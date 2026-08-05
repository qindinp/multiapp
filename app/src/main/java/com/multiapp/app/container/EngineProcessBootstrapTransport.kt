package com.multiapp.app.container

import android.app.Application
import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.multiapp.core.engine.EngineComponentProcessLaunchTicket
import com.multiapp.core.engine.EngineProcessBootstrapKind
import com.multiapp.core.engine.EngineProcessBootstrapReadiness
import com.multiapp.core.engine.EngineProcessBootstrapRequest
import com.multiapp.core.engine.EngineProcessBootstrapResult
import com.multiapp.core.engine.EngineProcessBootstrapState
import com.multiapp.core.engine.EngineProcessBootstrapper
import com.multiapp.core.engine.EngineProcessClientIdentity
import com.multiapp.core.engine.EngineRuntimeAuthorityValidator
import com.multiapp.core.engine.EngineRuntimeIpcClients
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineProcessSlotContract
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualRuntimeState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object EngineProcessBootstrapIpc {
    const val METHOD_PREPARE = "prepareGuestProcess"
    const val AUTHORITY_SUFFIX = ".multiapp.bootstrap.v"
    const val PROCESS_SLOT_COUNT = EngineProcessSlotContract.PROCESS_SLOT_COUNT
    const val DEFAULT_TIMEOUT_MS = 45_000L

    private const val KEY_INSTANCE_ID = "instanceId"
    private const val KEY_RUNTIME_EPOCH = "runtimeEpoch"
    private const val KEY_ENGINE_SESSION_ID = "engineSessionId"
    private const val KEY_CLIENT_TOKEN = "clientToken"
    private const val KEY_PROCESS_SLOT = "processSlot"
    private const val KEY_PROVIDER_ROUTING_ENABLED = "providerRoutingEnabled"
    private const val KEY_LEGACY_PROVIDER_HOOK_ENABLED = "legacyProviderHookEnabled"
    private const val KEY_EVIDENCE_MODE = "evidenceMode"
    private const val KEY_KIND = "kind"
    private const val KEY_COMPONENT_LAUNCH_TICKET = "componentLaunchTicket"
    private const val KEY_EFFECTIVE_GUEST_PROCESS_NAME = "effectiveGuestProcessName"
    private const val KEY_ATTACH_CAPABILITY = "attachCapability"
    private const val KEY_STATE = "state"
    private const val KEY_VERDICT = "verdict"
    private const val KEY_PROCESS_ID = "processId"
    private const val KEY_PROCESS_START_TICKS = "processStartTicks"
    private const val KEY_PROCESS_NAME = "processName"
    private const val KEY_CACHED = "cached"
    private const val KEY_DURATION_MS = "durationMs"
    private const val KEY_LAUNCHER_ACTIVITY = "launcherActivityClassName"
    private const val KEY_APPLICATION_STATUS = "applicationStatus"
    private const val KEY_PROVIDER_PREINSTALL_STATUS = "providerPreinstallStatus"
    private const val KEY_SYSTEM_SERVICE_PROXY_STATUS = "systemServiceProxyStatus"
    private const val KEY_MESSAGE = "message"
    private const val KEY_EVIDENCE = "evidence"

    fun authority(hostPackageName: String, processSlot: String): String? {
        // 严格校验：processSlot 必须是 host 的 canonical 槽位（2026-08-01，P1-SEC-01）
        if (!EngineProcessSlotContract.isCanonicalProcessSlot(hostPackageName, processSlot)) return null
        val index = processSlotIndex(processSlot) ?: return null
        return hostPackageName + AUTHORITY_SUFFIX + index
    }

    fun processSlotIndex(processSlot: String): Int? {
        // 严格化（2026-08-01，P1-SEC-01）：拒绝多分隔符注入、非严格十进制、前导零别名。
        // 无 hostPackageName 参数时无法验证包名前缀（foreign 包名格式上合法），
        // 由 authority() 的 isCanonicalProcessSlot 在有权校验 host 的路径补足。
        val lastColonV = processSlot.lastIndexOf(":v")
        if (lastColonV < 0) return null
        val prefix = processSlot.substring(0, lastColonV)
        if (prefix.isBlank() || ':' in prefix || '/' in prefix) return null
        val suffix = processSlot.substring(lastColonV + 2)
        if (suffix.isEmpty() || !suffix.all(Char::isDigit)) return null
        val index = suffix.toIntOrNull() ?: return null
        // 严格十进制：v03 是 v3 的前导零别名，必须拒绝（canonical 形式才接受）
        if (index.toString() != suffix) return null
        return index.takeIf { it in 0 until EngineProcessSlotContract.PROCESS_SLOT_COUNT }
    }

    fun requestEnvelope(request: EngineProcessBootstrapRequest): EngineProcessBootstrapRequestEnvelope =
        EngineProcessBootstrapRequestEnvelope(
            instanceId = request.runtime.instanceId,
            runtimeEpoch = request.runtime.runtimeEpoch,
            engineSessionId = request.runtime.engineSessionId,
            processSlot = request.runtime.processSlot,
            providerRoutingEnabled = request.providerRoutingEnabled,
            legacyProviderHookEnabled = request.legacyProviderHookEnabled,
            evidenceMode = request.evidenceMode,
            kind = request.kind,
            componentLaunchTicket = request.componentLaunchTicket
        )

    fun requestBundle(envelope: EngineProcessBootstrapRequestEnvelope): Bundle = Bundle().apply {
        putString(KEY_INSTANCE_ID, envelope.instanceId)
        putLong(KEY_RUNTIME_EPOCH, envelope.runtimeEpoch)
        putString(KEY_ENGINE_SESSION_ID, envelope.engineSessionId)
        putString(KEY_PROCESS_SLOT, envelope.processSlot)
        putBoolean(KEY_PROVIDER_ROUTING_ENABLED, envelope.providerRoutingEnabled)
        putBoolean(KEY_LEGACY_PROVIDER_HOOK_ENABLED, envelope.legacyProviderHookEnabled)
        putString(KEY_EVIDENCE_MODE, envelope.evidenceMode.name)
        putString(KEY_KIND, envelope.kind.name)
        envelope.componentLaunchTicket?.let { ticket ->
            putBundle(KEY_COMPONENT_LAUNCH_TICKET, componentLaunchTicketBundle(ticket))
        }
    }

    fun requestEnvelope(bundle: Bundle): EngineProcessBootstrapRequestEnvelope? {
        val kind = bundle.getString(KEY_KIND)
            ?.let { value -> runCatching { EngineProcessBootstrapKind.valueOf(value) }.getOrNull() }
            ?: return null
        val expectedFields = if (kind == EngineProcessBootstrapKind.COMPONENT_RUNTIME) {
            BOOTSTRAP_REQUEST_FIELDS + KEY_COMPONENT_LAUNCH_TICKET
        } else {
            BOOTSTRAP_REQUEST_FIELDS
        }
        if (bundle.keySet() != expectedFields) return null
        val instanceId = bundle.getString(KEY_INSTANCE_ID)?.takeIf { it.isNotBlank() } ?: return null
        val runtimeEpoch = bundle.getLong(KEY_RUNTIME_EPOCH).takeIf { it > 0L } ?: return null
        val engineSessionId = bundle.getString(KEY_ENGINE_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        val processSlot = bundle.getString(KEY_PROCESS_SLOT)?.takeIf { it.isNotBlank() } ?: return null
        val evidenceMode = bundle.getString(KEY_EVIDENCE_MODE)
            ?.let { value -> runCatching { EngineEvidenceMode.valueOf(value) }.getOrNull() }
            ?: return null
        val componentLaunchTicket = when (kind) {
            EngineProcessBootstrapKind.PRIMARY_RUNTIME -> null
            EngineProcessBootstrapKind.COMPONENT_RUNTIME -> bundle
                .getBundle(KEY_COMPONENT_LAUNCH_TICKET)
                ?.let(::componentLaunchTicket)
                ?: return null
        }
        return EngineProcessBootstrapRequestEnvelope(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            providerRoutingEnabled = bundle.getBoolean(KEY_PROVIDER_ROUTING_ENABLED),
            legacyProviderHookEnabled = bundle.getBoolean(KEY_LEGACY_PROVIDER_HOOK_ENABLED),
            evidenceMode = evidenceMode,
            kind = kind,
            componentLaunchTicket = componentLaunchTicket
        ).takeIf(EngineProcessBootstrapRequestEnvelope::isWellFormed)
    }

    fun resultBundle(result: EngineProcessBootstrapResult): Bundle = Bundle().apply {
        putString(KEY_STATE, result.state.name)
        putString(KEY_VERDICT, result.verdict.name)
        putString(KEY_INSTANCE_ID, result.instanceId)
        putLong(KEY_RUNTIME_EPOCH, result.runtimeEpoch)
        putString(KEY_ENGINE_SESSION_ID, result.engineSessionId)
        result.clientToken?.let { putBinder(KEY_CLIENT_TOKEN, it) }
        result.processId?.let { putInt(KEY_PROCESS_ID, it) }
        result.processStartTicks?.let { putLong(KEY_PROCESS_START_TICKS, it) }
        putString(KEY_PROCESS_NAME, result.processName)
        putBoolean(KEY_CACHED, result.cached)
        putLong(KEY_DURATION_MS, result.durationMs)
        putString(KEY_LAUNCHER_ACTIVITY, result.launcherActivityClassName)
        putString(KEY_APPLICATION_STATUS, result.applicationStatus)
        putString(KEY_PROVIDER_PREINSTALL_STATUS, result.providerPreinstallStatus)
        putString(KEY_SYSTEM_SERVICE_PROXY_STATUS, result.systemServiceProxyStatus)
        putString(KEY_MESSAGE, result.message)
        putBundle(KEY_EVIDENCE, Bundle().apply {
            result.evidence.forEach { (key, value) -> putString(key, value) }
        })
    }

    fun result(bundle: Bundle): EngineProcessBootstrapResult? = runCatching {
        val state = bundle.getString(KEY_STATE)
            ?.let { value -> EngineProcessBootstrapState.valueOf(value) }
            ?: return@runCatching null
        val verdict = bundle.getString(KEY_VERDICT)
            ?.let { value -> EngineResultStatus.valueOf(value) }
            ?: return@runCatching null
        val instanceId = bundle.getString(KEY_INSTANCE_ID)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val runtimeEpoch = bundle.getLong(KEY_RUNTIME_EPOCH).takeIf { it > 0L } ?: return@runCatching null
        val engineSessionId = bundle.getString(KEY_ENGINE_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val message = bundle.getString(KEY_MESSAGE)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val evidence = bundle.getBundle(KEY_EVIDENCE)
            ?.keySet()
            ?.associateWith { key -> bundle.getBundle(KEY_EVIDENCE)?.getString(key).orEmpty() }
            .orEmpty()
        EngineProcessBootstrapResult(
            state = state,
            verdict = verdict,
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            clientToken = bundle.getBinder(KEY_CLIENT_TOKEN),
            processId = if (bundle.containsKey(KEY_PROCESS_ID)) bundle.getInt(KEY_PROCESS_ID).takeIf { it > 0 } else null,
            processStartTicks = if (bundle.containsKey(KEY_PROCESS_START_TICKS)) {
                bundle.getLong(KEY_PROCESS_START_TICKS).takeIf { it > 0L }
            } else {
                null
            },
            processName = bundle.getString(KEY_PROCESS_NAME)?.takeIf { it.isNotBlank() },
            cached = bundle.getBoolean(KEY_CACHED),
            durationMs = bundle.getLong(KEY_DURATION_MS).coerceAtLeast(0L),
            launcherActivityClassName = bundle.getString(KEY_LAUNCHER_ACTIVITY)?.takeIf { it.isNotBlank() },
            applicationStatus = bundle.getString(KEY_APPLICATION_STATUS)?.takeIf { it.isNotBlank() },
            providerPreinstallStatus = bundle.getString(KEY_PROVIDER_PREINSTALL_STATUS)?.takeIf { it.isNotBlank() },
            systemServiceProxyStatus = bundle.getString(KEY_SYSTEM_SERVICE_PROXY_STATUS)?.takeIf { it.isNotBlank() },
            message = message,
            evidence = evidence
        )
    }.getOrNull()

    private fun componentLaunchTicketBundle(ticket: EngineComponentProcessLaunchTicket): Bundle =
        Bundle().apply {
            putString(KEY_INSTANCE_ID, ticket.instanceId)
            putString(KEY_EFFECTIVE_GUEST_PROCESS_NAME, ticket.effectiveGuestProcessName)
            putString(KEY_PROCESS_SLOT, ticket.processSlot)
            putString(KEY_ATTACH_CAPABILITY, ticket.attachCapability)
        }

    private fun componentLaunchTicket(bundle: Bundle): EngineComponentProcessLaunchTicket? {
        if (bundle.keySet() != COMPONENT_LAUNCH_TICKET_FIELDS) return null
        val ticket = EngineComponentProcessLaunchTicket(
            instanceId = bundle.getString(KEY_INSTANCE_ID).orEmpty(),
            effectiveGuestProcessName = bundle.getString(KEY_EFFECTIVE_GUEST_PROCESS_NAME).orEmpty(),
            processSlot = bundle.getString(KEY_PROCESS_SLOT).orEmpty(),
            attachCapability = bundle.getString(KEY_ATTACH_CAPABILITY).orEmpty()
        )
        return ticket.takeIf(::isWellFormedComponentLaunchTicket)
    }

    private val BOOTSTRAP_REQUEST_FIELDS = setOf(
        KEY_INSTANCE_ID,
        KEY_RUNTIME_EPOCH,
        KEY_ENGINE_SESSION_ID,
        KEY_PROCESS_SLOT,
        KEY_PROVIDER_ROUTING_ENABLED,
        KEY_LEGACY_PROVIDER_HOOK_ENABLED,
        KEY_EVIDENCE_MODE,
        KEY_KIND
    )

    private val COMPONENT_LAUNCH_TICKET_FIELDS = setOf(
        KEY_INSTANCE_ID,
        KEY_EFFECTIVE_GUEST_PROCESS_NAME,
        KEY_PROCESS_SLOT,
        KEY_ATTACH_CAPABILITY
    )
}

internal data class EngineProcessBootstrapRequestEnvelope(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val providerRoutingEnabled: Boolean,
    val legacyProviderHookEnabled: Boolean,
    val evidenceMode: EngineEvidenceMode,
    val kind: EngineProcessBootstrapKind = EngineProcessBootstrapKind.PRIMARY_RUNTIME,
    val componentLaunchTicket: EngineComponentProcessLaunchTicket? = null
) {
    fun isWellFormed(): Boolean = when (kind) {
        EngineProcessBootstrapKind.PRIMARY_RUNTIME -> componentLaunchTicket == null
        EngineProcessBootstrapKind.COMPONENT_RUNTIME -> componentLaunchTicket?.let { ticket ->
            isWellFormedComponentLaunchTicket(ticket) &&
                ticket.instanceId == instanceId &&
                ticket.processSlot == processSlot
        } == true
    }
}

private fun isWellFormedComponentLaunchTicket(ticket: EngineComponentProcessLaunchTicket): Boolean =
    ticket.instanceId.isBootstrapText() &&
        ticket.effectiveGuestProcessName.isBootstrapText() &&
        ticket.processSlot.isBootstrapText() &&
        ticket.attachCapability.length in 32..4096 &&
        ticket.attachCapability == ticket.attachCapability.trim()

private fun String.isBootstrapText(): Boolean =
    isNotBlank() && this == trim() && length <= 4096

private fun bootstrapRuntimeStateAllowed(
    kind: EngineProcessBootstrapKind,
    state: VirtualRuntimeState
): Boolean = when (kind) {
    EngineProcessBootstrapKind.PRIMARY_RUNTIME -> state == VirtualRuntimeState.CREATED
    EngineProcessBootstrapKind.COMPONENT_RUNTIME -> state in COMPONENT_BOOTSTRAP_PRIMARY_STATES
}

private fun bootstrapRuntimeStateAllowed(
    kind: EngineProcessBootstrapKind,
    state: String?
): Boolean = state
    ?.let { value -> runCatching { VirtualRuntimeState.valueOf(value) }.getOrNull() }
    ?.let { parsed -> bootstrapRuntimeStateAllowed(kind, parsed) }
    ?: false

private val COMPONENT_BOOTSTRAP_PRIMARY_STATES = setOf(
    VirtualRuntimeState.CREATED,
    VirtualRuntimeState.PREWARMED,
    VirtualRuntimeState.RUNNING
)

internal fun interface EngineProcessBootstrapTransport {
    fun call(authority: String, request: EngineProcessBootstrapRequestEnvelope): EngineProcessBootstrapResult?
}

internal data class EngineProcessSlotRecycleResult(
    val status: String,
    val processId: Int? = null,
    val message: String,
    val slotReusable: Boolean = false
)

internal data class EngineProcessSlotRecycleRequest(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int,
    val processStartTicks: Long
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processId > 0) { "processId must be positive" }
        require(processStartTicks > 0L) { "processStartTicks must be positive" }
    }
}

internal fun interface EngineProcessSlotRecycler {
    fun recycle(request: EngineProcessSlotRecycleRequest): EngineProcessSlotRecycleResult

    companion object {
        val NO_OP = EngineProcessSlotRecycler {
            EngineProcessSlotRecycleResult("NOT_CONFIGURED", message = "process slot recycler not configured")
        }
    }
}

internal class ContentProviderEngineProcessBootstrapper internal constructor(
    private val hostPackageName: String,
    private val timeoutMs: Long,
    private val transport: EngineProcessBootstrapTransport,
    private val executor: ExecutorService,
    private val processRecycler: EngineProcessSlotRecycler = EngineProcessSlotRecycler.NO_OP
) : EngineProcessBootstrapper {
    private val inFlight = ConcurrentHashMap<String, InFlightBootstrapCall>()

    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    constructor(
        context: Context,
        timeoutMs: Long = EngineProcessBootstrapIpc.DEFAULT_TIMEOUT_MS
    ) : this(
        hostPackageName = (context.applicationContext ?: context).packageName,
        timeoutMs = timeoutMs,
        transport = contentResolverTransport(context),
        executor = newBootstrapExecutor(),
        processRecycler = activityManagerProcessRecycler(context)
    )

    override fun bootstrap(request: EngineProcessBootstrapRequest): EngineProcessBootstrapResult {
        val authority = EngineProcessBootstrapIpc.authority(hostPackageName, request.runtime.processSlot)
            ?: return failed(request, EngineProcessBootstrapState.UNSUPPORTED, "unsupported process slot")
        val identity = listOf(
            request.runtime.instanceId,
            request.runtime.runtimeEpoch,
            request.runtime.engineSessionId,
            request.kind.name,
            request.componentLaunchTicket?.effectiveGuestProcessName.orEmpty(),
            request.componentLaunchTicket?.attachCapability.orEmpty()
        ).joinToString(":")
        val slotKey = request.runtime.processSlot
        val started = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val completedResponse = AtomicReference<EngineProcessBootstrapResult?>()
        val cleanupStarted = AtomicBoolean(false)
        val cleanupResult = AtomicReference<EngineProcessSlotRecycleResult?>()
        lateinit var submitted: InFlightBootstrapCall
        val task = FutureTask {
            started.set(true)
            try {
                validatedResponse(
                    request = request,
                    response = transport.call(authority, EngineProcessBootstrapIpc.requestEnvelope(request))
                ).also(completedResponse::set)
            } finally {
                completed.set(true)
                if (timedOut.get()) {
                    cleanupTimedOutCall(slotKey, submitted)
                }
            }
        }
        submitted = InFlightBootstrapCall(
            identity = identity,
            request = request,
            future = task,
            started = started,
            completed = completed,
            timedOut = timedOut,
            completedResponse = completedResponse,
            cleanupStarted = cleanupStarted,
            cleanupResult = cleanupResult
        )
        val existing = inFlight.putIfAbsent(slotKey, submitted)
        val active = existing ?: submitted.also { executor.execute(task) }
        val ownsSlotTombstone = existing == null
        if (existing != null && existing.identity != identity) {
            return failed(
                request,
                EngineProcessBootstrapState.STALE,
                "process slot still draining a previous bootstrap generation"
            ).copy(
                evidence = mapOf("bootstrapInFlightTombstone" to "true")
            )
        }
        val result = try {
            active.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            active.timedOut.set(true)
            val cancelled = active.future.cancel(true)
            val recycle = if (!active.started.get()) {
                inFlight.remove(slotKey, active)
                EngineProcessSlotRecycleResult(
                    status = "NOT_STARTED",
                    message = "bootstrap task was cancelled before transport entry",
                    slotReusable = true
                )
            } else {
                if (active.completed.get()) {
                    cleanupTimedOutCall(slotKey, active)
                } else {
                    // Force remove the stale entry to unblock future launches.
                    // The timed-out FutureTask may never complete if the underlying
                    // ContentProvider call ignores interrupts; leaving the entry
                    // blocks this process slot indefinitely.
                    inFlight.remove(slotKey, active)
                }
                active.cleanupResult.get() ?: EngineProcessSlotRecycleResult(
                    status = if (cancelled) "DEFERRED_IN_FLIGHT" else "DEFERRED_NOT_CANCELLED",
                    message = "slot recycle deferred until the timed-out transport call exits"
                )
            }
            return failed(
                request,
                EngineProcessBootstrapState.TIMED_OUT,
                "process bootstrap timed out after ${timeoutMs}ms"
            ).withRecycleEvidence(recycle)
        } catch (error: Exception) {
            return failed(
                request,
                EngineProcessBootstrapState.FAILED,
                "process bootstrap transport failed: ${error.javaClass.name}:${error.message.orEmpty()}"
            ).withRecycleEvidence(
                if (ownsSlotTombstone) {
                    EngineProcessSlotRecycleResult(
                        status = "IDENTITY_UNAVAILABLE",
                        message = "transport failed before an exact process identity was observed"
                    )
                } else {
                    EngineProcessSlotRecycleResult(
                        status = "DEFERRED_TO_OWNER",
                        message = "the in-flight owner retains the slot tombstone"
                    )
                }
            )
        }
        var releaseSlotTombstone = result.state !in BOOTSTRAP_STATES_REQUIRING_RECYCLE
        return try {
            if (result.state in BOOTSTRAP_STATES_REQUIRING_RECYCLE) {
                val recycle = if (ownsSlotTombstone) {
                    recycleCompletedBootstrap(request, result)
                } else {
                    EngineProcessSlotRecycleResult(
                        status = "DEFERRED_TO_OWNER",
                        message = "the in-flight owner is responsible for slot cleanup"
                    )
                }
                releaseSlotTombstone = ownsSlotTombstone && recycle.slotReusable
                result.withRecycleEvidence(
                    recycle
                )
            } else {
                result
            }
        } finally {
            if (ownsSlotTombstone && releaseSlotTombstone) {
                inFlight.remove(slotKey, active)
            }
        }
    }

    private fun validatedResponse(
        request: EngineProcessBootstrapRequest,
        response: EngineProcessBootstrapResult?
    ): EngineProcessBootstrapResult {
        if (response == null) {
            Log.w("BootstrapTransport", "validatedResponse: response=null instanceId=${request.runtime.instanceId} slot=${request.runtime.processSlot}")
            return failed(
                request,
                EngineProcessBootstrapState.STALE,
                "bootstrap provider returned a malformed or stale response"
            )
        }
        if (!response.validates(request)) {
            val req = request.runtime
            Log.w("BootstrapTransport", "validatedResponse: validates=false " +
                "instanceId[r=${req.instanceId},p=${response.instanceId}] " +
                "epoch[r=${req.runtimeEpoch},p=${response.runtimeEpoch}] " +
                "sessionId[r=${req.engineSessionId},p=${response.engineSessionId}] " +
                "processName[r=${req.processSlot},p=${response.processName}] " +
                "state=${response.state} verdict=${response.verdict} msg=${response.message}")
            return failed(
                request,
                EngineProcessBootstrapState.STALE,
                "bootstrap provider returned a malformed or stale response"
            )
        }
        val clientToken = response.clientToken
        if (
            response.ready &&
            (clientToken == null || !clientToken.isBinderAlive ||
                response.processId == null || response.processStartTicks == null)
        ) {
            return response.copy(
                state = EngineProcessBootstrapState.STALE,
                verdict = EngineResultStatus.FAIL,
                message = "bootstrap provider returned READY without a live token and exact process identity"
            )
        }
        return response
    }

    private fun failed(
        request: EngineProcessBootstrapRequest,
        state: EngineProcessBootstrapState,
        message: String
    ): EngineProcessBootstrapResult = EngineProcessBootstrapResult(
        state = state,
        verdict = if (state == EngineProcessBootstrapState.UNSUPPORTED) {
            EngineResultStatus.UNSUPPORTED
        } else {
            EngineResultStatus.FAIL
        },
        instanceId = request.runtime.instanceId,
        runtimeEpoch = request.runtime.runtimeEpoch,
        engineSessionId = request.runtime.engineSessionId,
        processName = request.runtime.processSlot,
        message = message,
        evidence = mapOf("bootstrapKind" to request.kind.name)
    )

    private fun EngineProcessBootstrapResult.withRecycleEvidence(
        recycle: EngineProcessSlotRecycleResult
    ): EngineProcessBootstrapResult = copy(
        evidence = evidence + mapOf(
            "processSlotRecycleStatus" to recycle.status,
            "processSlotRecyclePid" to recycle.processId?.toString().orEmpty(),
            "processSlotRecycleMessage" to recycle.message,
            "processSlotReusable" to recycle.slotReusable.toString()
        )
    )

    private fun recycleCompletedBootstrap(
        request: EngineProcessBootstrapRequest,
        result: EngineProcessBootstrapResult?
    ): EngineProcessSlotRecycleResult {
        if (result == null || !result.validates(request)) {
            return EngineProcessSlotRecycleResult(
                status = "IDENTITY_UNAVAILABLE",
                message = "bootstrap response did not preserve the requested generation"
            )
        }
        val processId = result.processId
        val processStartTicks = result.processStartTicks
        if (processId == null || processStartTicks == null) {
            return EngineProcessSlotRecycleResult(
                status = "IDENTITY_UNAVAILABLE",
                processId = processId,
                message = "bootstrap response did not expose pid and process start ticks"
            )
        }
        val recycleRequest = EngineProcessSlotRecycleRequest(
            instanceId = request.runtime.instanceId,
            runtimeEpoch = request.runtime.runtimeEpoch,
            engineSessionId = request.runtime.engineSessionId,
            processSlot = request.runtime.processSlot,
            processId = processId,
            processStartTicks = processStartTicks
        )
        return runCatching { processRecycler.recycle(recycleRequest) }.getOrElse { error ->
            EngineProcessSlotRecycleResult(
                status = "FAILED",
                processId = processId,
                message = "${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }

    }

    private fun cleanupTimedOutCall(slotKey: String, call: InFlightBootstrapCall) {
        if (!call.cleanupStarted.compareAndSet(false, true)) return
        val recycle = recycleCompletedBootstrap(call.request, call.completedResponse.get())
        call.cleanupResult.set(recycle)
        if (recycle.slotReusable) {
            inFlight.remove(slotKey, call)
        }
    }

    private data class InFlightBootstrapCall(
        val identity: String,
        val request: EngineProcessBootstrapRequest,
        val future: Future<EngineProcessBootstrapResult>,
        val started: AtomicBoolean,
        val completed: AtomicBoolean,
        val timedOut: AtomicBoolean,
        val completedResponse: AtomicReference<EngineProcessBootstrapResult?>,
        val cleanupStarted: AtomicBoolean,
        val cleanupResult: AtomicReference<EngineProcessSlotRecycleResult?>
    )

    private companion object {
        fun contentResolverTransport(context: Context): EngineProcessBootstrapTransport {
            val appContext = context.applicationContext ?: context
            return EngineProcessBootstrapTransport { authority, envelope ->
                val response = appContext.contentResolver.call(
                    Uri.Builder().scheme("content").authority(authority).build(),
                    EngineProcessBootstrapIpc.METHOD_PREPARE,
                    null,
                    EngineProcessBootstrapIpc.requestBundle(envelope)
                )
                response?.let(EngineProcessBootstrapIpc::result)
            }
        }

        fun newBootstrapExecutor(): ExecutorService = Executors.newFixedThreadPool(
            EngineProcessBootstrapIpc.PROCESS_SLOT_COUNT
        ) { runnable ->
            Thread(runnable, "multiapp-process-bootstrap").apply { isDaemon = true }
        }

        fun activityManagerProcessRecycler(context: Context): EngineProcessSlotRecycler {
            val appContext = context.applicationContext ?: context
            return EngineProcessSlotRecycler { request ->
                val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                        status = "UNAVAILABLE",
                        processId = request.processId,
                        message = "ActivityManager unavailable"
                    )
                if (request.processId == Process.myPid()) {
                    return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                        status = "IDENTITY_MISMATCH",
                        processId = request.processId,
                        message = "bootstrap slot recycler must not terminate its caller process"
                    )
                }
                val process = manager.runningAppProcesses.orEmpty()
                    .singleOrNull { candidate -> candidate.pid == request.processId }
                val observedStartTicks = readAndroidProcessStartTicks(request.processId)
                if (process == null && observedStartTicks == null) {
                    return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                        status = "NOT_RUNNING",
                        processId = request.processId,
                        message = "the exact bootstrap process is no longer running",
                        slotReusable = true
                    )
                }
                if (
                    process == null ||
                    process.processName != request.processSlot ||
                    process.uid != appContext.applicationInfo.uid ||
                    observedStartTicks != request.processStartTicks
                ) {
                    return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                        status = "IDENTITY_MISMATCH",
                        processId = request.processId,
                        message = "pid, uid, process name, or start ticks no longer match the failed generation"
                    )
                }
                val killError = runCatching { Process.killProcess(request.processId) }.exceptionOrNull()
                if (killError != null) {
                    return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                        status = "FAILED",
                        processId = request.processId,
                        message = "${killError.javaClass.name}:${killError.message.orEmpty()}"
                    )
                }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_RECYCLE_WAIT_MS)
                while (System.nanoTime() < deadline) {
                    val currentStartTicks = readAndroidProcessStartTicks(request.processId)
                    if (currentStartTicks == null) {
                        return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                            status = "RECYCLED",
                            processId = request.processId,
                            message = "the exact bootstrap process exited after termination",
                            slotReusable = true
                        )
                    }
                    if (currentStartTicks != request.processStartTicks) {
                        return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                            status = "PID_REUSED",
                            processId = request.processId,
                            message = "the failed process exited but its pid was already reused"
                        )
                    }
                    try {
                        Thread.sleep(PROCESS_RECYCLE_POLL_MS)
                    } catch (_: InterruptedException) {
                        // Future.cancel(true) may leave the bootstrap worker interrupted. The
                        // exact PID/starttime check still has to finish before releasing the slot.
                    }
                }
                EngineProcessSlotRecycleResult(
                    status = "TERMINATION_TIMEOUT",
                    processId = request.processId,
                    message = "the exact bootstrap process did not exit before the recycle deadline"
                )
            }
        }

        val BOOTSTRAP_STATES_REQUIRING_RECYCLE = setOf(
            EngineProcessBootstrapState.FAILED,
            EngineProcessBootstrapState.STALE,
            EngineProcessBootstrapState.TIMED_OUT
        )

        const val PROCESS_RECYCLE_WAIT_MS = 1_500L
        const val PROCESS_RECYCLE_POLL_MS = 20L
    }
}

open class EngineProcessBootstrapProvider : ContentProvider() {
    private val processToken = Binder()

    /**
     * 进程启动早期缓存真实 Android 进程名（hook 安装前）。GuestProcessNameCompat
     * 会在 bootstrap 后期 hook Application.getProcessName() 返回 guest 名，因此
     * provider 身份校验必须使用此缓存，否则二次 provider call 会误判 process mismatch。
     */
    private val startupProcessName: String? by lazy {
        runCatching { Application.getProcessName() }.getOrNull()
    }
    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != EngineProcessBootstrapIpc.METHOD_PREPARE) return Bundle.EMPTY
        val hostContext = context?.applicationContext ?: context ?: return Bundle.EMPTY
        val envelope = extras?.let(EngineProcessBootstrapIpc::requestEnvelope) ?: return Bundle.EMPTY
        val startedAt = System.currentTimeMillis()
        if (Binder.getCallingUid() != hostContext.applicationInfo.uid) {
            return EngineProcessBootstrapIpc.resultBundle(
                failed(envelope, EngineProcessBootstrapState.FAILED, "bootstrap caller uid mismatch")
            )
        }
        EngineRuntimeIpcClients.install(hostContext)
        val authoritativeRuntime = EngineRuntimeIpcClients.engineQueryRuntimeState(
            instanceId = envelope.instanceId,
            runtimeEpoch = envelope.runtimeEpoch,
            engineSessionId = envelope.engineSessionId,
            processSlot = envelope.processSlot,
            componentAttachCapability = envelope.componentLaunchTicket?.attachCapability
        )
            ?: return EngineProcessBootstrapIpc.resultBundle(
                failed(envelope, EngineProcessBootstrapState.STALE, "authoritative runtime state missing")
            )
        val componentTicket = envelope.componentLaunchTicket
        val primaryProcessSlot = authoritativeRuntime.processSlot
        val authoritySnapshot = EngineRuntimeIpcClients.queryRuntime(envelope.instanceId)
        val authority = EngineRuntimeAuthorityValidator.validate(
            snapshot = authoritySnapshot,
            expectedProcessSlot = primaryProcessSlot,
            requireLiveAuthority = false
        )
        val envelopeMatchesRuntime = when (envelope.kind) {
            EngineProcessBootstrapKind.PRIMARY_RUNTIME ->
                componentTicket == null && envelope.processSlot == primaryProcessSlot
            EngineProcessBootstrapKind.COMPONENT_RUNTIME ->
                componentTicket != null &&
                    componentTicket.instanceId == envelope.instanceId &&
                    componentTicket.processSlot == envelope.processSlot &&
                    componentTicket.processSlot != primaryProcessSlot
        }
        if (authoritySnapshot == null || !authority.allowed ||
            authoritySnapshot?.runtimeEpoch != envelope.runtimeEpoch ||
            authoritySnapshot?.engineSessionId != envelope.engineSessionId ||
            authoritativeRuntime.runtimeEpoch != envelope.runtimeEpoch ||
            authoritativeRuntime.engineSessionId != envelope.engineSessionId ||
            !envelopeMatchesRuntime ||
            !bootstrapRuntimeStateAllowed(envelope.kind, authoritativeRuntime.state)
        ) {
            return failed(
                envelope,
                EngineProcessBootstrapState.STALE,
                "authoritative runtime validation failed:${authority.reason}"
            ).let(EngineProcessBootstrapIpc::resultBundle)
        }
        val processName = currentProcessName()
        if (processName != envelope.processSlot) {
            return failed(
                envelope,
                EngineProcessBootstrapState.STALE,
                "bootstrap provider process mismatch: expected=${envelope.processSlot},actual=$processName"
            ).let(EngineProcessBootstrapIpc::resultBundle)
        }
        val requestRuntime = if (componentTicket == null) {
            authoritativeRuntime
        } else {
            authoritativeRuntime.copy(
                processSlot = componentTicket.processSlot,
                processName = componentTicket.effectiveGuestProcessName
            )
        }
        val request = EngineProcessBootstrapRequest(
            runtime = requestRuntime,
            providerRoutingEnabled = envelope.providerRoutingEnabled,
            legacyProviderHookEnabled = envelope.legacyProviderHookEnabled,
            evidenceMode = envelope.evidenceMode,
            kind = envelope.kind,
            componentLaunchTicket = componentTicket
        )
        val bootstrap = runCatching {
            // Establish VClient-style process authority before guest providers
            // and Application.onCreate invoke virtual system services.
            val processAttachEvidence = when (envelope.kind) {
                EngineProcessBootstrapKind.PRIMARY_RUNTIME -> {
                    val processIdentity = EngineProcessClientIdentity(
                        instanceId = envelope.instanceId,
                        runtimeEpoch = envelope.runtimeEpoch,
                        engineSessionId = envelope.engineSessionId,
                        processSlot = envelope.processSlot,
                        processId = Process.myPid()
                    )
                    val clientAttach = EngineRuntimeIpcClients.attachClient(processIdentity, processToken)
                    if (
                        clientAttach == null || !clientAttach.accepted || !clientAttach.liveAuthority ||
                        clientAttach.identity != processIdentity ||
                        clientAttach.runtimeState != VirtualRuntimeState.CREATED
                    ) {
                        return@runCatching failed(
                            envelope,
                            EngineProcessBootstrapState.FAILED,
                            "engine process client pre-attach failed:${clientAttach?.reason ?: "ipc_unavailable"}"
                        )
                    }
                    mapOf(
                        "engineProcessClientPreAttached" to "true",
                        "engineProcessClientAttachIdempotent" to clientAttach.idempotent.toString(),
                        "engineProcessClientAuthority" to "BINDER_LIVE"
                    )
                }
                EngineProcessBootstrapKind.COMPONENT_RUNTIME -> {
                    val ticket = checkNotNull(componentTicket)
                    val clientAttach = EngineRuntimeIpcClients.attachComponentProcessClient(ticket, processToken)
                    val processState = clientAttach?.processState
                    if (
                        clientAttach == null || !clientAttach.accepted || processState == null ||
                        !processState.live ||
                        processState.instanceId != ticket.instanceId ||
                        processState.effectiveGuestProcessName != ticket.effectiveGuestProcessName ||
                        processState.processSlot != ticket.processSlot ||
                        processState.processId != Process.myPid()
                    ) {
                        return@runCatching failed(
                            envelope,
                            EngineProcessBootstrapState.FAILED,
                            "engine component process pre-attach failed:${clientAttach?.reason ?: "ipc_unavailable"}"
                        )
                    }
                    mapOf(
                        "engineComponentProcessClientPreAttached" to "true",
                        "engineComponentProcessClientAttachIdempotent" to clientAttach.idempotent.toString(),
                        "engineComponentProcessClientAuthority" to "BINDER_LIVE",
                        "effectiveGuestProcessName" to ticket.effectiveGuestProcessName
                    )
                }
            }
            val runtimeEngine = hostedRuntimeEngineFrom(hostContext)
            val cached = runtimeEngine.reusableResult(
                instanceId = envelope.instanceId,
                providerHookEnabled = envelope.legacyProviderHookEnabled,
                processSlot = envelope.processSlot,
                effectiveGuestProcessName = componentTicket?.effectiveGuestProcessName
            )
            val result = cached ?: runtimeEngine.bindApplication(
                instanceId = envelope.instanceId,
                providerHookEnabled = envelope.legacyProviderHookEnabled,
                processSlot = envelope.processSlot,
                effectiveGuestProcessName = componentTicket?.effectiveGuestProcessName
            ).result
            val readiness = EngineProcessBootstrapReadiness.evaluate(
                request = request,
                result = result,
                processId = Process.myPid(),
                processName = processName,
                cached = cached != null,
                durationMs = System.currentTimeMillis() - startedAt
            )
            if (!readiness.ready) {
                readiness.copy(evidence = readiness.evidence + processAttachEvidence)
            } else {
                val postBootstrapRuntime = EngineRuntimeIpcClients.engineQueryRuntimeState(
                    instanceId = envelope.instanceId,
                    runtimeEpoch = envelope.runtimeEpoch,
                    engineSessionId = envelope.engineSessionId,
                    processSlot = envelope.processSlot,
                    componentAttachCapability = envelope.componentLaunchTicket?.attachCapability
                )
                val postBootstrapAuthority = EngineRuntimeIpcClients.queryRuntime(envelope.instanceId)
                val postBootstrapDecision = EngineRuntimeAuthorityValidator.validate(
                    snapshot = postBootstrapAuthority,
                    expectedProcessSlot = primaryProcessSlot,
                    requireLiveAuthority = false
                )
                if (
                    postBootstrapRuntime == null || postBootstrapAuthority == null ||
                    !postBootstrapDecision.allowed ||
                    postBootstrapRuntime.runtimeEpoch != envelope.runtimeEpoch ||
                    postBootstrapRuntime.engineSessionId != envelope.engineSessionId ||
                    postBootstrapRuntime.processSlot != primaryProcessSlot ||
                    postBootstrapAuthority.runtimeEpoch != envelope.runtimeEpoch ||
                    postBootstrapAuthority.engineSessionId != envelope.engineSessionId ||
                    !bootstrapRuntimeStateAllowed(envelope.kind, postBootstrapRuntime.state) ||
                    !bootstrapRuntimeStateAllowed(envelope.kind, postBootstrapAuthority.runtimeState)
                ) {
                    return@runCatching failed(
                        envelope,
                        EngineProcessBootstrapState.STALE,
                        "authoritative runtime changed while guest bootstrap was running"
                    )
                }
                val guestApplication = result.guestApplication
                    ?: return@runCatching failed(
                        envelope,
                        EngineProcessBootstrapState.FAILED,
                        "READY bootstrap did not expose guest Application"
                    )
                val guestClassLoader = result.guestClassLoader
                    ?: return@runCatching failed(
                        envelope,
                        EngineProcessBootstrapState.FAILED,
                        "READY bootstrap did not expose guest ClassLoader"
                    )
                when (envelope.kind) {
                    EngineProcessBootstrapKind.PRIMARY_RUNTIME -> {
                        val ackRegistered = EngineGuestForegroundAcknowledger.global.register(
                            guestApplication = guestApplication,
                            guestClassLoader = guestClassLoader,
                            request = EngineGuestForegroundAckRequest(
                                instanceId = envelope.instanceId,
                                runtimeEpoch = envelope.runtimeEpoch,
                                engineSessionId = envelope.engineSessionId,
                                processSlot = envelope.processSlot,
                                processId = Process.myPid()
                            )
                        )
                        if (!ackRegistered) {
                            failed(
                                envelope,
                                EngineProcessBootstrapState.FAILED,
                                "unable to register guest Activity foreground acknowledgement"
                            )
                        } else {
                            readiness.copy(
                                evidence = readiness.evidence +
                                    processAttachEvidence +
                                    mapOf(
                                        "guestForegroundAckRegistered" to "true",
                                        "engineProcessClientAttached" to "true"
                                    )
                            )
                        }
                    }
                    EngineProcessBootstrapKind.COMPONENT_RUNTIME -> {
                        readiness.copy(
                            evidence = readiness.evidence +
                                processAttachEvidence +
                                mapOf(
                                    "guestForegroundAckRegistered" to "false",
                                    "engineComponentProcessClientAttached" to "true"
                                )
                        )
                    }
                }
            }
        }.getOrElse { error ->
            failed(
                envelope,
                EngineProcessBootstrapState.FAILED,
                "guest bootstrap failed: ${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
        val response = bootstrap.copy(
            clientToken = processToken,
            processId = Process.myPid(),
            processStartTicks = readAndroidProcessStartTicks(Process.myPid())
        )
        runCatching {
            val fields = linkedMapOf<String, Any?>(
                "status" to response.state.name,
                "stage" to "PROCESS_BOOTSTRAP",
                "verdict" to response.verdict.name,
                "instanceId" to response.instanceId,
                "processId" to response.processId,
                "processStartTicks" to response.processStartTicks,
                "processName" to response.processName,
                "message" to response.message
            )
            response.toOperationEvidence().entries.forEach { (key, value) -> fields[key] = value }
            ContainerRuntimeEvidenceWriter.write(
                context = hostContext,
                instanceId = response.instanceId,
                component = "process-bootstrap",
                fields = fields
            )
            response.packageManagerProxyEvidenceFields()?.let { packageManagerFields ->
                ContainerRuntimeEvidenceWriter.write(
                    context = hostContext,
                    instanceId = response.instanceId,
                    component = "package-manager-proxy",
                    fields = packageManagerFields
                )
            }
        }
        return EngineProcessBootstrapIpc.resultBundle(response)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun failed(
        envelope: EngineProcessBootstrapRequestEnvelope,
        state: EngineProcessBootstrapState,
        message: String
    ): EngineProcessBootstrapResult = EngineProcessBootstrapResult(
        state = state,
        verdict = EngineResultStatus.FAIL,
        instanceId = envelope.instanceId,
        runtimeEpoch = envelope.runtimeEpoch,
        engineSessionId = envelope.engineSessionId,
        processId = Process.myPid(),
        processStartTicks = readAndroidProcessStartTicks(Process.myPid()),
        processName = envelope.processSlot,
        message = message,
        evidence = mapOf(
            "bootstrapKind" to envelope.kind.name,
            "effectiveGuestProcessName" to
                envelope.componentLaunchTicket?.effectiveGuestProcessName.orEmpty()
        )
    )

    private fun currentProcessName(): String = when {
        // 优先使用进程启动时缓存（不受 guest 进程名 hook 影响）
        !startupProcessName.isNullOrBlank() -> startupProcessName.orEmpty()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Application.getProcessName().orEmpty()
        else -> runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrDefault("")
    }
}

class EngineProcessBootstrapProviderV0 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV1 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV2 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV3 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV4 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV5 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV6 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV7 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV8 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV9 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV10 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV11 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV12 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV13 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV14 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV15 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV16 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV17 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV18 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV19 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV20 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV21 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV22 : EngineProcessBootstrapProvider()
class EngineProcessBootstrapProviderV23 : EngineProcessBootstrapProvider()

internal fun EngineProcessBootstrapResult.packageManagerProxyEvidenceFields(): Map<String, Any?>? {
    val prefix = "packageManagerProxy."
    val stageFields = evidence
        .asSequence()
        .filter { (key, _) -> key.startsWith(prefix) }
        .associate { (key, value) -> key.removePrefix(prefix) to value }
    if (stageFields.isEmpty()) return null
    return linkedMapOf<String, Any?>(
        "stage" to (stageFields["stage"] ?: "PACKAGE_MANAGER_PROXY"),
        "status" to (stageFields["status"] ?: systemServiceProxyStatus.orEmpty())
    ).apply {
        stageFields.forEach { (key, value) ->
            if (key !in keys) put(key, value)
        }
    }
}

private fun readAndroidProcessStartTicks(processId: Int): Long? {
    if (processId <= 0) return null
    return runCatching {
        val stat = File("/proc/$processId/stat").readText()
        val fieldsAfterName = stat.substringAfterLast(") ", missingDelimiterValue = "")
            .trim()
            .split(Regex("\\s+"))
        fieldsAfterName.getOrNull(PROC_STAT_STARTTIME_OFFSET_AFTER_NAME)
            ?.toLongOrNull()
            ?.takeIf { ticks -> ticks > 0L }
    }.getOrNull()
}

private const val PROC_STAT_STARTTIME_OFFSET_AFTER_NAME = 19


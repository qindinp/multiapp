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
import com.multiapp.core.engine.EngineProcessBootstrapReadiness
import com.multiapp.core.engine.EngineProcessBootstrapRequest
import com.multiapp.core.engine.EngineProcessBootstrapResult
import com.multiapp.core.engine.EngineProcessBootstrapState
import com.multiapp.core.engine.EngineProcessBootstrapper
import com.multiapp.core.engine.EngineProcessClientIdentity
import com.multiapp.core.engine.EngineRuntimeAuthorityValidator
import com.multiapp.core.engine.EngineRuntimeIpcClients
import com.multiapp.core.engine.EngineRuntimeInstallers
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualRuntimeState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal object EngineProcessBootstrapIpc {
    const val METHOD_PREPARE = "prepareGuestProcess"
    const val AUTHORITY_SUFFIX = ".multiapp.bootstrap.v"
    const val PROCESS_SLOT_COUNT = 8
    const val DEFAULT_TIMEOUT_MS = 45_000L

    private const val KEY_INSTANCE_ID = "instanceId"
    private const val KEY_RUNTIME_EPOCH = "runtimeEpoch"
    private const val KEY_ENGINE_SESSION_ID = "engineSessionId"
    private const val KEY_CLIENT_TOKEN = "clientToken"
    private const val KEY_PROCESS_SLOT = "processSlot"
    private const val KEY_PROVIDER_ROUTING_ENABLED = "providerRoutingEnabled"
    private const val KEY_LEGACY_PROVIDER_HOOK_ENABLED = "legacyProviderHookEnabled"
    private const val KEY_EVIDENCE_MODE = "evidenceMode"
    private const val KEY_STATE = "state"
    private const val KEY_VERDICT = "verdict"
    private const val KEY_PROCESS_ID = "processId"
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
        val index = processSlotIndex(processSlot) ?: return null
        return hostPackageName + AUTHORITY_SUFFIX + index
    }

    fun processSlotIndex(processSlot: String): Int? = processSlot
        .substringAfterLast(":v", missingDelimiterValue = "")
        .toIntOrNull()
        ?.takeIf { it in 0 until PROCESS_SLOT_COUNT }

    fun requestEnvelope(request: EngineProcessBootstrapRequest): EngineProcessBootstrapRequestEnvelope =
        EngineProcessBootstrapRequestEnvelope(
            instanceId = request.runtime.instanceId,
            runtimeEpoch = request.runtime.runtimeEpoch,
            engineSessionId = request.runtime.engineSessionId,
            processSlot = request.runtime.processSlot,
            providerRoutingEnabled = request.providerRoutingEnabled,
            legacyProviderHookEnabled = request.legacyProviderHookEnabled,
            evidenceMode = request.evidenceMode
        )

    fun requestBundle(envelope: EngineProcessBootstrapRequestEnvelope): Bundle = Bundle().apply {
        putString(KEY_INSTANCE_ID, envelope.instanceId)
        putLong(KEY_RUNTIME_EPOCH, envelope.runtimeEpoch)
        putString(KEY_ENGINE_SESSION_ID, envelope.engineSessionId)
        putString(KEY_PROCESS_SLOT, envelope.processSlot)
        putBoolean(KEY_PROVIDER_ROUTING_ENABLED, envelope.providerRoutingEnabled)
        putBoolean(KEY_LEGACY_PROVIDER_HOOK_ENABLED, envelope.legacyProviderHookEnabled)
        putString(KEY_EVIDENCE_MODE, envelope.evidenceMode.name)
    }

    fun requestEnvelope(bundle: Bundle): EngineProcessBootstrapRequestEnvelope? {
        val instanceId = bundle.getString(KEY_INSTANCE_ID)?.takeIf { it.isNotBlank() } ?: return null
        val runtimeEpoch = bundle.getLong(KEY_RUNTIME_EPOCH).takeIf { it > 0L } ?: return null
        val engineSessionId = bundle.getString(KEY_ENGINE_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        val processSlot = bundle.getString(KEY_PROCESS_SLOT)?.takeIf { it.isNotBlank() } ?: return null
        val evidenceMode = bundle.getString(KEY_EVIDENCE_MODE)
            ?.let { value -> runCatching { EngineEvidenceMode.valueOf(value) }.getOrNull() }
            ?: EngineEvidenceMode.DEFAULT
        return EngineProcessBootstrapRequestEnvelope(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            providerRoutingEnabled = bundle.getBoolean(KEY_PROVIDER_ROUTING_ENABLED),
            legacyProviderHookEnabled = bundle.getBoolean(KEY_LEGACY_PROVIDER_HOOK_ENABLED),
            evidenceMode = evidenceMode
        )
    }

    fun resultBundle(result: EngineProcessBootstrapResult): Bundle = Bundle().apply {
        putString(KEY_STATE, result.state.name)
        putString(KEY_VERDICT, result.verdict.name)
        putString(KEY_INSTANCE_ID, result.instanceId)
        putLong(KEY_RUNTIME_EPOCH, result.runtimeEpoch)
        putString(KEY_ENGINE_SESSION_ID, result.engineSessionId)
        result.clientToken?.let { putBinder(KEY_CLIENT_TOKEN, it) }
        result.processId?.let { putInt(KEY_PROCESS_ID, it) }
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
}

internal data class EngineProcessBootstrapRequestEnvelope(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val providerRoutingEnabled: Boolean,
    val legacyProviderHookEnabled: Boolean,
    val evidenceMode: EngineEvidenceMode
)

internal fun interface EngineProcessBootstrapTransport {
    fun call(authority: String, request: EngineProcessBootstrapRequestEnvelope): EngineProcessBootstrapResult?
}

internal data class EngineProcessSlotRecycleResult(
    val status: String,
    val processId: Int? = null,
    val message: String
)

internal fun interface EngineProcessSlotRecycler {
    fun recycle(processSlot: String): EngineProcessSlotRecycleResult

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
            request.runtime.engineSessionId
        ).joinToString(":")
        val slotKey = request.runtime.processSlot
        val started = AtomicBoolean(false)
        lateinit var submitted: InFlightBootstrapCall
        val task = FutureTask {
            started.set(true)
            try {
                validatedResponse(
                    request = request,
                    response = transport.call(authority, EngineProcessBootstrapIpc.requestEnvelope(request))
                )
            } finally {
                inFlight.remove(slotKey, submitted)
            }
        }
        submitted = InFlightBootstrapCall(identity, task, started)
        val existing = inFlight.putIfAbsent(slotKey, submitted)
        val active = existing ?: submitted.also { executor.execute(task) }
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
            val cancelled = active.future.cancel(true)
            if (cancelled && !active.started.get()) {
                inFlight.remove(slotKey, active)
            }
            return failed(
                request,
                EngineProcessBootstrapState.TIMED_OUT,
                "process bootstrap timed out after ${timeoutMs}ms"
            ).withRecycleEvidence(recycleProcessSlot(slotKey))
        } catch (error: Exception) {
            return failed(
                request,
                EngineProcessBootstrapState.FAILED,
                "process bootstrap transport failed: ${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
        return if (result.state in setOf(EngineProcessBootstrapState.FAILED, EngineProcessBootstrapState.TIMED_OUT)) {
            result.withRecycleEvidence(recycleProcessSlot(slotKey))
        } else {
            result
        }
    }

    private fun validatedResponse(
        request: EngineProcessBootstrapRequest,
        response: EngineProcessBootstrapResult?
    ): EngineProcessBootstrapResult {
        if (response == null || !response.validates(request)) {
            return failed(
                request,
                EngineProcessBootstrapState.STALE,
                "bootstrap provider returned a malformed or stale response"
            )
        }
        val clientToken = response.clientToken
        if (response.ready && (clientToken == null || !clientToken.isBinderAlive || response.processId == null)) {
            return failed(
                request,
                EngineProcessBootstrapState.STALE,
                "bootstrap provider returned READY without a live process token and pid"
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
        message = message
    )

    private fun EngineProcessBootstrapResult.withRecycleEvidence(
        recycle: EngineProcessSlotRecycleResult
    ): EngineProcessBootstrapResult = copy(
        evidence = evidence + mapOf(
            "processSlotRecycleStatus" to recycle.status,
            "processSlotRecyclePid" to recycle.processId?.toString().orEmpty(),
            "processSlotRecycleMessage" to recycle.message
        )
    )

    private fun recycleProcessSlot(processSlot: String): EngineProcessSlotRecycleResult =
        runCatching { processRecycler.recycle(processSlot) }.getOrElse { error ->
            EngineProcessSlotRecycleResult(
                status = "FAILED",
                message = "${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }

    private data class InFlightBootstrapCall(
        val identity: String,
        val future: Future<EngineProcessBootstrapResult>,
        val started: AtomicBoolean
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
            return EngineProcessSlotRecycler { processSlot ->
                val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return@EngineProcessSlotRecycler EngineProcessSlotRecycleResult(
                        status = "UNAVAILABLE",
                        message = "ActivityManager unavailable"
                    )
                val matches = manager.runningAppProcesses.orEmpty().filter { process ->
                    process.processName == processSlot &&
                        process.uid == appContext.applicationInfo.uid &&
                        process.pid > 0 && process.pid != Process.myPid()
                }
                when (matches.size) {
                    0 -> EngineProcessSlotRecycleResult(
                        status = "NOT_RUNNING",
                        message = "target process slot is not running"
                    )
                    1 -> {
                        val processId = matches.single().pid
                        runCatching { Process.killProcess(processId) }.fold(
                            onSuccess = {
                                EngineProcessSlotRecycleResult(
                                    status = "KILL_REQUESTED",
                                    processId = processId,
                                    message = "target process slot recycle requested"
                                )
                            },
                            onFailure = { error ->
                                EngineProcessSlotRecycleResult(
                                    status = "FAILED",
                                    processId = processId,
                                    message = "${error.javaClass.name}:${error.message.orEmpty()}"
                                )
                            }
                        )
                    }
                    else -> EngineProcessSlotRecycleResult(
                        status = "AMBIGUOUS",
                        message = "multiple processes matched the same process slot"
                    )
                }
            }
        }
    }
}

open class EngineProcessBootstrapProvider : ContentProvider() {
    private val processToken = Binder()
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
        val localRuntime = EngineRuntimeInstallers.fileBackedSystemServer(hostContext)
            .registry
            .get(envelope.instanceId)
            ?: return EngineProcessBootstrapIpc.resultBundle(
                failed(envelope, EngineProcessBootstrapState.STALE, "local runtime state missing")
            )
        EngineRuntimeIpcClients.install(hostContext)
        val authoritySnapshot = EngineRuntimeIpcClients.queryRuntime(envelope.instanceId)
        val authority = EngineRuntimeAuthorityValidator.validate(
            snapshot = authoritySnapshot,
            expectedProcessSlot = envelope.processSlot,
            requireLiveAuthority = false
        )
        if (authoritySnapshot == null || !authority.allowed ||
            authoritySnapshot?.runtimeEpoch != envelope.runtimeEpoch ||
            authoritySnapshot?.engineSessionId != envelope.engineSessionId ||
            localRuntime.runtimeEpoch != envelope.runtimeEpoch ||
            localRuntime.engineSessionId != envelope.engineSessionId ||
            localRuntime.processSlot != envelope.processSlot
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
        val request = EngineProcessBootstrapRequest(
            runtime = localRuntime,
            providerRoutingEnabled = envelope.providerRoutingEnabled,
            legacyProviderHookEnabled = envelope.legacyProviderHookEnabled,
            evidenceMode = envelope.evidenceMode
        )
        val bootstrap = runCatching {
            val runtimeEngine = hostedRuntimeEngineFrom(hostContext)
            val cached = runtimeEngine.reusableResult(
                instanceId = envelope.instanceId,
                providerHookEnabled = envelope.legacyProviderHookEnabled,
                processSlot = envelope.processSlot
            )
            val result = cached ?: runtimeEngine.bindApplication(
                instanceId = envelope.instanceId,
                providerHookEnabled = envelope.legacyProviderHookEnabled,
                processSlot = envelope.processSlot
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
                readiness
            } else {
                val postBootstrapRuntime = EngineRuntimeInstallers.fileBackedSystemServer(hostContext)
                    .registry
                    .get(envelope.instanceId)
                val postBootstrapAuthority = EngineRuntimeIpcClients.queryRuntime(envelope.instanceId)
                val postBootstrapDecision = EngineRuntimeAuthorityValidator.validate(
                    snapshot = postBootstrapAuthority,
                    expectedProcessSlot = envelope.processSlot,
                    requireLiveAuthority = false
                )
                if (
                    postBootstrapRuntime == null || postBootstrapAuthority == null ||
                    !postBootstrapDecision.allowed ||
                    postBootstrapRuntime.runtimeEpoch != envelope.runtimeEpoch ||
                    postBootstrapRuntime.engineSessionId != envelope.engineSessionId ||
                    postBootstrapAuthority.runtimeEpoch != envelope.runtimeEpoch ||
                    postBootstrapAuthority.engineSessionId != envelope.engineSessionId ||
                    postBootstrapAuthority.runtimeState != "CREATED"
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
                        "engine process client attach failed:${clientAttach?.reason ?: "ipc_unavailable"}"
                    )
                }
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
                            mapOf(
                                "guestForegroundAckRegistered" to "true",
                                "engineProcessClientAttached" to "true",
                                "engineProcessClientAttachIdempotent" to clientAttach.idempotent.toString(),
                                "engineProcessClientAuthority" to "BINDER_LIVE"
                            )
                    )
                }
            }
        }.getOrElse { error ->
            failed(
                envelope,
                EngineProcessBootstrapState.FAILED,
                "guest bootstrap failed: ${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
        return EngineProcessBootstrapIpc.resultBundle(
            bootstrap.copy(clientToken = processToken)
        )
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
        processName = envelope.processSlot,
        message = message
    )

    private fun currentProcessName(): String = when {
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

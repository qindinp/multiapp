package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime

enum class EngineProcessBootstrapState {
    READY,
    FAILED,
    STALE,
    TIMED_OUT,
    UNSUPPORTED
}

enum class EngineProcessBootstrapKind {
    PRIMARY_RUNTIME,
    COMPONENT_RUNTIME
}

data class EngineProcessBootstrapRequest(
    val runtime: VirtualInstanceRuntime,
    val providerRoutingEnabled: Boolean,
    val legacyProviderHookEnabled: Boolean,
    val evidenceMode: EngineEvidenceMode,
    val kind: EngineProcessBootstrapKind = EngineProcessBootstrapKind.PRIMARY_RUNTIME,
    val componentLaunchTicket: EngineComponentProcessLaunchTicket? = null
) {
    init {
        when (kind) {
            EngineProcessBootstrapKind.PRIMARY_RUNTIME -> require(componentLaunchTicket == null) {
                "primary runtime bootstrap must not carry a component launch ticket"
            }
            EngineProcessBootstrapKind.COMPONENT_RUNTIME -> {
                val ticket = requireNotNull(componentLaunchTicket) {
                    "component runtime bootstrap requires a component launch ticket"
                }
                require(ticket.instanceId == runtime.instanceId) {
                    "component launch ticket instance must match the runtime"
                }
                require(ticket.processSlot == runtime.processSlot) {
                    "component launch ticket process slot must match the runtime view"
                }
                require(ticket.effectiveGuestProcessName == runtime.processName) {
                    "component launch ticket process name must match the runtime view"
                }
                require(ticket.attachCapability.length >= MIN_COMPONENT_BOOTSTRAP_CAPABILITY_LENGTH) {
                    "component launch ticket capability must be unguessable"
                }
                require(ticket.attachCapability == ticket.attachCapability.trim()) {
                    "component launch ticket capability must be trimmed"
                }
            }
        }
    }

    private companion object {
        const val MIN_COMPONENT_BOOTSTRAP_CAPABILITY_LENGTH = 32
    }
}

data class EngineProcessBootstrapResult(
    val state: EngineProcessBootstrapState,
    val verdict: EngineResultStatus,
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val clientToken: IBinder? = null,
    val processId: Int? = null,
    val processStartTicks: Long? = null,
    val processName: String? = null,
    val cached: Boolean = false,
    val durationMs: Long = 0L,
    val launcherActivityClassName: String? = null,
    val applicationStatus: String? = null,
    val providerPreinstallStatus: String? = null,
    val systemServiceProxyStatus: String? = null,
    val message: String,
    val evidence: Map<String, String> = emptyMap()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processId == null || processId > 0) { "processId must be positive" }
        require(processStartTicks == null || processStartTicks > 0L) {
            "processStartTicks must be positive"
        }
        require(processName?.isNotBlank() ?: true) { "processName must not be blank" }
        require(durationMs >= 0L) { "durationMs must not be negative" }
        require(message.isNotBlank()) { "message must not be blank" }
        require(evidence.keys.none { it.isBlank() }) { "evidence keys must not be blank" }
    }

    val ready: Boolean
        get() = state == EngineProcessBootstrapState.READY &&
            verdict != EngineResultStatus.FAIL &&
            verdict != EngineResultStatus.UNSUPPORTED

    fun validates(request: EngineProcessBootstrapRequest): Boolean {
        val runtime = request.runtime
        return instanceId == runtime.instanceId &&
            runtimeEpoch == runtime.runtimeEpoch &&
            engineSessionId == runtime.engineSessionId &&
            processName == runtime.processSlot
    }

    fun toOperationEvidence(): EngineOperationEvidence = EngineOperationEvidence(
        component = "runtime",
        operation = "process-bootstrap",
        verdict = verdict,
        entries = linkedMapOf(
            "bootstrapState" to state.name,
            "runtimeEpoch" to runtimeEpoch.toString(),
            "engineSessionId" to engineSessionId,
            "clientTokenPresent" to (clientToken != null).toString(),
            "processId" to processId?.toString().orEmpty(),
            "processStartTicks" to processStartTicks?.toString().orEmpty(),
            "processName" to processName.orEmpty(),
            "cached" to cached.toString(),
            "durationMs" to durationMs.toString(),
            "launcherActivityClassName" to launcherActivityClassName.orEmpty(),
            "applicationStatus" to applicationStatus.orEmpty(),
            "providerPreinstallStatus" to providerPreinstallStatus.orEmpty(),
            "systemServiceProxyStatus" to systemServiceProxyStatus.orEmpty(),
            "message" to message
        ) + evidence
    )

    companion object {
        private const val TEST_PROCESS_ID = 1

        fun immediateReady(request: EngineProcessBootstrapRequest): EngineProcessBootstrapResult {
            val runtime = request.runtime
            return EngineProcessBootstrapResult(
                state = EngineProcessBootstrapState.READY,
                verdict = EngineResultStatus.PASS,
                instanceId = runtime.instanceId,
                runtimeEpoch = runtime.runtimeEpoch,
                engineSessionId = runtime.engineSessionId,
                clientToken = null,
                processId = runtime.processId ?: TEST_PROCESS_ID,
                processName = runtime.processSlot,
                cached = true,
                launcherActivityClassName = runtime.packageSnapshot.launcherActivityName
                    ?: runtime.packageSnapshot.activities.firstOrNull()?.name,
                applicationStatus = "TEST_IMMEDIATE_READY",
                providerPreinstallStatus = "TEST_IMMEDIATE_READY",
                systemServiceProxyStatus = "TEST_IMMEDIATE_READY",
                message = "process bootstrap supplied by immediate test adapter",
                evidence = mapOf(
                    "bootstrapTransport" to "immediate-test-adapter",
                    "bootstrapKind" to request.kind.name
                )
            )
        }

        fun unsupported(
            request: EngineProcessBootstrapRequest,
            message: String
        ): EngineProcessBootstrapResult = EngineProcessBootstrapResult(
            state = EngineProcessBootstrapState.UNSUPPORTED,
            verdict = EngineResultStatus.UNSUPPORTED,
            instanceId = request.runtime.instanceId,
            runtimeEpoch = request.runtime.runtimeEpoch,
            engineSessionId = request.runtime.engineSessionId,
            processName = request.runtime.processSlot,
            message = message,
            evidence = mapOf("bootstrapKind" to request.kind.name)
        )
    }
}

fun interface EngineProcessBootstrapper {
    fun bootstrap(request: EngineProcessBootstrapRequest): EngineProcessBootstrapResult

    companion object {
        val IMMEDIATE = EngineProcessBootstrapper(EngineProcessBootstrapResult::immediateReady)
        val PRIMARY_IMMEDIATE_COMPONENT_DEFERRED = EngineProcessBootstrapper { request ->
            if (request.kind == EngineProcessBootstrapKind.COMPONENT_RUNTIME) {
                EngineProcessBootstrapResult.unsupported(
                    request,
                    "component process bootstrap is not configured for this adapter"
                )
            } else {
                EngineProcessBootstrapResult.immediateReady(request)
            }
        }
    }
}

object EngineProcessBootstrapReadiness {
    fun evaluate(
        request: EngineProcessBootstrapRequest,
        result: EngineHostedBootstrapResult,
        processId: Int,
        processName: String,
        cached: Boolean,
        durationMs: Long
    ): EngineProcessBootstrapResult {
        val applicationStage = result.firstStageResult(EngineBootstrapStage.APPLICATION)
        val systemServiceStage = result.firstStageResult(EngineBootstrapStage.PACKAGE_MANAGER_PROXY)
        val applicationStatus = applicationStage
            ?.evidence
            ?.get("loadedApkApplicationCreatorStatus")
            ?: applicationStage?.status?.name
        val providerPreinstallStatus = applicationStage?.evidence?.get("providerPreinstallStatus")
        val systemServiceProxyStatus = systemServiceStage?.status?.name
        val identityMatches = result.instanceId == request.runtime.instanceId &&
            result.processSlot == request.runtime.processSlot &&
            processName == request.runtime.processSlot
        val providerPreinstallReady = providerPreinstallStatus != null &&
            providerPreinstallStatus !in setOf("PARTIAL", "FAILED")
        val launcherReady = request.kind == EngineProcessBootstrapKind.COMPONENT_RUNTIME ||
            !result.launcherActivityClassName.isNullOrBlank()
        val mandatoryRuntimeReady = result.success &&
            result.guestClassLoader != null &&
            result.guestApplication != null &&
            applicationStage?.status == EngineBootstrapStatus.SUCCESS &&
            applicationStatus == "PASS" &&
            systemServiceStage?.status != EngineBootstrapStatus.FAILED &&
            providerPreinstallReady &&
            launcherReady

        if (!identityMatches) {
            return baseResult(
                request = request,
                state = EngineProcessBootstrapState.STALE,
                verdict = EngineResultStatus.FAIL,
                result = result,
                processId = processId,
                processName = processName,
                cached = cached,
                durationMs = durationMs,
                applicationStatus = applicationStatus,
                providerPreinstallStatus = providerPreinstallStatus,
                systemServiceProxyStatus = systemServiceProxyStatus,
                message = "hosted bootstrap identity does not match the authoritative engine runtime"
            )
        }
        if (!mandatoryRuntimeReady) {
            return baseResult(
                request = request,
                state = EngineProcessBootstrapState.FAILED,
                verdict = EngineResultStatus.FAIL,
                result = result,
                processId = processId,
                processName = processName,
                cached = cached,
                durationMs = durationMs,
                applicationStatus = applicationStatus,
                providerPreinstallStatus = providerPreinstallStatus,
                systemServiceProxyStatus = systemServiceProxyStatus,
                message = result.summary.failureReason
                    ?: "hosted bootstrap did not produce a complete guest runtime"
            )
        }

        val degraded = systemServiceStage?.status in setOf(
            EngineBootstrapStatus.DEGRADED,
            EngineBootstrapStatus.SKIPPED
        )
        return baseResult(
            request = request,
            state = EngineProcessBootstrapState.READY,
            verdict = if (degraded) EngineResultStatus.PARTIAL else EngineResultStatus.PASS,
            result = result,
            processId = processId,
            processName = processName,
            cached = cached,
            durationMs = durationMs,
            applicationStatus = applicationStatus,
            providerPreinstallStatus = providerPreinstallStatus,
            systemServiceProxyStatus = systemServiceProxyStatus,
            message = if (degraded) {
                "guest process is READY with degraded bootstrap evidence"
            } else {
                "guest process is READY"
            }
        )
    }

    private fun baseResult(
        request: EngineProcessBootstrapRequest,
        state: EngineProcessBootstrapState,
        verdict: EngineResultStatus,
        result: EngineHostedBootstrapResult,
        processId: Int,
        processName: String,
        cached: Boolean,
        durationMs: Long,
        applicationStatus: String?,
        providerPreinstallStatus: String?,
        systemServiceProxyStatus: String?,
        message: String
    ): EngineProcessBootstrapResult = EngineProcessBootstrapResult(
        state = state,
        verdict = verdict,
        instanceId = request.runtime.instanceId,
        runtimeEpoch = request.runtime.runtimeEpoch,
        engineSessionId = request.runtime.engineSessionId,
        processId = processId,
        processName = processName,
        cached = cached,
        durationMs = durationMs,
        launcherActivityClassName = result.launcherActivityClassName,
        applicationStatus = applicationStatus,
        providerPreinstallStatus = providerPreinstallStatus,
        systemServiceProxyStatus = systemServiceProxyStatus,
        message = message,
        evidence = linkedMapOf(
            "hostedBootstrapSuccess" to result.success.toString(),
            "hostedBootstrapProcessSlot" to result.processSlot.orEmpty(),
            "bootstrapKind" to request.kind.name,
            "effectiveGuestProcessName" to
                request.componentLaunchTicket?.effectiveGuestProcessName.orEmpty(),
            "guestClassLoaderReady" to (result.guestClassLoader != null).toString(),
            "guestApplicationReady" to (result.guestApplication != null).toString(),
            "applicationStageStatus" to (result.firstStageResult(EngineBootstrapStage.APPLICATION)?.status?.name ?: "MISSING"),
            "systemServiceStageStatus" to
                (result.firstStageResult(EngineBootstrapStage.PACKAGE_MANAGER_PROXY)?.status?.name ?: "MISSING")
        )
    )
}

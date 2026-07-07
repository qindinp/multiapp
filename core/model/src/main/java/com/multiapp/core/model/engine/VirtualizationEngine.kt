package com.multiapp.core.model.engine

import com.multiapp.core.model.virtual.VirtualPackageSnapshot

interface VirtualizationEngine {
    fun installOrRefreshPackage(originPackageName: String): EngineResult
    fun createInstance(originPackageName: String): EngineResult
    fun launchInstance(request: LaunchInstanceRequest): EngineResult
    fun stopInstance(instanceId: String): EngineResult
    fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime?
    fun exportEvidence(instanceId: String): EngineEvidenceReport
}

enum class EngineProfile {
    BASELINE,
    COMPAT_HOOK,
    DIAGNOSTICS_ONLY,
    EXPERIMENTAL_COMPAT
}

enum class EngineResultStatus {
    PASS,
    PARTIAL,
    FAIL,
    UNSUPPORTED
}

data class LaunchInstanceRequest(
    val instanceId: String,
    val profile: EngineProfile = EngineProfile.BASELINE,
    val requestedLauncherActivityClass: String? = null,
    val reason: String = "user"
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

data class VirtualInstanceRuntime(
    val instanceId: String,
    val hostPackageName: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val dataRoot: String,
    val packageSnapshot: VirtualPackageSnapshot,
    val profile: EngineProfile,
    val processSlot: String,
    val proxySlot: String,
    val evidenceSessionId: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(hostPackageName.isNotBlank()) { "hostPackageName must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(dataRoot.isNotBlank()) { "dataRoot must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(proxySlot.isNotBlank()) { "proxySlot must not be blank" }
        require(evidenceSessionId.isNotBlank()) { "evidenceSessionId must not be blank" }
    }
}

data class EngineResult(
    val operation: String,
    val status: EngineResultStatus,
    val instanceId: String? = null,
    val originPackageName: String? = null,
    val message: String? = null,
    val runtime: VirtualInstanceRuntime? = null,
    val evidence: EngineEvidenceReport? = null
) {
    val success: Boolean
        get() = status == EngineResultStatus.PASS || status == EngineResultStatus.PARTIAL

    fun toUnitResult(): Result<Unit> {
        return if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(message ?: "$operation failed with $status"))
        }
    }

    companion object {
        fun pass(
            operation: String,
            instanceId: String? = null,
            originPackageName: String? = null,
            message: String? = null,
            runtime: VirtualInstanceRuntime? = null,
            evidence: EngineEvidenceReport? = null
        ): EngineResult = EngineResult(
            operation = operation,
            status = EngineResultStatus.PASS,
            instanceId = instanceId,
            originPackageName = originPackageName,
            message = message,
            runtime = runtime,
            evidence = evidence
        )

        fun partial(
            operation: String,
            instanceId: String? = null,
            originPackageName: String? = null,
            message: String,
            runtime: VirtualInstanceRuntime? = null,
            evidence: EngineEvidenceReport? = null
        ): EngineResult = EngineResult(
            operation = operation,
            status = EngineResultStatus.PARTIAL,
            instanceId = instanceId,
            originPackageName = originPackageName,
            message = message,
            runtime = runtime,
            evidence = evidence
        )

        fun fail(
            operation: String,
            instanceId: String? = null,
            originPackageName: String? = null,
            message: String
        ): EngineResult = EngineResult(
            operation = operation,
            status = EngineResultStatus.FAIL,
            instanceId = instanceId,
            originPackageName = originPackageName,
            message = message
        )

        fun unsupported(
            operation: String,
            instanceId: String? = null,
            originPackageName: String? = null,
            message: String
        ): EngineResult = EngineResult(
            operation = operation,
            status = EngineResultStatus.UNSUPPORTED,
            instanceId = instanceId,
            originPackageName = originPackageName,
            message = message
        )
    }
}

data class EngineEvidenceReport(
    val instanceId: String,
    val evidenceSessionId: String,
    val status: EngineResultStatus,
    val profile: EngineProfile,
    val entries: Map<String, String> = emptyMap()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(evidenceSessionId.isNotBlank()) { "evidenceSessionId must not be blank" }
    }
}

object EngineLaunchIntentContract {
    const val CONTAINER_ACTIVITY_CLASS_NAME = "com.multiapp.app.container.ContainerActivity"
    const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
    const val EXTRA_ENABLE_PROVIDER_HOOK = "multiapp.profile.providerHookEnabled"
    const val EXTRA_ENGINE_PROFILE = "multiapp.engine.profile"
    const val EXTRA_ENGINE_PROCESS_SLOT = "multiapp.engine.processSlot"
    const val EXTRA_ENGINE_PROXY_SLOT = "multiapp.engine.proxySlot"
    const val EXTRA_ENGINE_EVIDENCE_SESSION_ID = "multiapp.engine.evidenceSessionId"
}

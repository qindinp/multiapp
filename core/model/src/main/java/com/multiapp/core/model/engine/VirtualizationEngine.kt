package com.multiapp.core.model.engine

import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.instance.CompatibilityMode

interface VirtualizationEngine {
    fun installOrRefreshPackage(originPackageName: String): EngineResult
    fun createInstance(originPackageName: String): EngineResult
    fun createInstance(request: CreateInstanceRequest): EngineResult =
        createInstance(request.originPackageName)
    fun launchInstance(request: LaunchInstanceRequest): EngineResult
    fun stopInstance(instanceId: String): EngineResult
    fun deleteInstance(instanceId: String): EngineResult
    fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime?
    fun exportEvidence(instanceId: String): EngineEvidenceReport
}

data class EnginePackageInstallRequest(
    val originPackageName: String,
    val originApkPath: String,
    val versionCode: Long,
    val versionName: String,
    val targetSdk: Int,
    val minSdk: Int,
    val applicationClassName: String? = null,
    val packageLabel: String,
    val requestedPermissions: List<String> = emptyList(),
    val activityClassNames: List<String> = emptyList(),
    val serviceClassNames: List<String> = emptyList(),
    val receiverClassNames: List<String> = emptyList(),
    val providerClassNames: List<String> = emptyList(),
    val nativeAbis: List<String> = emptyList(),
    val splitApkPaths: List<String> = emptyList(),
    val splitPublicSourceDirs: List<String> = emptyList(),
    val splitNames: List<String> = emptyList(),
    val isolatedSplits: Boolean = false
) {
    init {
        require(originPackageName.isNotBlank() && originPackageName.length <= MAX_IDENTITY_LENGTH) {
            "originPackageName must be non-blank and at most $MAX_IDENTITY_LENGTH characters"
        }
        require(originPackageName.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "originPackageName contains unsafe characters"
        }
        require(!originPackageName.contains("..")) { "originPackageName contains an unsafe segment" }
        require(originApkPath.isNotBlank() && originApkPath.length <= MAX_PATH_LENGTH) {
            "originApkPath must be non-blank and at most $MAX_PATH_LENGTH characters"
        }
        require('\u0000' !in originApkPath) { "originApkPath contains NUL" }
        require(versionCode > 0L) { "versionCode must be positive" }
        require(versionName.isNotBlank() && versionName.length <= MAX_LABEL_LENGTH) {
            "versionName must be non-blank and at most $MAX_LABEL_LENGTH characters"
        }
        require(targetSdk > 0) { "targetSdk must be positive" }
        require(minSdk > 0) { "minSdk must be positive" }
        require(
            applicationClassName == null ||
                applicationClassName.isNotBlank() &&
                applicationClassName.length <= MAX_IDENTITY_LENGTH &&
                '\u0000' !in applicationClassName
        ) {
            "applicationClassName must be null or a valid class name"
        }
        require(packageLabel.isNotBlank() && packageLabel.length <= MAX_LABEL_LENGTH) {
            "packageLabel must be non-blank and at most $MAX_LABEL_LENGTH characters"
        }
        validateEntries("requestedPermissions", requestedPermissions)
        validateEntries("activityClassNames", activityClassNames)
        validateEntries("serviceClassNames", serviceClassNames)
        validateEntries("receiverClassNames", receiverClassNames)
        validateEntries("providerClassNames", providerClassNames)
        validateEntries("nativeAbis", nativeAbis)
        validatePaths("splitApkPaths", splitApkPaths)
        validatePaths("splitPublicSourceDirs", splitPublicSourceDirs)
        validateEntries("splitNames", splitNames)
        require(splitPublicSourceDirs.isEmpty() || splitPublicSourceDirs.size == splitApkPaths.size) {
            "splitPublicSourceDirs size must match splitApkPaths size"
        }
        require(splitNames.isEmpty() || splitNames.size == splitApkPaths.size) {
            "splitNames size must match splitApkPaths size"
        }
        val ipcTextSize = listOf(
            requestedPermissions,
            activityClassNames,
            serviceClassNames,
            receiverClassNames,
            providerClassNames,
            nativeAbis,
            splitApkPaths,
            splitPublicSourceDirs,
            splitNames
        ).flatten().sumOf { value -> value.length } +
            originApkPath.length + packageLabel.length + versionName.length
        require(ipcTextSize <= MAX_IPC_TEXT_LENGTH) {
            "install metadata exceeds the engine IPC text budget"
        }
    }

    private fun validateEntries(name: String, entries: List<String>) {
        require(entries.size <= MAX_ENTRY_COUNT) { "$name exceeds $MAX_ENTRY_COUNT entries" }
        require(entries.all { it.isNotBlank() && it.length <= MAX_IDENTITY_LENGTH && '\u0000' !in it }) {
            "$name contains an invalid entry"
        }
    }

    private fun validatePaths(name: String, paths: List<String>) {
        require(paths.size <= MAX_SPLIT_COUNT) { "$name exceeds $MAX_SPLIT_COUNT entries" }
        require(paths.all { it.isNotBlank() && it.length <= MAX_PATH_LENGTH && '\u0000' !in it }) {
            "$name contains an invalid path"
        }
    }
}

data class CreateInstanceRequest(
    val creationRequestId: String,
    val install: EnginePackageInstallRequest,
    val displayName: String,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.DEFAULT
) {
    val originPackageName: String
        get() = install.originPackageName

    init {
        require(creationRequestId.isNotBlank() && creationRequestId.length <= MAX_IDENTITY_LENGTH) {
            "creationRequestId must be non-blank and at most $MAX_IDENTITY_LENGTH characters"
        }
        require(creationRequestId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "creationRequestId contains unsafe characters"
        }
        require(displayName.isNotBlank() && displayName == displayName.trim()) {
            "displayName must be non-blank and trimmed"
        }
        require(displayName.length <= MAX_LABEL_LENGTH) {
            "displayName must be at most $MAX_LABEL_LENGTH characters"
        }
        require('\u0000' !in displayName) { "displayName contains NUL" }
    }
}

private const val MAX_IDENTITY_LENGTH = 512
private const val MAX_LABEL_LENGTH = 256
private const val MAX_PATH_LENGTH = 4_096
private const val MAX_ENTRY_COUNT = 4_096
private const val MAX_SPLIT_COUNT = 256
private const val MAX_IPC_TEXT_LENGTH = 262_144

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

enum class EngineSubsystem {
    RUNTIME,
    PACKAGE,
    ACTIVITY,
    PROVIDER,
    PERMISSION,
    APP_OPS,
    SERVICE,
    BROADCAST,
    STORAGE,
    NATIVE,
    EVIDENCE
}

enum class VirtualRuntimeState {
    CREATED,
    PREWARMED,
    RUNNING,
    STOPPED,
    DEAD
}

enum class EngineTaskPolicy {
    DEFAULT,
    NEW_TASK,
    REUSE_EXISTING
}

enum class EnginePrewarmPolicy {
    DEFAULT,
    REQUIRED,
    DISABLED
}

enum class EngineEvidenceMode {
    DEFAULT,
    FULL,
    MINIMAL
}

data class LaunchInstanceRequest(
    val instanceId: String,
    val profile: EngineProfile = EngineProfile.BASELINE,
    val requestedLauncherActivityClass: String? = null,
    val reason: String = "user",
    val targetComponentClassName: String? = null,
    val launchFlags: Int = 0,
    val taskPolicy: EngineTaskPolicy = EngineTaskPolicy.DEFAULT,
    val prewarmPolicy: EnginePrewarmPolicy = EnginePrewarmPolicy.DEFAULT,
    val evidenceMode: EngineEvidenceMode = EngineEvidenceMode.DEFAULT
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(requestedLauncherActivityClass?.isNotBlank() ?: true) {
            "requestedLauncherActivityClass must not be blank"
        }
        require(targetComponentClassName?.isNotBlank() ?: true) {
            "targetComponentClassName must not be blank"
        }
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
    val evidenceSessionId: String,
    val runtimeEpoch: Long = 1L,
    val engineSessionId: String = evidenceSessionId,
    val processId: Int? = null,
    val processName: String? = null,
    val state: VirtualRuntimeState = VirtualRuntimeState.CREATED
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
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processId == null || processId > 0) { "processId must be positive" }
        require(processName?.isNotBlank() ?: true) { "processName must not be blank" }
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
    val entries: Map<String, String> = emptyMap(),
    val operationEvidence: Map<String, Map<String, List<EngineOperationEvidence>>> = emptyMap(),
    val subsystemVerdicts: Map<EngineSubsystem, EngineResultStatus> = emptyMap()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(evidenceSessionId.isNotBlank()) { "evidenceSessionId must not be blank" }
        require(operationEvidence.keys.none { it.isBlank() }) {
            "operationEvidence component keys must not be blank"
        }
        require(operationEvidence.values.all { operations -> operations.keys.none { it.isBlank() } }) {
            "operationEvidence operation keys must not be blank"
        }
    }

    fun withOperationEvidence(evidence: EngineOperationEvidence): EngineEvidenceReport {
        val componentEvidence = operationEvidence[evidence.component].orEmpty()
        val mergedOperationEvidence = componentEvidence[evidence.operation].orEmpty() + evidence
        val mergedComponentEvidence = componentEvidence + (evidence.operation to mergedOperationEvidence)
        return copy(
            status = status.merge(evidence.verdict),
            operationEvidence = operationEvidence + (evidence.component to mergedComponentEvidence)
        )
    }

    fun withSubsystemVerdict(subsystem: EngineSubsystem, verdict: EngineResultStatus): EngineEvidenceReport {
        return copy(
            status = status.merge(verdict),
            subsystemVerdicts = subsystemVerdicts + (subsystem to verdict)
        )
    }

    fun operationEntries(component: String, operation: String): List<EngineOperationEvidence> =
        operationEvidence[component]?.get(operation).orEmpty()

    fun flattenedOperationEvidence(): List<EngineOperationEvidence> =
        operationEvidence
            .toSortedMap()
            .flatMap { (_, operations) ->
                operations
                    .toSortedMap()
                    .flatMap { (_, evidences) ->
                        evidences.map { evidence ->
                            evidence.copy(entries = evidence.entries.toSortedMap().toMap())
                        }
                    }
            }
}

data class EngineOperationEvidence(
    val component: String,
    val operation: String,
    val verdict: EngineResultStatus,
    val entries: Map<String, String> = emptyMap()
) {
    init {
        require(component.isNotBlank()) { "component must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(entries.keys.none { it.isBlank() }) { "entries keys must not be blank" }
    }
}

private fun EngineResultStatus.merge(verdict: EngineResultStatus): EngineResultStatus {
    return if (verdict.rank() > rank()) verdict else this
}

private fun EngineResultStatus.rank(): Int {
    return when (this) {
        EngineResultStatus.PASS -> 0
        EngineResultStatus.PARTIAL -> 1
        EngineResultStatus.UNSUPPORTED -> 2
        EngineResultStatus.FAIL -> 3
    }
}

object EngineLaunchIntentContract {
    const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
    const val EXTRA_ENABLE_PROVIDER_HOOK = "multiapp.profile.providerHookEnabled"
    const val EXTRA_ENGINE_PROFILE = "multiapp.engine.profile"
    const val EXTRA_ENGINE_EVIDENCE_MODE = "multiapp.engine.evidenceMode"
    const val EXTRA_ENGINE_PROCESS_SLOT = "multiapp.engine.processSlot"
    const val EXTRA_ENGINE_PROXY_SLOT = "multiapp.engine.proxySlot"
    const val EXTRA_ENGINE_EVIDENCE_SESSION_ID = "multiapp.engine.evidenceSessionId"
}

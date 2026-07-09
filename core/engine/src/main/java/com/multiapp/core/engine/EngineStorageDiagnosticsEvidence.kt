package com.multiapp.core.engine

import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStorageDiagnosticStatus
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import com.multiapp.core.loader.VirtualStoragePathDiagnostics
import java.io.File

data class EngineStorageDiagnosticsPlan(
    val instanceId: String,
    val bootstrapUnsupportedEntry: EngineStorageEvidenceEntry? = null,
    val diagnostics: List<EngineStoragePathDiagnostic> = emptyList()
)

data class EngineStorageEvidenceEntry(
    val instanceId: String,
    val component: String,
    val operationName: String,
    val fields: Map<String, Any?>
)

enum class EngineStorageDiagnosticKind {
    JAVA_ABSOLUTE_PATH,
    NATIVE_IO;

    companion object {
        fun fromLoader(kind: VirtualStorageDiagnosticKind): EngineStorageDiagnosticKind =
            when (kind) {
                VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH -> JAVA_ABSOLUTE_PATH
                VirtualStorageDiagnosticKind.NATIVE_IO -> NATIVE_IO
            }
    }
}

enum class EngineStorageDiagnosticStatus {
    REDIRECTED,
    UNCHANGED,
    UNSUPPORTED;

    companion object {
        fun fromLoader(status: VirtualStorageDiagnosticStatus): EngineStorageDiagnosticStatus =
            when (status) {
                VirtualStorageDiagnosticStatus.REDIRECTED -> REDIRECTED
                VirtualStorageDiagnosticStatus.UNCHANGED -> UNCHANGED
                VirtualStorageDiagnosticStatus.UNSUPPORTED -> UNSUPPORTED
            }
    }
}

data class EngineStoragePathDiagnostic(
    val kind: EngineStorageDiagnosticKind,
    val status: EngineStorageDiagnosticStatus,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val dataRoot: String,
    val probeName: String?,
    val operation: String?,
    val originalPath: String,
    val redirectedPath: String,
    val candidateRedirectedPath: String?,
    val caller: String,
    val reason: String?,
    val withinDataRoot: Boolean,
    val candidateWithinDataRoot: Boolean?,
    val nativeProbeResultCode: Int? = null,
    val nativeProbeErrno: Int? = null,
    val nativeProbeCandidateExists: Boolean? = null,
    val nativeProbeResolvedPath: String? = null
) {
    companion object {
        fun fromLoader(diagnostic: VirtualStoragePathDiagnostic): EngineStoragePathDiagnostic =
            EngineStoragePathDiagnostic(
                kind = EngineStorageDiagnosticKind.fromLoader(diagnostic.kind),
                status = EngineStorageDiagnosticStatus.fromLoader(diagnostic.status),
                instanceId = diagnostic.instanceId,
                originPackageName = diagnostic.originPackageName,
                virtualPackageName = diagnostic.virtualPackageName,
                dataRoot = diagnostic.dataRoot,
                probeName = diagnostic.probeName,
                operation = diagnostic.operation,
                originalPath = diagnostic.originalPath,
                redirectedPath = diagnostic.redirectedPath,
                candidateRedirectedPath = diagnostic.candidateRedirectedPath,
                caller = diagnostic.caller,
                reason = diagnostic.reason,
                withinDataRoot = diagnostic.withinDataRoot,
                candidateWithinDataRoot = diagnostic.candidateWithinDataRoot,
                nativeProbeResultCode = diagnostic.nativeProbeResultCode,
                nativeProbeErrno = diagnostic.nativeProbeErrno,
                nativeProbeCandidateExists = diagnostic.nativeProbeCandidateExists,
                nativeProbeResolvedPath = diagnostic.nativeProbeResolvedPath
            )
    }
}

object EngineStorageDiagnosticsFacade {
    private const val CALLER = "ContainerActivity.PR10_STORAGE_DIAGNOSTICS"
    private const val STAGE = "STORAGE_PATH_DIAGNOSTIC"

    private val javaProbeComponents = mapOf(
        "data-data" to "storage-java-data-data",
        "data-user" to "storage-java-data-user",
        "sdcard" to "storage-java-sdcard",
        "storage-emulated" to "storage-java-storage-emulated"
    )

    fun diagnosticsFromBootstrapResult(result: Any): EngineStorageDiagnosticsPlan {
        val loaderResult = EngineHostedBootstrapResult.unwrap(result) ?: throw IllegalArgumentException(
            "Expected HostedBootstrapResult, got ${result::class.java.name}"
        )
        return diagnosticsFromBootstrapResult(loaderResult)
    }

    fun diagnosticsFromBootstrapResult(result: HostedBootstrapResult): EngineStorageDiagnosticsPlan {
        val originPackageName = result.originPackageName.orEmpty()
        val virtualPackageName = result.virtualPackageName.orEmpty()
        val dataRoot = result.dataRoot.orEmpty()
        if (originPackageName.isBlank() || virtualPackageName.isBlank() || dataRoot.isBlank()) {
            return EngineStorageDiagnosticsPlan(
                instanceId = result.instanceId,
                bootstrapUnsupportedEntry = bootstrapUnsupportedEntry(
                    result = result,
                    originPackageName = originPackageName,
                    virtualPackageName = virtualPackageName,
                    dataRoot = dataRoot
                )
            )
        }

        val javaDiagnostics = VirtualStoragePathDiagnostics.javaAbsolutePathDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER
        ).map(EngineStoragePathDiagnostic::fromLoader)

        val nativeDiagnostics = nativeIoDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER,
            result = result
        )

        return EngineStorageDiagnosticsPlan(
            instanceId = result.instanceId,
            diagnostics = javaDiagnostics + nativeDiagnostics
        )
    }

    fun fieldsForDiagnostic(
        diagnostic: EngineStoragePathDiagnostic,
        isolationMarkerPath: String? = null,
        isolationMarkerContent: String? = null
    ): Map<String, Any?> = buildMap {
        put("stage", STAGE)
        put("instanceId", diagnostic.instanceId)
        put("originPackageName", diagnostic.originPackageName)
        put("virtualPackageName", diagnostic.virtualPackageName)
        put("dataRoot", diagnostic.dataRoot)
        put("storageDiagnosticKind", diagnostic.kind.name)
        put("storageDiagnosticStatus", diagnostic.status.name)
        put("originalPath", diagnostic.originalPath)
        put("redirectedPath", diagnostic.redirectedPath)
        put("caller", diagnostic.caller)
        put("withinDataRoot", diagnostic.withinDataRoot)
        diagnostic.probeName?.let { put("probeName", it) }
        diagnostic.operation?.let { put("nativeIoOperation", it) }
        diagnostic.reason?.let { put("reason", it) }
        diagnostic.candidateRedirectedPath?.let { put("candidateRedirectedPath", it) }
        diagnostic.candidateWithinDataRoot?.let { put("candidateWithinDataRoot", it) }
        if (diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO) {
            put("nativeIoDiagnosticStatus", diagnostic.status.name)
            diagnostic.nativeProbeResultCode?.let { put("nativeProbeResultCode", it) }
            diagnostic.nativeProbeErrno?.let { put("nativeProbeErrno", it) }
            diagnostic.nativeProbeCandidateExists?.let { put("nativeProbeCandidateExists", it) }
            diagnostic.nativeProbeResolvedPath?.let { put("nativeProbeResolvedPath", it) }
            putAll(nativeRuntimeVerdictFields(diagnostic))
        }
        if (!isolationMarkerPath.isNullOrBlank()) {
            put("isolationMarkerPath", isolationMarkerPath)
            put("isolationMarkerContent", isolationMarkerContent.orEmpty())
        }
    }

    fun shouldWriteIsolationMarker(diagnostic: EngineStoragePathDiagnostic): Boolean =
        diagnostic.kind == EngineStorageDiagnosticKind.JAVA_ABSOLUTE_PATH &&
            diagnostic.status == EngineStorageDiagnosticStatus.REDIRECTED &&
            diagnostic.withinDataRoot

    fun isolationMarkerContent(diagnostic: EngineStoragePathDiagnostic): String =
        "instanceId=${diagnostic.instanceId}\n" +
            "probeName=${diagnostic.probeName.orEmpty()}\n" +
            "originalPath=${diagnostic.originalPath}\n"

    fun componentName(diagnostic: EngineStoragePathDiagnostic): String {
        if (diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO) {
            return "storage-native-${diagnostic.operation.orEmpty()}"
        }
        return javaProbeComponents[diagnostic.probeName] ?: "storage-java-absolute-path"
    }

    private fun bootstrapUnsupportedEntry(
        result: HostedBootstrapResult,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String
    ): EngineStorageEvidenceEntry {
        val fields = linkedMapOf(
            "stage" to STAGE,
            "instanceId" to result.instanceId,
            "originPackageName" to originPackageName,
            "virtualPackageName" to virtualPackageName,
            "dataRoot" to dataRoot,
            "storageDiagnosticStatus" to EngineStorageDiagnosticStatus.UNSUPPORTED.name,
            "nativeIoRedirectVerdict" to "UNSUPPORTED",
            "nativeIoRedirectVerdictReason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "namespaceVerdict" to "UNKNOWN",
            "namespaceVerdictReason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "findLibraryVerdict" to "UNKNOWN",
            "findLibraryVerdictReason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "nativeLoadVerdict" to "UNKNOWN",
            "nativeLoadVerdictReason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "procMapsSpoofEnabled" to false,
            "procStatusSpoofEnabled" to false,
            "reason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "caller" to CALLER
        )
        return EngineStorageEvidenceEntry(
            instanceId = result.instanceId,
            component = "storage-bootstrap",
            operationName = "storage-bootstrap",
            fields = fields
        )
    }

    private fun nativeIoDiagnostics(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        caller: String,
        result: HostedBootstrapResult
    ): List<EngineStoragePathDiagnostic> {
        val bootstrapEvidence = result.stageResults
            .flatMap { it.evidence }
            .associate { it.key to it.value }
        val redirectVerdict = bootstrapEvidence["nativePrivatePathRedirectVerdict"]
        val unsupportedReason = nativeIoUnsupportedReason(result)
        val baseDiagnostics = VirtualStoragePathDiagnostics.nativeIoUnsupportedDiagnostics(
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = caller,
            reason = unsupportedReason
        ).map(EngineStoragePathDiagnostic::fromLoader)
        if (redirectVerdict != "PARTIAL") return baseDiagnostics

        val bridge = NativeHookBridge.getInstance()
        return baseDiagnostics.map { diagnostic ->
            val candidate = diagnostic.candidateRedirectedPath
            if (candidate.isNullOrBlank() || diagnostic.candidateWithinDataRoot != true) {
                diagnostic.copy(
                    status = EngineStorageDiagnosticStatus.UNSUPPORTED,
                    reason = "NATIVE_IO_CANDIDATE_OUTSIDE_DATA_ROOT"
                )
            } else {
                probeNativeIoDiagnostic(bridge, diagnostic, candidate)
            }
        }
    }

    private fun probeNativeIoDiagnostic(
        bridge: NativeHookBridge,
        diagnostic: EngineStoragePathDiagnostic,
        candidatePath: String
    ): EngineStoragePathDiagnostic {
        val operation = diagnostic.operation.orEmpty()
        val candidateFile = File(candidatePath)
        candidateFile.parentFile?.mkdirs()
        if (operation in setOf("stat", "access", "realpath")) {
            candidateFile.writeText("multiapp-native-probe-$operation")
        } else {
            candidateFile.delete()
        }
        val probe = bridge.probePrivatePathRedirect(
            operation = operation,
            originalPath = diagnostic.originalPath,
            expectedRedirectedPath = candidatePath
        )
        val status = if (probe.success) {
            EngineStorageDiagnosticStatus.REDIRECTED
        } else {
            EngineStorageDiagnosticStatus.UNCHANGED
        }
        val redirectedPath = if (probe.success) candidateFile.absolutePath else ""
        return diagnostic.copy(
            status = status,
            redirectedPath = redirectedPath,
            withinDataRoot = probe.success,
            reason = if (probe.success) null else probe.reason.ifBlank { "NATIVE_IO_PATH_NOT_REDIRECTED" },
            nativeProbeResultCode = probe.resultCode,
            nativeProbeErrno = probe.errno,
            nativeProbeCandidateExists = probe.candidateExists,
            nativeProbeResolvedPath = probe.resolvedPath
        )
    }

    private fun nativeRuntimeVerdictFields(diagnostic: EngineStoragePathDiagnostic): Map<String, Any?> {
        val nativeIoRedirectVerdict = when (diagnostic.status) {
            EngineStorageDiagnosticStatus.REDIRECTED -> "PASS"
            EngineStorageDiagnosticStatus.UNSUPPORTED -> "UNSUPPORTED"
            EngineStorageDiagnosticStatus.UNCHANGED -> "FAIL"
        }
        val nativeIoRedirectReason = when (diagnostic.status) {
            EngineStorageDiagnosticStatus.REDIRECTED -> ""
            EngineStorageDiagnosticStatus.UNSUPPORTED -> diagnostic.reason.orEmpty()
            EngineStorageDiagnosticStatus.UNCHANGED -> diagnostic.reason ?: "NATIVE_IO_PATH_NOT_REDIRECTED"
        }
        return linkedMapOf(
            "nativeIoRedirectVerdict" to nativeIoRedirectVerdict,
            "nativeIoRedirectVerdictReason" to nativeIoRedirectReason,
            "nativeRedirectScope" to "GUEST_PRIVATE_PATHS_ONLY",
            "nativeIoRedirectEnabled" to (diagnostic.status == EngineStorageDiagnosticStatus.REDIRECTED),
            "nativeIoCandidateWithinDataRoot" to (diagnostic.candidateWithinDataRoot ?: false),
            "namespaceVerdict" to "UNKNOWN",
            "namespaceVerdictReason" to "NAMESPACE_COLLECTOR_NOT_IMPLEMENTED",
            "findLibraryVerdict" to "UNKNOWN",
            "findLibraryVerdictReason" to "FIND_LIBRARY_COLLECTOR_NOT_IMPLEMENTED",
            "nativeLoadVerdict" to "UNKNOWN",
            "nativeLoadVerdictReason" to "NATIVE_LOAD_COLLECTOR_NOT_IMPLEMENTED",
            "procMapsSpoofEnabled" to false,
            "procStatusSpoofEnabled" to false
        )
    }

    private fun nativeIoUnsupportedReason(result: HostedBootstrapResult): String {
        val evidence = result.stageResults
            .flatMap { it.evidence }
            .associate { it.key to it.value }
        return when (evidence["nativePrivatePathRedirectVerdict"]) {
            "PARTIAL" -> "NATIVE_IO_DEVICE_PROBE_NOT_IMPLEMENTED"
            "FAIL" -> evidence["nativePrivatePathRedirectReason"] ?: "PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE"
            else -> "NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE"
        }
    }
}

package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStorageDiagnosticStatus
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import com.multiapp.core.loader.VirtualStoragePathDiagnostics
import com.multiapp.core.hook.NativeHookBridge
import java.io.File

/** Writes PR-10 storage redirect diagnostics for hosted-container instances. */
object ContainerStorageDiagnosticsEvidence {
    private const val TAG = "StorageDiagnostics"
    private const val CALLER = "ContainerActivity.PR10_STORAGE_DIAGNOSTICS"
    private const val STAGE = "STORAGE_PATH_DIAGNOSTIC"

    private val javaProbeComponents = mapOf(
        "data-data" to "storage-java-data-data",
        "data-user" to "storage-java-data-user",
        "sdcard" to "storage-java-sdcard",
        "storage-emulated" to "storage-java-storage-emulated"
    )

    fun write(context: Context, result: HostedBootstrapResult) {
        val originPackageName = result.originPackageName.orEmpty()
        val virtualPackageName = result.virtualPackageName.orEmpty()
        val dataRoot = result.dataRoot.orEmpty()
        if (originPackageName.isBlank() || virtualPackageName.isBlank() || dataRoot.isBlank()) {
            writeBootstrapUnsupported(context, result, originPackageName, virtualPackageName, dataRoot)
            return
        }

        VirtualStoragePathDiagnostics.javaAbsolutePathDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER
        ).forEach { diagnostic ->
            val marker = writeIsolationMarker(diagnostic)
            writeDiagnostic(context, diagnostic, marker)
        }

        nativeIoDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER,
            result = result
        ).forEach { diagnostic ->
            writeDiagnostic(context, diagnostic, null)
        }
    }

    internal fun fieldsForDiagnostic(
        diagnostic: VirtualStoragePathDiagnostic,
        isolationMarker: File? = null
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
        if (diagnostic.kind == VirtualStorageDiagnosticKind.NATIVE_IO) {
            put("nativeIoDiagnosticStatus", diagnostic.status.name)
            diagnostic.nativeProbeResultCode?.let { put("nativeProbeResultCode", it) }
            diagnostic.nativeProbeErrno?.let { put("nativeProbeErrno", it) }
            diagnostic.nativeProbeCandidateExists?.let { put("nativeProbeCandidateExists", it) }
            diagnostic.nativeProbeResolvedPath?.let { put("nativeProbeResolvedPath", it) }
            putAll(nativeRuntimeVerdictFields(diagnostic))
        }
        isolationMarker?.let { marker ->
            put("isolationMarkerPath", marker.absolutePath)
            put("isolationMarkerContent", marker.readText())
        }
    }

    private fun writeBootstrapUnsupported(
        context: Context,
        result: HostedBootstrapResult,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = result.instanceId,
                component = "storage-bootstrap",
                fields = linkedMapOf(
                    "stage" to STAGE,
                    "instanceId" to result.instanceId,
                    "originPackageName" to originPackageName,
                    "virtualPackageName" to virtualPackageName,
                    "dataRoot" to dataRoot,
                    "storageDiagnosticStatus" to VirtualStorageDiagnosticStatus.UNSUPPORTED.name,
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
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write PR-10 bootstrap storage evidence for instanceId=${result.instanceId}", error)
        }
    }

    private fun writeDiagnostic(
        context: Context,
        diagnostic: VirtualStoragePathDiagnostic,
        isolationMarker: File?
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = diagnostic.instanceId,
                component = componentName(diagnostic),
                fields = fieldsForDiagnostic(diagnostic, isolationMarker)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write PR-10 storage evidence for instanceId=${diagnostic.instanceId}", error)
        }
    }

    private fun writeIsolationMarker(diagnostic: VirtualStoragePathDiagnostic): File? {
        if (diagnostic.kind != VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH) return null
        if (diagnostic.status != VirtualStorageDiagnosticStatus.REDIRECTED) return null
        if (!diagnostic.withinDataRoot) return null
        return runCatching {
            File(diagnostic.redirectedPath).apply {
                parentFile?.mkdirs()
                writeText(
                    "instanceId=${diagnostic.instanceId}\n" +
                        "probeName=${diagnostic.probeName.orEmpty()}\n" +
                        "originalPath=${diagnostic.originalPath}\n"
                )
            }
        }.getOrNull()
    }

    private fun componentName(diagnostic: VirtualStoragePathDiagnostic): String {
        if (diagnostic.kind == VirtualStorageDiagnosticKind.NATIVE_IO) {
            return "storage-native-${diagnostic.operation.orEmpty()}"
        }
        return javaProbeComponents[diagnostic.probeName] ?: "storage-java-absolute-path"
    }

    private fun nativeIoDiagnostics(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        caller: String,
        result: HostedBootstrapResult
    ): List<VirtualStoragePathDiagnostic> {
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
        )
        if (redirectVerdict != "PARTIAL") return baseDiagnostics

        val bridge = NativeHookBridge.getInstance()
        return baseDiagnostics.map { diagnostic ->
            val candidate = diagnostic.candidateRedirectedPath
            if (candidate.isNullOrBlank() || diagnostic.candidateWithinDataRoot != true) {
                diagnostic.copy(
                    status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                    reason = "NATIVE_IO_CANDIDATE_OUTSIDE_DATA_ROOT"
                )
            } else {
                probeNativeIoDiagnostic(bridge, diagnostic, candidate)
            }
        }
    }

    private fun probeNativeIoDiagnostic(
        bridge: NativeHookBridge,
        diagnostic: VirtualStoragePathDiagnostic,
        candidatePath: String
    ): VirtualStoragePathDiagnostic {
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
            VirtualStorageDiagnosticStatus.REDIRECTED
        } else {
            VirtualStorageDiagnosticStatus.UNCHANGED
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

    private fun nativeRuntimeVerdictFields(diagnostic: VirtualStoragePathDiagnostic): Map<String, Any?> {
        val nativeIoRedirectVerdict = when (diagnostic.status) {
            VirtualStorageDiagnosticStatus.REDIRECTED -> "PASS"
            VirtualStorageDiagnosticStatus.UNSUPPORTED -> "UNSUPPORTED"
            VirtualStorageDiagnosticStatus.UNCHANGED -> "FAIL"
        }
        val nativeIoRedirectReason = when (diagnostic.status) {
            VirtualStorageDiagnosticStatus.REDIRECTED -> ""
            VirtualStorageDiagnosticStatus.UNSUPPORTED -> diagnostic.reason.orEmpty()
            VirtualStorageDiagnosticStatus.UNCHANGED -> diagnostic.reason ?: "NATIVE_IO_PATH_NOT_REDIRECTED"
        }
        return linkedMapOf(
            "nativeIoRedirectVerdict" to nativeIoRedirectVerdict,
            "nativeIoRedirectVerdictReason" to nativeIoRedirectReason,
            "nativeRedirectScope" to "GUEST_PRIVATE_PATHS_ONLY",
            "nativeIoRedirectEnabled" to (diagnostic.status == VirtualStorageDiagnosticStatus.REDIRECTED),
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

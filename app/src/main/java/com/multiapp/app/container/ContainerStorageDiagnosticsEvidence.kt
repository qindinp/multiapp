package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStorageDiagnosticStatus
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import com.multiapp.core.loader.VirtualStoragePathDiagnostics
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

        VirtualStoragePathDiagnostics.nativeIoUnsupportedDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER
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
}

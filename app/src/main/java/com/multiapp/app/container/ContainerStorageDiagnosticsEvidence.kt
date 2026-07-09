package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.engine.EngineStorageDiagnosticsFacade
import com.multiapp.core.engine.EngineStorageEvidenceEntry
import com.multiapp.core.engine.EngineStoragePathDiagnostic
import java.io.File

/** Writes PR-10 storage redirect diagnostics for hosted-container instances. */
object ContainerStorageDiagnosticsEvidence {
    private const val TAG = "StorageDiagnostics"

    fun write(context: Context, result: Any) {
        val plan = EngineStorageDiagnosticsFacade.diagnosticsFromBootstrapResult(result)
        plan.bootstrapUnsupportedEntry?.let { entry ->
            writeEntry(context, entry)
            ContainerEngineEvidenceBridge.recordNativeBootstrapUnsupported(
                instanceId = entry.instanceId,
                fields = entry.fields
            )
            return
        }

        plan.diagnostics.forEach { diagnostic ->
            writeDiagnostic(context, diagnostic)
        }
    }

    internal fun fieldsForDiagnostic(
        diagnostic: EngineStoragePathDiagnostic,
        isolationMarker: File? = null
    ): Map<String, Any?> = EngineStorageDiagnosticsFacade.fieldsForDiagnostic(
        diagnostic = diagnostic,
        isolationMarkerPath = isolationMarker?.absolutePath,
        isolationMarkerContent = isolationMarker?.readText()
    )

    private fun writeDiagnostic(context: Context, diagnostic: EngineStoragePathDiagnostic) {
        runCatching {
            val marker = writeIsolationMarker(diagnostic)
            val fields = fieldsForDiagnostic(diagnostic, marker)
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = diagnostic.instanceId,
                component = EngineStorageDiagnosticsFacade.componentName(diagnostic),
                fields = fields
            )
            ContainerEngineEvidenceBridge.recordNativeStorageDiagnostic(
                diagnostic = diagnostic,
                fields = fields
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write PR-10 storage evidence for instanceId=${diagnostic.instanceId}", error)
        }
    }

    private fun writeEntry(context: Context, entry: EngineStorageEvidenceEntry) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = entry.instanceId,
                component = entry.component,
                fields = entry.fields
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write PR-10 bootstrap storage evidence for instanceId=${entry.instanceId}", error)
        }
    }

    private fun writeIsolationMarker(diagnostic: EngineStoragePathDiagnostic): File? {
        if (!EngineStorageDiagnosticsFacade.shouldWriteIsolationMarker(diagnostic)) return null
        return runCatching {
            File(diagnostic.redirectedPath).apply {
                parentFile?.mkdirs()
                writeText(EngineStorageDiagnosticsFacade.isolationMarkerContent(diagnostic))
            }
        }.getOrNull()
    }
}

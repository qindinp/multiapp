package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.common.EvidenceSanitizer
import java.io.File

internal object HostedRuntimeProgressEvidenceWriter {
    private const val EVIDENCE_DIR = "hosted_launch_evidence"

    fun write(
        context: Context?,
        instanceId: String,
        component: String,
        fields: Map<String, String>
    ) {
        val filesDir = runCatching { context?.filesDir }.getOrNull() ?: return
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }.canonicalFile
            val outputFile = File(evidenceDir, "$instanceId.$component.properties").canonicalFile
            require(outputFile.parentFile == evidenceDir) {
                "Runtime progress evidence path escapes evidence dir"
            }
            outputFile.writeText(
                fields.entries.joinToString("\n") { (key, value) ->
                    "${EvidenceSanitizer.sanitizeEvidenceLine(key)}=${EvidenceSanitizer.sanitizeEvidenceLine(value)}"
                }
            )
        }
    }
}

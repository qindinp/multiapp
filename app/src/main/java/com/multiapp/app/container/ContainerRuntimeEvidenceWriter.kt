package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.common.EvidenceSanitizer
import java.io.File

/** Writes small component-scoped evidence files for hosted runtime diagnostics. */
object ContainerRuntimeEvidenceWriter {
    fun write(
        context: Context,
        instanceId: String,
        component: String,
        fields: Map<String, Any?>
    ): File = write(context.filesDir, instanceId, component, fields)

    fun write(
        filesDir: File,
        instanceId: String,
        component: String,
        fields: Map<String, Any?>
    ): File {
        val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(filesDir, instanceId, component)
        file.writeText(toLines(fields).joinToString("\n"))
        return file
    }

    fun toLines(fields: Map<String, Any?>): List<String> = fields.map { (key, value) ->
        "$key=${EvidenceSanitizer.sanitizeEvidenceValue(key, value)}"
    }
}

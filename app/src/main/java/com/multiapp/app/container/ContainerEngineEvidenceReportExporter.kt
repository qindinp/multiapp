package com.multiapp.app.container

import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import java.io.File

/** Exports the engine-owned evidence report into run-as readable hosted evidence files. */
internal object ContainerEngineEvidenceReportExporter {
    private const val COMPONENT = "engine-report"
    private const val OPERATION_PREFIX = "operationEvidence"
    private val unsafeKeyCharacters = Regex("[^A-Za-z0-9._-]")

    fun write(report: EngineEvidenceReport): File? {
        val filesDir = filesDirForReport(report) ?: return null
        return write(filesDir, report)
    }

    internal fun write(filesDir: File, report: EngineEvidenceReport): File {
        return ContainerRuntimeEvidenceWriter.write(
            filesDir = filesDir,
            instanceId = report.instanceId,
            component = COMPONENT,
            fields = fieldsForReport(report)
        )
    }

    internal fun fieldsForReport(report: EngineEvidenceReport): Map<String, Any?> = linkedMapOf<String, Any?>(
        "component" to COMPONENT,
        "instanceId" to report.instanceId,
        "evidenceSessionId" to report.evidenceSessionId,
        "status" to report.status.name,
        "profile" to report.profile.name
    ).apply {
        report.entries.forEach { (key, value) ->
            put(keySegment(key, "entry"), EvidenceSanitizer.sanitizeEvidenceEntry(key, value))
        }
        put("${OPERATION_PREFIX}.count", report.operationEvidence.values.sumOf { operations ->
            operations.values.sumOf { evidence -> evidence.size }
        })
        report.operationEvidence.toSortedMap().forEach { (component, operations) ->
            operations.toSortedMap().forEach { (operation, evidenceEntries) ->
                val groupPrefix = "$OPERATION_PREFIX.${keySegment(component, "component")}." +
                    keySegment(operation, "operation")
                put("$groupPrefix.count", evidenceEntries.size)
            }
        }
        val groupIndexes = mutableMapOf<String, Int>()
        report.flattenedOperationEvidence().forEachIndexed { index, evidence ->
            val groupKey = "${keySegment(evidence.component, "component")}.${keySegment(evidence.operation, "operation")}"
            val operationIndex = groupIndexes.getOrDefault(groupKey, 0)
            groupIndexes[groupKey] = operationIndex + 1
            putIndexedEvidence(index, operationIndex, evidence)
        }
    }

    private fun MutableMap<String, Any?>.putIndexedEvidence(
        index: Int,
        operationIndex: Int,
        evidence: EngineOperationEvidence
    ) {
        val component = keySegment(evidence.component, "component")
        val operation = keySegment(evidence.operation, "operation")
        val indexedPrefix = "$OPERATION_PREFIX.$index"
        val groupedPrefix = "$OPERATION_PREFIX.$component.$operation.$operationIndex"

        put("$indexedPrefix.component", evidence.component.toEvidenceLabel("component"))
        put("$indexedPrefix.operation", evidence.operation.toEvidenceLabel("operation"))
        put("$indexedPrefix.verdict", evidence.verdict.name)
        put("$groupedPrefix.verdict", evidence.verdict.name)
        evidence.entries.forEach { (key, value) ->
            val entryKey = keySegment(key, "entry")
            val sanitizedValue = EvidenceSanitizer.sanitizeEvidenceEntry(key, value)
            put("$indexedPrefix.entry.$entryKey", sanitizedValue)
            put("$groupedPrefix.entry.$entryKey", sanitizedValue)
        }
    }

    private fun filesDirForReport(report: EngineEvidenceReport): File? {
        val dataRoot = report.entries["dataRoot"]?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (!dataRoot.isDirectory) return null
        val instanceDataRoot = dataRoot.parentFile?.takeIf {
            it.name == ContainerRuntimePaths.INSTANCE_DATA_DIR
        } ?: return null
        return instanceDataRoot.parentFile
    }

    private fun keySegment(value: String, defaultValue: String): String {
        val sanitized = value.toEvidenceLabel(defaultValue)
            .replace(unsafeKeyCharacters, "_")
            .trim('.', '_', '-')
        return sanitized.takeIf { it.isNotBlank() } ?: defaultValue
    }

    private fun String.toEvidenceLabel(defaultValue: String): String =
        EvidenceSanitizer.sanitizeEvidenceLabel(this, defaultValue)
}

package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.model.engine.EngineCapability
import com.multiapp.core.model.engine.EngineCapabilityReport
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

internal fun EngineCapabilityReport.toEngineCapabilityReportBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putInt(KEY_SCHEMA_VERSION, CAPABILITY_REPORT_SCHEMA_VERSION)
    putString(KEY_INSTANCE_ID, instanceId.orEmpty())
    putString(KEY_STATUS, status.name)
    putLong(KEY_GENERATED_AT_MS, generatedAtMs)
    putString(KEY_MESSAGE, message)
    val orderedCapabilities = capabilities.sortedBy(EngineCapability::id)
    putInt(KEY_CAPABILITY_COUNT, orderedCapabilities.size)
    orderedCapabilities.forEachIndexed { index, capability ->
        putCapability(index, capability)
    }
}

internal fun Bundle.toEngineCapabilityReportOrNull(): EngineCapabilityReport? = runCatching {
    val capabilityCount = requireNotNull(strictInt(KEY_CAPABILITY_COUNT))
    check(capabilityCount in 0..MAX_CAPABILITY_COUNT) { "capability count exceeds budget" }
    check(keySet() == reportFields(capabilityCount)) { "unexpected capability report fields" }
    check(strictInt(KEY_SCHEMA_VERSION) == CAPABILITY_REPORT_SCHEMA_VERSION) {
        "unsupported capability report schema"
    }
    val instanceIdText = requireNotNull(strictString(KEY_INSTANCE_ID))
        .checkedText(MAX_IDENTITY_LENGTH, allowEmpty = true)
    val capabilities = (0 until capabilityCount).map { index ->
        toEngineCapability(index)
    }
    val report = EngineCapabilityReport(
        instanceId = instanceIdText.takeIf(String::isNotEmpty),
        status = requiredEnum(KEY_STATUS),
        capabilities = capabilities,
        generatedAtMs = requireNotNull(strictLong(KEY_GENERATED_AT_MS)),
        message = requireNotNull(strictString(KEY_MESSAGE)).checkedText(MAX_MESSAGE_LENGTH, allowEmpty = true)
    )
    check(report.totalTextLength() <= MAX_REPORT_TEXT_LENGTH) { "capability report exceeds text budget" }
    report
}.getOrNull()

internal fun engineCapabilityFailureBundle(
    instanceId: String?,
    message: String,
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = EngineCapabilityReport(
    instanceId = instanceId,
    status = EngineResultStatus.FAIL,
    capabilities = emptyList(),
    generatedAtMs = 0L,
    message = message
).toEngineCapabilityReportBundle(bundleFactory)

private fun Bundle.putCapability(index: Int, capability: EngineCapability) {
    putString(capabilityField(index, KEY_CAPABILITY_ID), capability.id)
    putString(capabilityField(index, KEY_SUBSYSTEM), capability.subsystem.name)
    putString(capabilityField(index, KEY_STATUS), capability.status.name)
    putBoolean(capabilityField(index, KEY_RELEASE_CRITICAL), capability.releaseCritical)
    putStringArrayList(
        capabilityField(index, KEY_SUPPORTED_OPERATIONS),
        ArrayList(capability.supportedOperations.sorted())
    )
    putStringArrayList(
        capabilityField(index, KEY_UNSUPPORTED_OPERATIONS),
        ArrayList(capability.unsupportedOperations.sorted())
    )
    putStringArrayList(
        capabilityField(index, KEY_REQUIRED_DEVICE_EVIDENCE),
        ArrayList(capability.requiredDeviceEvidence.sorted())
    )
    putString(capabilityField(index, KEY_MESSAGE), capability.message)
}

private fun Bundle.toEngineCapability(index: Int): EngineCapability = EngineCapability(
    id = requireNotNull(strictString(capabilityField(index, KEY_CAPABILITY_ID)))
        .checkedText(MAX_IDENTITY_LENGTH),
    subsystem = requiredEnum(capabilityField(index, KEY_SUBSYSTEM)),
    status = requiredEnum(capabilityField(index, KEY_STATUS)),
    releaseCritical = requireNotNull(strictBoolean(capabilityField(index, KEY_RELEASE_CRITICAL))),
    supportedOperations = requiredStringSet(capabilityField(index, KEY_SUPPORTED_OPERATIONS)),
    unsupportedOperations = requiredStringSet(capabilityField(index, KEY_UNSUPPORTED_OPERATIONS)),
    requiredDeviceEvidence = requiredStringSet(capabilityField(index, KEY_REQUIRED_DEVICE_EVIDENCE)),
    message = requireNotNull(strictString(capabilityField(index, KEY_MESSAGE)))
        .checkedText(MAX_MESSAGE_LENGTH, allowEmpty = true)
)

private fun Bundle.requiredStringSet(key: String): Set<String> {
    val values = requireNotNull(strictStringArrayList(key))
    check(values.size <= MAX_OPERATION_COUNT) { "$key exceeds entry budget" }
    check(values.distinct().size == values.size) { "$key contains duplicates" }
    values.forEach { value -> value.checkedText(MAX_IDENTITY_LENGTH) }
    return values.toSet()
}

private inline fun <reified T : Enum<T>> Bundle.requiredEnum(key: String): T =
    requireNotNull(strictString(key))
        .checkedText(MAX_ENUM_LENGTH)
        .let { value -> enumValueOf<T>(value) }

private fun String.checkedText(maxLength: Int, allowEmpty: Boolean = false): String = also { value ->
    check(value.length <= maxLength) { "text exceeds budget" }
    check(allowEmpty || value.isNotEmpty()) { "text must not be empty" }
    check(value.none(Char::isISOControl)) { "text contains control characters" }
}

private fun EngineCapabilityReport.totalTextLength(): Int =
    instanceId.orEmpty().length + message.length + capabilities.sumOf { capability ->
        capability.id.length + capability.message.length +
            capability.supportedOperations.sumOf(String::length) +
            capability.unsupportedOperations.sumOf(String::length) +
            capability.requiredDeviceEvidence.sumOf(String::length)
    }

private fun reportFields(capabilityCount: Int): Set<String> = buildSet {
    add(KEY_SCHEMA_VERSION)
    add(KEY_INSTANCE_ID)
    add(KEY_STATUS)
    add(KEY_GENERATED_AT_MS)
    add(KEY_MESSAGE)
    add(KEY_CAPABILITY_COUNT)
    repeat(capabilityCount) { index ->
        CAPABILITY_FIELD_SUFFIXES.forEach { suffix -> add(capabilityField(index, suffix)) }
    }
}

private fun capabilityField(index: Int, suffix: String): String = "capability.$index.$suffix"

@Suppress("DEPRECATION")
private fun Bundle.strictString(key: String): String? =
    if (!containsKey(key)) null else runCatching { get(key) as? String }.getOrNull()

@Suppress("DEPRECATION")
private fun Bundle.strictInt(key: String): Int? =
    if (!containsKey(key)) null else runCatching { get(key) as? Int }.getOrNull()

@Suppress("DEPRECATION")
private fun Bundle.strictLong(key: String): Long? =
    if (!containsKey(key)) null else runCatching { get(key) as? Long }.getOrNull()

@Suppress("DEPRECATION")
private fun Bundle.strictBoolean(key: String): Boolean? =
    if (!containsKey(key)) null else runCatching { get(key) as? Boolean }.getOrNull()

@Suppress("DEPRECATION", "UNCHECKED_CAST")
private fun Bundle.strictStringArrayList(key: String): ArrayList<String>? {
    if (!containsKey(key)) return null
    val values = runCatching { get(key) as? ArrayList<*> }.getOrNull() ?: return null
    if (values.any { it !is String }) return null
    return values as ArrayList<String>
}

private val CAPABILITY_FIELD_SUFFIXES = setOf(
    KEY_CAPABILITY_ID,
    KEY_SUBSYSTEM,
    KEY_STATUS,
    KEY_RELEASE_CRITICAL,
    KEY_SUPPORTED_OPERATIONS,
    KEY_UNSUPPORTED_OPERATIONS,
    KEY_REQUIRED_DEVICE_EVIDENCE,
    KEY_MESSAGE
)

private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_INSTANCE_ID = "instanceId"
private const val KEY_STATUS = "status"
private const val KEY_GENERATED_AT_MS = "generatedAtMs"
private const val KEY_MESSAGE = "message"
private const val KEY_CAPABILITY_COUNT = "capabilityCount"
private const val KEY_CAPABILITY_ID = "capabilityId"
private const val KEY_SUBSYSTEM = "subsystem"
private const val KEY_RELEASE_CRITICAL = "releaseCritical"
private const val KEY_SUPPORTED_OPERATIONS = "supportedOperations"
private const val KEY_UNSUPPORTED_OPERATIONS = "unsupportedOperations"
private const val KEY_REQUIRED_DEVICE_EVIDENCE = "requiredDeviceEvidence"
private const val CAPABILITY_REPORT_SCHEMA_VERSION = 1
private const val MAX_CAPABILITY_COUNT = 128
private const val MAX_OPERATION_COUNT = 512
private const val MAX_IDENTITY_LENGTH = 1_024
private const val MAX_ENUM_LENGTH = 64
private const val MAX_MESSAGE_LENGTH = 8_192
private const val MAX_REPORT_TEXT_LENGTH = 262_144

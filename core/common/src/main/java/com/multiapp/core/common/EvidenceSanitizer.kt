package com.multiapp.core.common

import java.net.URI

/** Shared evidence-value and evidence-file safety helpers. */
object EvidenceSanitizer {
    private const val MAX_EVIDENCE_LINE_LENGTH = 4096
    private const val REDACTED = "<redacted>"
    private val SAFE_EVIDENCE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val URI_VALUE_PATTERN = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")
    private val SENSITIVE_VALUE_PATTERN = Regex(
        "(?i)(multiapp_routeToken|routeToken|route_token|password|secret|credential)\\s*(=|:|%3[dD])"
    )
    private val URI_FIELD_KEYS = setOf(
        "uri",
        "datauri",
        "intentdata",
        "intentdatauri",
        "pendingdatauri"
    )

    fun sanitizeEvidenceValue(key: String, value: Any?): String {
        val rawValue = value?.toString().orEmpty()
        val redactedValue = if (isUriField(key)) redactUriForEvidence(rawValue) else rawValue
        return sanitizeEvidenceLine(redactedValue)
    }

    fun sanitizeEvidenceEntries(fields: Map<String, Any?>): Map<String, String> {
        return fields
            .filterKeys { it.isNotBlank() }
            .mapValues { (key, value) -> sanitizeEvidenceEntry(key, value) }
    }

    fun sanitizeEvidenceEntry(key: String, value: Any?): String {
        if (isSensitiveEvidenceKey(key)) return REDACTED
        val rawValue = value?.toString().orEmpty()
        val redactedEmbeddedUris = URI_VALUE_PATTERN.replace(rawValue) { match ->
            redactUriForEvidence(match.value)
        }
        if (containsSensitiveAssignment(redactedEmbeddedUris)) return REDACTED
        return sanitizeEvidenceValue(key, redactedEmbeddedUris)
    }

    fun sanitizeEvidenceLabel(value: String, defaultValue: String): String {
        val normalized = value
            .substringBefore(':')
            .trim()
            .takeIf { it.isNotBlank() }
            ?: defaultValue
        val sanitized = sanitizeEvidenceLine(normalized)
        return sanitized.takeIf { it.isNotBlank() } ?: defaultValue
    }

    fun sanitizeEvidenceLine(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\t', ' ')
        .take(MAX_EVIDENCE_LINE_LENGTH)

    fun redactUriForEvidence(value: String): String {
        if (value.isBlank()) return value
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return redactUriByDelimiter(value)
        val scheme = uri.scheme ?: return redactUriByDelimiter(value)
        if (uri.isOpaque) return "$scheme:$REDACTED"
        return buildString {
            append(scheme)
            append("://")
            val authority = sanitizedAuthority(uri)
            if (authority.isNullOrBlank()) {
                append('/')
            } else {
                append(authority)
                append('/')
            }
            append(REDACTED)
        }
    }

    fun safeEvidenceSegment(value: String, label: String): String {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(value == value.trim()) { "unsafe $label for evidence path" }
        require(value != "." && value != ".." && ".." !in value) { "unsafe $label for evidence path" }
        require(SAFE_EVIDENCE_SEGMENT.matches(value)) { "unsafe $label for evidence path" }
        return value
    }

    private fun isUriField(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized in URI_FIELD_KEYS || normalized.endsWith("uri")
    }

    private fun isSensitiveEvidenceKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("token") ||
            normalized.contains("password") ||
            normalized.contains("secret") ||
            normalized.contains("credential")
    }

    private fun containsSensitiveAssignment(value: String): Boolean =
        SENSITIVE_VALUE_PATTERN.containsMatchIn(value)

    private fun sanitizedAuthority(uri: URI): String? {
        val host = uri.host
        if (!host.isNullOrBlank()) {
            return if (uri.port >= 0) "$host:${uri.port}" else host
        }
        return uri.rawAuthority
            ?.substringAfterLast('@')
            ?.takeIf { it.isNotBlank() }
    }

    private fun redactUriByDelimiter(value: String): String {
        val sensitiveStart = listOf('?', '#', ';')
            .map { marker -> value.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return value
        return value.substring(0, sensitiveStart) + REDACTED
    }
}

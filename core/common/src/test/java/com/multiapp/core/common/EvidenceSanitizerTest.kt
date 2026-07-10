package com.multiapp.core.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EvidenceSanitizerTest {

    @Test
    fun `sanitize evidence entries redacts sensitive keys and embedded uris`() {
        val entries = EvidenceSanitizer.sanitizeEvidenceEntries(
            mapOf(
                "uri" to "content://com.multiapp.app.stub/items?multiapp_routeToken=secret-token",
                "callback" to "dispatch content://com.multiapp.app.stub/items?multiapp_routeToken=secret-token",
                "routeToken" to "secret-token",
                "password" to "plain-password",
                "providerSecret" to "plain-secret",
                "detail" to "credential=plain-credential",
                "safe" to "ok"
            )
        )

        assertEquals("content://com.multiapp.app.stub/<redacted>", entries["uri"])
        assertEquals("dispatch content://com.multiapp.app.stub/<redacted>", entries["callback"])
        assertEquals("<redacted>", entries["routeToken"])
        assertEquals("<redacted>", entries["password"])
        assertEquals("<redacted>", entries["providerSecret"])
        assertEquals("<redacted>", entries["detail"])
        assertEquals("ok", entries["safe"])
        assertFalse(entries.values.any { value ->
            listOf("secret-token", "plain-password", "plain-secret", "plain-credential").any(value::contains)
        })
    }

    @Test
    fun `sanitize evidence value redacts activity token keys without hiding token status text`() {
        val rawToken = "raw-activity-token-super-secret"

        assertEquals("<redacted>", EvidenceSanitizer.sanitizeEvidenceValue("token", rawToken))
        assertEquals("<redacted>", EvidenceSanitizer.sanitizeEvidenceValue("sourceToken", rawToken))
        assertEquals("<redacted>", EvidenceSanitizer.sanitizeEvidenceValue("multiapp.virtualActivityToken", rawToken))
        assertEquals("<redacted>", EvidenceSanitizer.sanitizeEvidenceValue("detail", "token=$rawToken"))
        assertEquals("<redacted>", EvidenceSanitizer.sanitizeEvidenceValue("detail", "activityToken%3D$rawToken"))
        assertEquals("EXPIRED", EvidenceSanitizer.sanitizeEvidenceValue("routeTokenStatus", "EXPIRED"))
        assertEquals("MISSING_TOKEN", EvidenceSanitizer.sanitizeEvidenceValue("tokenValidationStatus", "MISSING_TOKEN"))
        assertEquals("<redacted>", EvidenceSanitizer.redactTokenForEvidence(rawToken))
        assertEquals(
            "INVALID_PROXY_URI:invalid route token:EXPIRED",
            EvidenceSanitizer.sanitizeEvidenceValue("detail", "INVALID_PROXY_URI:invalid route token:EXPIRED")
        )
    }

    @Test
    fun `sanitize evidence label drops sensitive suffix`() {
        assertEquals("query", EvidenceSanitizer.sanitizeEvidenceLabel("query:password=secret", "unknown"))
        assertEquals("unknown", EvidenceSanitizer.sanitizeEvidenceLabel("  ", "unknown"))
    }
}

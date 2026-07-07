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
    fun `sanitize evidence label drops sensitive suffix`() {
        assertEquals("query", EvidenceSanitizer.sanitizeEvidenceLabel("query:password=secret", "unknown"))
        assertEquals("unknown", EvidenceSanitizer.sanitizeEvidenceLabel("  ", "unknown"))
    }
}

package com.multiapp.app.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderProxyUriTest {

    @Test
    fun `rewrite encoded query removes only MultiApp proxy parameters`() {
        val rewritten = ProviderProxyUri.rewriteEncodedQuery(
            "multiapp_instanceId=inst-001" +
                "&bookId=123" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&token=a%3Db" +
                "&bookId=456" +
                "&flag"
        )

        assertEquals("bookId=123&token=a%3Db&bookId=456&flag", rewritten)
    }

    @Test
    fun `rewrite encoded query returns null when only proxy parameters remain`() {
        val rewritten = ProviderProxyUri.rewriteEncodedQuery(
            "multiapp_instanceId=inst-001&multiapp_guestAuthority=com.test.minimal.probe"
        )

        assertNull(rewritten)
    }

    @Test
    fun `rewrite encoded query preserves blank and value-only guest parameters`() {
        val rewritten = ProviderProxyUri.rewriteEncodedQuery(
            "empty=&=value&multiapp_instanceId=inst-001&plain"
        )

        assertEquals("empty=&=value&plain", rewritten)
    }
}

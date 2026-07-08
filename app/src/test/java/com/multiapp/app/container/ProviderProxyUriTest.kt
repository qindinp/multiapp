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
                "&multiapp_processSlot=com.multiapp.app%3Av3" +
                "&multiapp_routeToken=route-token-001" +
                "&token=a%3Db" +
                "&bookId=456" +
                "&flag"
        )

        assertEquals("bookId=123&token=a%3Db&bookId=456&flag", rewritten)
    }

    @Test
    fun `rewrite encoded query returns null when only proxy parameters remain`() {
        val rewritten = ProviderProxyUri.rewriteEncodedQuery(
            "multiapp_instanceId=inst-001" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&multiapp_routeToken=route-token-001"
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

    @Test
    fun `rewrite encoded query removes only exact proxy parameter names`() {
        val rewritten = ProviderProxyUri.rewriteEncodedQuery(
            "multiapp_instanceIdExtra=keep" +
                "&multiapp_instanceId" +
                "&multiapp_guestAuthority=" +
                "&multiapp_routeToken" +
                "&multiapp_guestAuthorityExtra=keep" +
                "&multiapp_routeTokenExtra=keep" +
                "&token=a%26b"
        )

        assertEquals(
            "multiapp_instanceIdExtra=keep&multiapp_guestAuthorityExtra=keep&multiapp_routeTokenExtra=keep&token=a%26b",
            rewritten
        )
    }
}

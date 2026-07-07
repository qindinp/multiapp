package com.multiapp.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentProviderHookUriRewriteTest {

    @Test
    fun `provider hook query rewrite appends stub routing parameters`() {
        val rewritten = ContentProviderHook.rewriteEncodedQueryForProviderHook(
            encodedQuery = "bookId=123&token=a%3Db&bookId=456&flag",
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe"
        )

        assertEquals(
            "bookId=123&token=a%3Db&bookId=456&flag" +
                "&multiapp_instanceId=inst-001" +
                "&multiapp_guestAuthority=com.test.minimal.probe",
            rewritten
        )
    }

    @Test
    fun `provider hook query rewrite removes stale proxy parameters before appending current route`() {
        val rewritten = ContentProviderHook.rewriteEncodedQueryForProviderHook(
            encodedQuery = "multiapp_instanceId=old" +
                "&multiapp_guestAuthority=old.authority" +
                "&multiapp_instanceIdExtra=keep" +
                "&multiapp_guestAuthorityExtra=keep" +
                "&token=a%26b",
            instanceId = "inst-002",
            guestAuthority = "com.test.minimal.probe"
        )

        assertEquals(
            "multiapp_instanceIdExtra=keep" +
                "&multiapp_guestAuthorityExtra=keep" +
                "&token=a%26b" +
                "&multiapp_instanceId=inst-002" +
                "&multiapp_guestAuthority=com.test.minimal.probe",
            rewritten
        )
    }

    @Test
    fun `provider hook query rewrite appends route when original query is empty`() {
        val rewritten = ContentProviderHook.rewriteEncodedQueryForProviderHook(
            encodedQuery = null,
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe"
        )

        assertEquals("multiapp_instanceId=inst-001&multiapp_guestAuthority=com.test.minimal.probe", rewritten)
    }

    @Test
    fun `provider hook query rewrite preserves only non proxy parameters`() {
        val rewritten = ContentProviderHook.rewriteEncodedQueryWithoutProxyParameters(
            "multiapp_instanceId=inst-001" +
                "&bookId=123" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&token=a%3Db" +
                "&bookId=456" +
                "&flag"
        )

        assertEquals("bookId=123&token=a%3Db&bookId=456&flag", rewritten)
    }
}

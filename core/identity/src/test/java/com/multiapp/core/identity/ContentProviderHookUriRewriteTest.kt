package com.multiapp.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach

class ContentProviderHookUriRewriteTest {

    @BeforeEach
    fun clearRouteTokens() {
        ProviderRouteTokenRegistry.clearForTest()
    }

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
                "&multiapp_processSlot=old.slot" +
                "&multiapp_routeToken=old-token" +
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
    fun `provider hook query rewrite appends remembered process slot`() {
        ProviderRouteTokenRegistry.rememberProcessSlot("inst-slot-001", "com.multiapp.app:v3")

        val rewritten = ContentProviderHook.rewriteEncodedQueryForProviderHook(
            encodedQuery = "bookId=123",
            instanceId = "inst-slot-001",
            guestAuthority = "com.test.minimal.probe",
            routeToken = "route-token-001"
        )

        assertEquals(
            "bookId=123" +
                "&multiapp_instanceId=inst-slot-001" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&multiapp_processSlot=com.multiapp.app:v3" +
                "&multiapp_routeToken=route-token-001",
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
    fun `provider hook query rewrite appends route token when issued`() {
        val rewritten = ContentProviderHook.rewriteEncodedQueryForProviderHook(
            encodedQuery = "bookId=123&multiapp_routeToken=stale",
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            routeToken = "route-token-001"
        )

        assertEquals(
            "bookId=123" +
                "&multiapp_instanceId=inst-001" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&multiapp_routeToken=route-token-001",
            rewritten
        )
    }

    @Test
    fun `provider hook query rewrite preserves only non proxy parameters`() {
        val rewritten = ContentProviderHook.rewriteEncodedQueryWithoutProxyParameters(
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
    fun `route token registry issues unguessable tokens bound to route fields`() {
        ProviderRouteTokenRegistry.clearForTest()

        val first = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query",
            nowMillis = 100L,
            ttlMillis = 50L
        )
        val second = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        assertTrue(first.token.length >= 40)
        assertNotEquals(first.token, second.token)
        assertEquals(
            ProviderRouteTokenValidationStatus.VALID,
            ProviderRouteTokenRegistry.validate(
                token = first.token,
                callerInstanceId = "inst-001",
                targetInstanceId = "inst-001",
                authority = "com.test.minimal.probe",
                operation = "query",
                nowMillis = 120L
            ).status
        )
        assertEquals(
            ProviderRouteTokenValidationStatus.OPERATION_MISMATCH,
            ProviderRouteTokenRegistry.validate(
                token = first.token,
                callerInstanceId = "inst-001",
                targetInstanceId = "inst-001",
                authority = "com.test.minimal.probe",
                operation = "insert",
                nowMillis = 120L
            ).status
        )
        assertEquals(
            ProviderRouteTokenValidationStatus.AUTHORITY_MISMATCH,
            ProviderRouteTokenRegistry.validate(
                token = first.token,
                callerInstanceId = "inst-001",
                targetInstanceId = "inst-001",
                authority = "other.authority",
                operation = "query",
                nowMillis = 120L
            ).status
        )
        assertEquals(
            ProviderRouteTokenValidationStatus.EXPIRED,
            ProviderRouteTokenRegistry.validate(
                token = first.token,
                callerInstanceId = "inst-001",
                targetInstanceId = "inst-001",
                authority = "com.test.minimal.probe",
                operation = "query",
                nowMillis = 151L
            ).status
        )
    }

    @Test
    fun `route token registry normalizes file descriptor resolver operation to provider open file`() {
        ProviderRouteTokenRegistry.clearForTest()
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "openFileDescriptor",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        assertEquals(
            ProviderRouteTokenValidationStatus.VALID,
            ProviderRouteTokenRegistry.validate(
                token = route.token,
                callerInstanceId = "inst-001",
                targetInstanceId = "inst-001",
                authority = "com.test.minimal.probe",
                operation = "openFile",
                nowMillis = 120L
            ).status
        )
    }

    @Test
    fun `target validation preserves token caller for cross instance authorization`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-caller",
            targetInstanceId = "inst-target",
            authority = "com.test.minimal.probe",
            operation = "query",
            processSlot = "com.multiapp.app:v2",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        val valid = ProviderRouteTokenRegistry.validateTarget(
            token = route.token,
            targetInstanceId = "inst-target",
            authority = "com.test.minimal.probe",
            operation = "query",
            expectedProcessSlot = "com.multiapp.app:v2",
            nowMillis = 120L
        )
        val forgedTarget = ProviderRouteTokenRegistry.validateTarget(
            token = route.token,
            targetInstanceId = "inst-other",
            authority = "com.test.minimal.probe",
            operation = "query",
            expectedProcessSlot = "com.multiapp.app:v2",
            nowMillis = 120L
        )

        assertEquals(ProviderRouteTokenValidationStatus.VALID, valid.status)
        assertEquals("inst-caller", valid.route?.callerInstanceId)
        assertEquals(ProviderRouteTokenValidationStatus.TARGET_INSTANCE_MISMATCH, forgedTarget.status)
    }

    @Test
    fun `observer routes are stable and omit expiring provider token`() {
        val register = ContentProviderHook.rewriteEncodedQueryForProviderHook(
            encodedQuery = "id=7&multiapp_routeToken=stale",
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            routeToken = null
        )

        assertEquals(false, ContentProviderHook.routeTokenRequiredForOperation("registerContentObserver"))
        assertEquals(false, ContentProviderHook.routeTokenRequiredForOperation("notifyChange"))
        assertEquals(true, ContentProviderHook.routeTokenRequiredForOperation("query"))
        assertTrue("multiapp_routeToken" !in register)
        assertTrue("multiapp_instanceId=inst-001" in register)
    }
}

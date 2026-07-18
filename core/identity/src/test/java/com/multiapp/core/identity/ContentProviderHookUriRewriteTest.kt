package com.multiapp.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class ContentProviderHookUriRewriteTest {

    @BeforeEach
    fun clearRouteTokens() {
        ProviderRouteTokenRegistry.clearForTest()
    }

    @AfterEach
    fun releaseRouteTokenIssuer() {
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
    fun `authoritative issuer receives normalized route and does not populate local registry`() {
        val staleLocalRoute = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-stale",
            targetInstanceId = "inst-stale",
            authority = "com.test.minimal.probe",
            operation = "query",
            nowMillis = 100L,
            ttlMillis = 50L
        )
        var issueCount = 0
        var issuedCaller: String? = null
        var issuedTarget: String? = null
        var issuedAuthority: String? = null
        var issuedOperation: String? = null
        var issuedProcessSlot: String? = null
        val remoteRoute = ProviderRouteToken(
            token = "remote-" + "a".repeat(40),
            callerInstanceId = "inst-caller",
            targetInstanceId = "inst-target",
            authority = "com.test.minimal.probe",
            operation = "openFile",
            expiresAtMillis = 150L,
            processSlot = "com.multiapp.app:v2"
        )
        ProviderRouteTokenRegistry.installAuthoritativeIssuer(
            ProviderRouteTokenIssuer { caller, target, authority, operation, processSlot ->
                issueCount += 1
                issuedCaller = caller
                issuedTarget = target
                issuedAuthority = authority
                issuedOperation = operation
                issuedProcessSlot = processSlot
                remoteRoute
            }
        )

        val issued = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-caller",
            targetInstanceId = "inst-target",
            authority = "com.test.minimal.probe",
            operation = "openFileDescriptor:rw",
            processSlot = "com.multiapp.app:v2",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        assertEquals(1, issueCount)
        assertEquals("inst-caller", issuedCaller)
        assertEquals("inst-target", issuedTarget)
        assertEquals("com.test.minimal.probe", issuedAuthority)
        assertEquals("openFile", issuedOperation)
        assertEquals("com.multiapp.app:v2", issuedProcessSlot)
        assertEquals(remoteRoute, issued)
        assertEquals(
            ProviderRouteTokenValidationStatus.TOKEN_NOT_FOUND,
            ProviderRouteTokenRegistry.validate(
                token = staleLocalRoute.token,
                callerInstanceId = staleLocalRoute.callerInstanceId,
                targetInstanceId = staleLocalRoute.targetInstanceId,
                authority = staleLocalRoute.authority,
                operation = staleLocalRoute.operation,
                nowMillis = 120L
            ).status
        )
        assertEquals(
            ProviderRouteTokenValidationStatus.TOKEN_NOT_FOUND,
            ProviderRouteTokenRegistry.validate(
                token = remoteRoute.token,
                callerInstanceId = remoteRoute.callerInstanceId,
                targetInstanceId = remoteRoute.targetInstanceId,
                authority = remoteRoute.authority,
                operation = remoteRoute.operation,
                expectedProcessSlot = remoteRoute.processSlot,
                nowMillis = 120L
            ).status
        )
    }

    @Test
    fun `authoritative issuer null and exception fail closed without local fallback`() {
        ProviderRouteTokenRegistry.installAuthoritativeIssuer(
            ProviderRouteTokenIssuer { _, _, _, _, _ -> null }
        )
        assertFailsWith<IllegalStateException> {
            ProviderRouteTokenRegistry.issue(
                callerInstanceId = "inst-caller",
                targetInstanceId = "inst-target",
                authority = "com.test.minimal.probe",
                operation = "query",
                nowMillis = 100L
            )
        }

        ProviderRouteTokenRegistry.installAuthoritativeIssuer(
            ProviderRouteTokenIssuer { _, _, _, _, _ -> error("engine unavailable") }
        )
        val failure = assertFailsWith<IllegalStateException> {
            ProviderRouteTokenRegistry.issue(
                callerInstanceId = "inst-caller",
                targetInstanceId = "inst-target",
                authority = "com.test.minimal.probe",
                operation = "query",
                nowMillis = 100L
            )
        }

        assertEquals("engine unavailable", failure.cause?.message)
    }

    @Test
    fun `remembered caller slot is not sent as authoritative target slot`() {
        ProviderRouteTokenRegistry.rememberProcessSlot("inst-caller", "com.multiapp.app:v1")
        var requestedTargetSlot: String? = "not-called"
        ProviderRouteTokenRegistry.installAuthoritativeIssuer(
            ProviderRouteTokenIssuer { caller, target, authority, operation, processSlot ->
                requestedTargetSlot = processSlot
                ProviderRouteToken(
                    token = "engine-" + "t".repeat(40),
                    callerInstanceId = caller,
                    targetInstanceId = target,
                    authority = authority,
                    operation = operation,
                    expiresAtMillis = 200L,
                    processSlot = "com.multiapp.app:v6"
                )
            }
        )

        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-caller",
            targetInstanceId = "inst-target",
            authority = "com.test.remote.provider",
            operation = "query",
            nowMillis = 100L
        )

        assertEquals(null, requestedTargetSlot)
        assertEquals("com.multiapp.app:v6", route.processSlot)
    }

    @Test
    fun `authoritative issuer rejects forged route fields and tokens`() {
        val expected = ProviderRouteToken(
            token = "r".repeat(40),
            callerInstanceId = "inst-caller",
            targetInstanceId = "inst-target",
            authority = "com.test.minimal.probe",
            operation = "openFile",
            expiresAtMillis = 150L,
            processSlot = "com.multiapp.app:v2"
        )
        val forgedRoutes = listOf(
            expected.copy(token = " "),
            expected.copy(token = "too-short"),
            expected.copy(callerInstanceId = "forged-caller"),
            expected.copy(targetInstanceId = "forged-target"),
            expected.copy(authority = "forged.authority"),
            expected.copy(operation = "openFileDescriptor"),
            expected.copy(processSlot = "com.multiapp.app:v7"),
            expected.copy(expiresAtMillis = 100L)
        )

        forgedRoutes.forEach { forgedRoute ->
            ProviderRouteTokenRegistry.installAuthoritativeIssuer(
                ProviderRouteTokenIssuer { _, _, _, _, _ -> forgedRoute }
            )
            assertFailsWith<IllegalStateException> {
                ProviderRouteTokenRegistry.issue(
                    callerInstanceId = expected.callerInstanceId,
                    targetInstanceId = expected.targetInstanceId,
                    authority = expected.authority,
                    operation = "openFileDescriptor",
                    processSlot = expected.processSlot,
                    nowMillis = 100L,
                    ttlMillis = 50L
                )
            }
        }
    }

    @Test
    fun `clear for test restores local route token issuance`() {
        var authoritativeCalls = 0
        ProviderRouteTokenRegistry.installAuthoritativeIssuer(
            ProviderRouteTokenIssuer { _, _, _, _, _ ->
                authoritativeCalls += 1
                null
            }
        )
        ProviderRouteTokenRegistry.clearForTest()

        val localRoute = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-local",
            targetInstanceId = "inst-local",
            authority = "com.test.minimal.probe",
            operation = "query",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        assertEquals(0, authoritativeCalls)
        assertEquals(
            ProviderRouteTokenValidationStatus.VALID,
            ProviderRouteTokenRegistry.validate(
                token = localRoute.token,
                callerInstanceId = localRoute.callerInstanceId,
                targetInstanceId = localRoute.targetInstanceId,
                authority = localRoute.authority,
                operation = localRoute.operation,
                nowMillis = 120L
            ).status
        )
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

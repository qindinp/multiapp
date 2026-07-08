package com.multiapp.app.container

import com.multiapp.core.identity.ProviderRouteTokenRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StubContentProviderRouteTokenTest {

    @Test
    fun `direct forged stub route without token is rejected before dispatch`() {
        val status = StubContentProvider().routeTokenStatusForTest(
            token = null,
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            operationName = "query"
        )

        assertEquals("invalid route token:MISSING_TOKEN", status)
    }

    @Test
    fun `provider route gate canonical query preserves guest query and replaces proxy route`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        val canonicalQuery = ProviderRouteTokenGate.canonicalEncodedQueryForTest(
            encodedQuery = "bookId=123" +
                "&multiapp_instanceId=forged" +
                "&multiapp_guestAuthority=forged.authority" +
                "&multiapp_routeToken=stale-token" +
                "&flag",
            route = route
        )

        assertEquals(
            "bookId=123" +
                "&flag" +
                "&multiapp_instanceId=inst-001" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&multiapp_routeToken=${route.token}",
            canonicalQuery
        )
        assertEquals("bookId=123&flag", ProviderProxyUri.rewriteEncodedQuery(canonicalQuery))
    }

    @Test
    fun `provider route gate canonical query uses token process slot`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query",
            processSlot = "com.multiapp.app:v3"
        )

        val canonicalQuery = ProviderRouteTokenGate.canonicalEncodedQueryForTest(
            encodedQuery = "bookId=123&multiapp_processSlot=com.multiapp.app%3Av7",
            route = route
        )

        assertEquals(
            "bookId=123" +
                "&multiapp_instanceId=inst-001" +
                "&multiapp_guestAuthority=com.test.minimal.probe" +
                "&multiapp_processSlot=com.multiapp.app:v3" +
                "&multiapp_routeToken=${route.token}",
            canonicalQuery
        )
    }

    @Test
    fun `stub route token is bound to operation`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query"
        )

        val status = StubContentProvider().routeTokenStatusForTest(
            token = route.token,
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            operationName = "insert"
        )

        assertEquals("invalid route token:OPERATION_MISMATCH", status)
    }

    @Test
    fun `stub route token is bound to instance`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query"
        )

        val status = StubContentProvider().routeTokenStatusForTest(
            token = route.token,
            instanceId = "inst-002",
            guestAuthority = "com.test.minimal.probe",
            operationName = "query"
        )

        assertEquals("invalid route token:CALLER_INSTANCE_MISMATCH", status)
    }

    @Test
    fun `stub route token is bound to authority`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query"
        )

        val status = StubContentProvider().routeTokenStatusForTest(
            token = route.token,
            instanceId = "inst-001",
            guestAuthority = "com.test.other.probe",
            operationName = "query"
        )

        assertEquals("invalid route token:AUTHORITY_MISMATCH", status)
    }

    @Test
    fun `stub route token is bound to process slot when expected`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query",
            processSlot = "com.multiapp.app:v1"
        )

        val status = StubContentProvider().routeTokenStatusForTest(
            token = route.token,
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            operationName = "query",
            expectedProcessSlot = "com.multiapp.app:v2"
        )

        assertEquals("invalid route token:PROCESS_SLOT_MISMATCH", status)
    }

    @Test
    fun `stub route token expires fail closed`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "query",
            nowMillis = 100L,
            ttlMillis = 50L
        )

        val status = StubContentProvider().routeTokenStatusForTest(
            token = route.token,
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            operationName = "query",
            nowMillis = 151L
        )

        assertEquals("invalid route token:EXPIRED", status)
    }

    @Test
    fun `stub accepts route token generated for same instance authority and operation`() {
        val route = ProviderRouteTokenRegistry.issue(
            callerInstanceId = "inst-001",
            targetInstanceId = "inst-001",
            authority = "com.test.minimal.probe",
            operation = "openFileDescriptor"
        )

        val status = StubContentProvider().routeTokenStatusForTest(
            token = route.token,
            instanceId = "inst-001",
            guestAuthority = "com.test.minimal.probe",
            operationName = "openFile"
        )

        assertEquals("VALID", status)
    }
}

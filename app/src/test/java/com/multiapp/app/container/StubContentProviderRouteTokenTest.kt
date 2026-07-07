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

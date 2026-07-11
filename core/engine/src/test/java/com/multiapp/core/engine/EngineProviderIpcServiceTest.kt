package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineProviderIpcServiceTest {
    @Test
    fun `connected authority owns Provider planning`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val request = request()
        val remote = plan(message = "remote_provider_plan")
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remotePlan = { _, _ -> remote },
            remoteRecord = { _, _ -> true },
            authorityConnected = { true }
        )

        assertSame(remote, service.planProvider("instance-1", request))
        verify(exactly = 0) { fallback.planProvider(any(), any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Provider response`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { true }
        )

        val actual = service.planProvider("instance-1", request())

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_provider_ipc_plan_invalid", actual.message)
        verify(exactly = 0) { fallback.planProvider(any(), any()) }
    }

    @Test
    fun `connected authority owns global Provider authority resolution`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val request = VirtualProviderAuthorityResolveRequest(
            guestAuthority = "com.example.provider",
            operation = EngineProviderOperation.QUERY,
            encodedPath = "/books/7"
        )
        val remote = VirtualProviderAuthorityResolveResult(
            callerInstanceId = "instance-1",
            guestAuthority = request.guestAuthority,
            verdict = EngineResultStatus.PARTIAL,
            virtualAuthority = true,
            targetInstanceId = "instance-2",
            message = "remote_authority_owner"
        )
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteResolve = { _, _ -> remote },
            authorityConnected = { true }
        )

        assertSame(remote, service.resolveProviderAuthority("instance-1", request))
        verify(exactly = 0) { fallback.resolveProviderAuthority(any(), any()) }
    }

    @Test
    fun `connected authority resolution fails closed for malformed response`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val request = VirtualProviderAuthorityResolveRequest(
            guestAuthority = "com.example.provider",
            operation = EngineProviderOperation.QUERY
        )
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteResolve = { _, _ -> null },
            authorityConnected = { true }
        )

        val result = service.resolveProviderAuthority("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, result.verdict)
        assertTrue(result.virtualAuthority)
        assertNull(result.targetInstanceId)
        verify(exactly = 0) { fallback.resolveProviderAuthority(any(), any()) }
    }

    @Test
    fun `unavailable authority uses durable Provider fallback`() {
        val fallback = mockk<VirtualProviderService>()
        val request = request()
        val local = plan(message = "durable_provider_plan")
        every { fallback.planProvider("instance-1", request) } returns local
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        assertSame(local, service.planProvider("instance-1", request))
    }

    @Test
    fun `connected authority owns Provider dispatch evidence`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val result = VirtualProviderOperationResult(
            instanceId = "instance-1",
            operation = EngineProviderOperation.QUERY,
            guestAuthority = "com.example.provider",
            proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
            providerClassName = "com.example.DataProvider",
            verdict = EngineResultStatus.PASS,
            reason = "provider_ready",
            ready = true,
            message = "provider_dispatch_pass"
        )
        val acceptedService = IpcBackedVirtualProviderService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> true },
            authorityConnected = { true }
        )
        val rejectedService = IpcBackedVirtualProviderService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> false },
            authorityConnected = { true }
        )

        assertTrue(acceptedService.recordProviderDispatch("instance-1", result))
        assertFalse(rejectedService.recordProviderDispatch("instance-1", result))
        verify(exactly = 0) { fallback.recordProviderDispatch(any(), any()) }
    }

    @Test
    fun `connected authority owns Provider runtime state query`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val remote = VirtualProviderRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PARTIAL,
            records = listOf(
                EngineProviderRuntimeRecord(
                    instanceId = "instance-1",
                    guestAuthority = "com.example.provider",
                    providerClassName = "com.example.DataProvider",
                    processSlot = "com.multiapp.app:v1",
                    runtimeEpoch = 42L,
                    lastOperation = EngineProviderOperation.QUERY
                )
            ),
            message = "remote_provider_runtime_state"
        )
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteState = { remote },
            authorityConnected = { true }
        )

        assertSame(remote, service.queryProviderRuntimeState("instance-1"))
        verify(exactly = 0) { fallback.queryProviderRuntimeState(any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Provider runtime state`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteState = { null },
            authorityConnected = { true }
        )

        val state = service.queryProviderRuntimeState("instance-1")

        assertEquals(EngineResultStatus.FAIL, state.verdict)
        assertEquals("engine_provider_ipc_runtime_state_invalid", state.message)
        verify(exactly = 0) { fallback.queryProviderRuntimeState(any()) }
    }

    @Test
    fun `connected authority owns durable Provider URI grants`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val request = VirtualProviderUriGrantRequest(
            guestAuthority = "com.example.provider",
            encodedPath = "/books/7",
            modeFlags = EngineProviderUriGrantModes.READ,
            targetInstanceId = "instance-2"
        )
        val remote = VirtualProviderUriGrantResult(
            ownerInstanceId = "instance-1",
            targetInstanceId = "instance-2",
            guestAuthority = request.guestAuthority,
            encodedPath = request.encodedPath,
            modeFlags = request.modeFlags,
            verdict = EngineResultStatus.PARTIAL,
            granted = true,
            affectedGrantCount = 1,
            message = "remote_provider_uri_grant"
        )
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteGrant = { _, _ -> remote },
            authorityConnected = { true }
        )

        assertSame(remote, service.grantUriPermission("instance-1", request))
        verify(exactly = 0) { fallback.grantUriPermission(any(), any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Provider URI grant response`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val request = VirtualProviderUriGrantRequest(
            guestAuthority = "com.example.provider",
            encodedPath = "/books/7",
            modeFlags = EngineProviderUriGrantModes.READ,
            targetInstanceId = "instance-2"
        )
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteGrant = { _, _ -> null },
            authorityConnected = { true }
        )

        val actual = service.grantUriPermission("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_provider_ipc_uri_grant_invalid", actual.message)
        assertFalse(actual.granted)
        verify(exactly = 0) { fallback.grantUriPermission(any(), any()) }
    }

    @Test
    fun `connected authority owns persistable Provider URI take and release`() {
        val fallback = mockk<VirtualProviderService>(relaxed = true)
        val request = VirtualProviderUriGrantRequest(
            guestAuthority = "com.example.provider",
            encodedPath = "/documents/7",
            modeFlags = EngineProviderUriGrantModes.READ,
            ownerInstanceId = "instance-1",
            targetInstanceId = "instance-2"
        )
        val taken = VirtualProviderUriGrantResult(
            ownerInstanceId = "instance-1",
            targetInstanceId = "instance-2",
            guestAuthority = request.guestAuthority,
            encodedPath = request.encodedPath,
            modeFlags = request.modeFlags,
            verdict = EngineResultStatus.PARTIAL,
            granted = true,
            affectedGrantCount = 1,
            persistedModeFlags = EngineProviderUriGrantModes.READ,
            message = "remote_provider_persistable_uri_taken"
        )
        val released = taken.copy(
            granted = false,
            persistedModeFlags = 0,
            message = "remote_provider_persistable_uri_released"
        )
        val service = IpcBackedVirtualProviderService(
            fallback = fallback,
            remoteTakePersistable = { _, _ -> taken },
            remoteReleasePersistable = { _, _ -> released },
            authorityConnected = { true }
        )

        assertSame(taken, service.takePersistableUriPermission("instance-2", request))
        assertSame(released, service.releasePersistableUriPermission("instance-2", request))
        verify(exactly = 0) { fallback.takePersistableUriPermission(any(), any()) }
        verify(exactly = 0) { fallback.releasePersistableUriPermission(any(), any()) }
    }

    private fun request() = VirtualProviderDispatchPlanRequest(
        operation = EngineProviderOperation.QUERY,
        guestAuthority = "com.example.provider",
        proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
        processSlot = "com.multiapp.app:v1",
        routeTokenPresent = true
    )

    private fun plan(message: String) = VirtualProviderDispatchPlan(
        instanceId = "instance-1",
        operation = EngineProviderOperation.QUERY,
        verdict = EngineResultStatus.PARTIAL,
        guestAuthority = "com.example.provider",
        message = message
    )
}

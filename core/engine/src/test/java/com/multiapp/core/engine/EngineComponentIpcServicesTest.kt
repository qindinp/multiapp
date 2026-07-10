package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineComponentIpcServicesTest {
    @Test
    fun `connected authority owns Service planning and evidence`() {
        val fallback = mockk<VirtualServiceService>(relaxed = true)
        val remotePlan = servicePlan("remote_service_plan")
        val service = IpcBackedVirtualServiceService(
            fallback = fallback,
            remotePlan = { _, _ -> remotePlan },
            remoteRecord = { _, _ -> true },
            authorityConnected = { true }
        )

        assertSame(remotePlan, service.planService("instance-1", serviceRequest()))
        assertTrue(service.recordServiceDispatch("instance-1", serviceResult()))
        verify(exactly = 0) { fallback.planService(any(), any()) }
        verify(exactly = 0) { fallback.recordServiceDispatch(any(), any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Service response`() {
        val fallback = mockk<VirtualServiceService>(relaxed = true)
        val service = IpcBackedVirtualServiceService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> false },
            authorityConnected = { true }
        )

        val plan = service.planService("instance-1", serviceRequest())

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("engine_service_ipc_plan_invalid", plan.message)
        assertFalse(service.recordServiceDispatch("instance-1", serviceResult()))
        verify(exactly = 0) { fallback.planService(any(), any()) }
    }

    @Test
    fun `unavailable authority uses durable Service fallback`() {
        val fallback = mockk<VirtualServiceService>()
        val request = serviceRequest()
        val local = servicePlan("durable_service_plan")
        every { fallback.planService("instance-1", request) } returns local
        val service = IpcBackedVirtualServiceService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        assertSame(local, service.planService("instance-1", request))
    }

    @Test
    fun `connected authority owns Service runtime state query`() {
        val fallback = mockk<VirtualServiceService>(relaxed = true)
        val remote = VirtualServiceRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PARTIAL,
            records = listOf(
                EngineServiceRuntimeRecord(
                    instanceId = "instance-1",
                    serviceClassName = "com.example.SyncService",
                    processSlot = "com.multiapp.app:v1",
                    runtimeEpoch = 1L,
                    state = EngineServiceLifecycleState.STARTED,
                    activeStartCount = 1
                )
            ),
            message = "remote_service_runtime_state"
        )
        val service = IpcBackedVirtualServiceService(
            fallback = fallback,
            remoteState = { remote },
            authorityConnected = { true }
        )

        assertSame(remote, service.queryServiceRuntimeState("instance-1"))
        verify(exactly = 0) { fallback.queryServiceRuntimeState(any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Service runtime state`() {
        val fallback = mockk<VirtualServiceService>(relaxed = true)
        val service = IpcBackedVirtualServiceService(
            fallback = fallback,
            remoteState = { null },
            authorityConnected = { true }
        )

        val state = service.queryServiceRuntimeState("instance-1")

        assertEquals(EngineResultStatus.FAIL, state.verdict)
        assertEquals("engine_service_ipc_runtime_state_invalid", state.message)
        verify(exactly = 0) { fallback.queryServiceRuntimeState(any()) }
    }

    @Test
    fun `connected authority owns Broadcast planning and evidence`() {
        val fallback = mockk<VirtualBroadcastService>(relaxed = true)
        val remotePlan = broadcastPlan("remote_broadcast_plan")
        val service = IpcBackedVirtualBroadcastService(
            fallback = fallback,
            remotePlan = { _, _ -> remotePlan },
            remoteRecord = { _, _ -> true },
            authorityConnected = { true }
        )

        assertSame(remotePlan, service.planBroadcast("instance-1", broadcastRequest()))
        assertTrue(service.recordBroadcastDispatch("instance-1", broadcastResult()))
        verify(exactly = 0) { fallback.planBroadcast(any(), any()) }
        verify(exactly = 0) { fallback.recordBroadcastDispatch(any(), any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Broadcast response`() {
        val fallback = mockk<VirtualBroadcastService>(relaxed = true)
        val service = IpcBackedVirtualBroadcastService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> false },
            authorityConnected = { true }
        )

        val plan = service.planBroadcast("instance-1", broadcastRequest())

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("engine_broadcast_ipc_plan_invalid", plan.message)
        assertFalse(service.recordBroadcastDispatch("instance-1", broadcastResult()))
        verify(exactly = 0) { fallback.planBroadcast(any(), any()) }
    }

    @Test
    fun `unavailable authority uses durable Broadcast fallback`() {
        val fallback = mockk<VirtualBroadcastService>()
        val request = broadcastRequest()
        val local = broadcastPlan("durable_broadcast_plan")
        every { fallback.planBroadcast("instance-1", request) } returns local
        val service = IpcBackedVirtualBroadcastService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        assertSame(local, service.planBroadcast("instance-1", request))
    }

    @Test
    fun `connected authority owns Broadcast runtime state query`() {
        val fallback = mockk<VirtualBroadcastService>(relaxed = true)
        val remote = VirtualBroadcastRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PARTIAL,
            records = listOf(
                EngineBroadcastRuntimeRecord(
                    instanceId = "instance-1",
                    receiverClassName = "com.example.EventReceiver",
                    action = "test.EVENT",
                    processSlot = "com.multiapp.app:v1",
                    runtimeEpoch = 42L,
                    state = EngineBroadcastDeliveryState.DELIVERED,
                    lastVerdict = EngineResultStatus.PASS,
                    lastReason = "receiver_delivered",
                    deliveredCount = 1L
                )
            ),
            message = "remote_broadcast_runtime_state"
        )
        val service = IpcBackedVirtualBroadcastService(
            fallback = fallback,
            remoteState = { remote },
            authorityConnected = { true }
        )

        assertSame(remote, service.queryBroadcastRuntimeState("instance-1"))
        verify(exactly = 0) { fallback.queryBroadcastRuntimeState(any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Broadcast runtime state`() {
        val fallback = mockk<VirtualBroadcastService>(relaxed = true)
        val service = IpcBackedVirtualBroadcastService(
            fallback = fallback,
            remoteState = { null },
            authorityConnected = { true }
        )

        val state = service.queryBroadcastRuntimeState("instance-1")

        assertEquals(EngineResultStatus.FAIL, state.verdict)
        assertEquals("engine_broadcast_ipc_runtime_state_invalid", state.message)
        verify(exactly = 0) { fallback.queryBroadcastRuntimeState(any()) }
    }

    private fun serviceRequest() = VirtualServiceDispatchPlanRequest(
        operation = VirtualServiceOperation.START,
        action = "test.SYNC"
    )

    private fun servicePlan(message: String) = VirtualServiceDispatchPlan(
        instanceId = "instance-1",
        operation = VirtualServiceOperation.START,
        verdict = EngineResultStatus.PARTIAL,
        action = "test.SYNC",
        message = message
    )

    private fun serviceResult() = VirtualServiceOperationResult(
        instanceId = "instance-1",
        operation = VirtualServiceOperation.START,
        serviceClassName = "com.example.SyncService",
        action = "test.SYNC",
        verdict = EngineResultStatus.PASS,
        reason = "service_started",
        started = true,
        message = "service_dispatch_pass"
    )

    private fun broadcastRequest() = VirtualBroadcastDispatchPlanRequest(action = "test.EVENT")

    private fun broadcastPlan(message: String) = VirtualBroadcastDispatchPlan(
        instanceId = "instance-1",
        verdict = EngineResultStatus.PARTIAL,
        action = "test.EVENT",
        message = message
    )

    private fun broadcastResult() = VirtualBroadcastOperationResult(
        instanceId = "instance-1",
        receiverClassName = "com.example.EventReceiver",
        action = "test.EVENT",
        verdict = EngineResultStatus.PASS,
        reason = "receiver_delivered",
        delivered = true,
        message = "broadcast_dispatch_pass"
    )
}

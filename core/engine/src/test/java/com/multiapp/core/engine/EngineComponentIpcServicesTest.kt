package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
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
    fun `Service execution plan requires exactly one engine lease`() {
        val request = serviceRequest().copy(operationLeaseRequested = true)
        val missingLease = IpcBackedVirtualServiceService(
            remotePlan = { _, _ -> servicePlan("missing_lease") },
            authorityConnected = { true }
        ).planService("instance-1", request)
        val lease = EngineServiceOperationLeaseIdentity(
            leaseToken = "service-lease",
            instanceId = "instance-1",
            runtimeEpoch = 1L,
            engineSessionId = "engine-session-1",
            processSlot = "com.multiapp.app:v0",
            processId = 4200,
            operation = VirtualServiceOperation.START.name,
            component = "com.example.SyncService",
            issuedAtNanos = 1L,
            expiresAtNanos = 2L
        )
        val leasedPlan = servicePlan("leased").copy(
            targets = listOf(
                VirtualServiceDispatchTarget(
                    instanceId = "instance-1",
                    originPackageName = "com.example",
                    virtualPackageName = "com.multiapp.instance.example",
                    serviceClassName = lease.component,
                    action = "test.SYNC",
                    reason = "explicit",
                    operation = VirtualServiceOperation.START,
                    processSlot = lease.processSlot,
                    operationLease = lease
                )
            )
        )
        val accepted = IpcBackedVirtualServiceService(
            remotePlan = { _, _ -> leasedPlan },
            authorityConnected = { true }
        ).planService("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, missingLease.verdict)
        assertEquals("engine_service_ipc_plan_invalid", missingLease.message)
        assertSame(leasedPlan, accepted)
    }

    @Test
    fun `component Service execution plan accepts server scoped target without primary lease`() {
        val request = serviceRequest().copy(operationLeaseRequested = true)
        val componentPlan = servicePlan("component_authorized").copy(
            targets = listOf(
                VirtualServiceDispatchTarget(
                    instanceId = "instance-1",
                    originPackageName = "com.example",
                    virtualPackageName = "com.multiapp.instance.example",
                    serviceClassName = "com.example.RemoteService",
                    action = "test.SYNC",
                    reason = "explicit",
                    operation = VirtualServiceOperation.START,
                    processSlot = "com.multiapp.app:v3",
                    processName = "com.example:remote",
                    sameProcess = false
                )
            )
        )

        val accepted = IpcBackedVirtualServiceService(
            remotePlan = { _, _ -> componentPlan },
            authorityConnected = { true }
        ).planService("instance-1", request)

        assertSame(componentPlan, accepted)
        assertEquals(null, accepted.targets.single().operationLease)
    }

    @Test
    fun `unavailable authority fails closed without Service fallback`() {
        val fallback = mockk<VirtualServiceService>(relaxed = true)
        val request = serviceRequest()
        val service = IpcBackedVirtualServiceService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        val plan = service.planService("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("engine_service_authority_unavailable:plan", plan.message)
        assertFalse(service.recordServiceDispatch("instance-1", serviceResult()))
        verify(exactly = 0) { fallback.planService(any(), any()) }
        verify(exactly = 0) { fallback.recordServiceDispatch(any(), any()) }
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
    fun `unavailable authority fails closed without Broadcast fallback`() {
        val fallback = mockk<VirtualBroadcastService>(relaxed = true)
        val request = broadcastRequest()
        val service = IpcBackedVirtualBroadcastService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        val plan = service.planBroadcast("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("engine_broadcast_authority_unavailable:plan", plan.message)
        assertFalse(service.recordBroadcastDispatch("instance-1", broadcastResult()))
        verify(exactly = 0) { fallback.planBroadcast(any(), any()) }
        verify(exactly = 0) { fallback.recordBroadcastDispatch(any(), any()) }
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

    @Test
    fun `unavailable authority exposes only explicit read only component snapshots`() {
        val serviceFallback = mockk<VirtualServiceService>(relaxed = true)
        val broadcastFallback = mockk<VirtualBroadcastService>(relaxed = true)
        val serviceState = VirtualServiceRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            message = "durable_service_snapshot"
        )
        val broadcastState = VirtualBroadcastRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            message = "durable_broadcast_snapshot"
        )
        val serviceBinding = VirtualSubsystemRuntimeBinding(
            instanceId = "instance-1",
            subsystem = com.multiapp.core.model.engine.EngineSubsystem.SERVICE,
            verdict = EngineResultStatus.PASS,
            message = "durable_service_binding"
        )
        val broadcastBinding = VirtualSubsystemRuntimeBinding(
            instanceId = "instance-1",
            subsystem = com.multiapp.core.model.engine.EngineSubsystem.BROADCAST,
            verdict = EngineResultStatus.PASS,
            message = "durable_broadcast_binding"
        )
        val service = IpcBackedVirtualServiceService(
            fallback = serviceFallback,
            remoteState = { null },
            readOnlyRuntimeStateSnapshot = { serviceState },
            readOnlyRuntimeBindingSnapshot = { serviceBinding },
            authorityConnected = { false }
        )
        val broadcast = IpcBackedVirtualBroadcastService(
            fallback = broadcastFallback,
            remoteState = { null },
            readOnlyRuntimeStateSnapshot = { broadcastState },
            readOnlyRuntimeBindingSnapshot = { broadcastBinding },
            authorityConnected = { false }
        )

        assertEquals(EngineResultStatus.PARTIAL, service.queryServiceRuntimeState("instance-1").verdict)
        assertEquals(EngineResultStatus.PARTIAL, service.queryRuntimeBinding("instance-1").verdict)
        assertEquals(EngineResultStatus.PARTIAL, broadcast.queryBroadcastRuntimeState("instance-1").verdict)
        assertEquals(EngineResultStatus.PARTIAL, broadcast.queryRuntimeBinding("instance-1").verdict)
        verify(exactly = 0) { serviceFallback.queryServiceRuntimeState(any()) }
        verify(exactly = 0) { serviceFallback.queryRuntimeBinding(any()) }
        verify(exactly = 0) { broadcastFallback.queryBroadcastRuntimeState(any()) }
        verify(exactly = 0) { broadcastFallback.queryRuntimeBinding(any()) }
    }

    @Test
    fun `mismatched component response identity fails closed`() {
        val serviceFallback = mockk<VirtualServiceService>(relaxed = true)
        val broadcastFallback = mockk<VirtualBroadcastService>(relaxed = true)
        val service = IpcBackedVirtualServiceService(
            fallback = serviceFallback,
            remotePlan = { _, _ -> servicePlan("forged").copy(instanceId = "instance-2") },
            authorityConnected = { true }
        )
        val broadcast = IpcBackedVirtualBroadcastService(
            fallback = broadcastFallback,
            remotePlan = { _, _ -> broadcastPlan("forged").copy(instanceId = "instance-2") },
            authorityConnected = { true }
        )

        assertEquals(EngineResultStatus.FAIL, service.planService("instance-1", serviceRequest()).verdict)
        assertEquals(
            EngineResultStatus.FAIL,
            broadcast.planBroadcast("instance-1", broadcastRequest()).verdict
        )
        verify(exactly = 0) { serviceFallback.planService(any(), any()) }
        verify(exactly = 0) { broadcastFallback.planBroadcast(any(), any()) }
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

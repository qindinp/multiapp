package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineActivityIpcServiceTest {
    @Test
    fun `connected authority owns Activity planning`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val request = VirtualActivityDispatchPlanRequest(action = "test.OPEN")
        val remote = plan(message = "remote_activity_plan")
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> remote },
            remoteRecord = { _, _ -> true },
            authorityConnected = { true }
        )

        val actual = service.planActivity("instance-1", request)

        assertSame(remote, actual)
        verify(exactly = 0) { fallback.planActivity(any(), any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Activity plan response`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { true }
        )

        val actual = service.planActivity(
            "instance-1",
            VirtualActivityDispatchPlanRequest(action = "test.OPEN")
        )

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_activity_ipc_plan_invalid", actual.message)
        verify(exactly = 0) { fallback.planActivity(any(), any()) }
    }

    @Test
    fun `unavailable authority fails closed without Activity planning fallback`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val request = VirtualActivityDispatchPlanRequest(action = "test.OPEN")
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        val actual = service.planActivity("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_activity_authority_unavailable", actual.message)
        verify(exactly = 0) { fallback.planActivity(any(), any()) }
    }

    @Test
    fun `connected authority owns Activity dispatch evidence`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val result = VirtualActivityDispatchResult(
            instanceId = "instance-1",
            activityClassName = "com.example.MainActivity",
            action = "test.OPEN",
            verdict = EngineResultStatus.PASS,
            reason = "explicit",
            remapped = true,
            message = "engine_activity_remapped"
        )
        val acceptedService = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> true },
            authorityConnected = { true }
        )
        val rejectedService = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> false },
            authorityConnected = { true }
        )

        assertTrue(acceptedService.recordActivityDispatch("instance-1", result))
        assertFalse(rejectedService.recordActivityDispatch("instance-1", result))
        verify(exactly = 0) { fallback.recordActivityDispatch(any(), any()) }
    }

    @Test
    fun `connected authority owns Activity lifecycle mutation`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val remote = operationResult(
            operation = EngineActivityIpcOperation.MARK_STATE,
            state = VirtualActivityState.STOPPED,
            message = "remote_activity_state_persisted"
        )
        var capturedRequest: EngineActivityIpcMutationRequest? = null
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteMutation = { _, request ->
                capturedRequest = request
                remote
            },
            authorityConnected = { true }
        )

        val actual = service.markActivityState(
            instanceId = "instance-1",
            token = "token-1",
            state = VirtualActivityState.STOPPED
        )

        assertSame(remote, actual)
        assertEquals(EngineActivityIpcOperation.MARK_STATE, capturedRequest?.operation)
        assertEquals("token-1", capturedRequest?.token)
        assertEquals(VirtualActivityState.STOPPED, capturedRequest?.state)
        verify(exactly = 0) { fallback.markActivityState(any(), any(), any()) }
    }

    @Test
    fun `finish result mutation accepts the authoritative source Activity token`() {
        val remote = operationResult(
            operation = EngineActivityIpcOperation.RECORD_FINISH_RESULT,
            token = "source-token",
            message = "activity_finish_result_persisted"
        ).copy(requestCode = 4242, resultCode = -1)
        val service = IpcBackedVirtualActivityService(
            remoteMutation = { _, _ -> remote },
            authorityConnected = { true }
        )

        val actual = service.recordActivityResultForFinish(
            instanceId = "instance-1",
            token = "child-token",
            resultCode = -1,
            dataIntent = VirtualIntentSnapshot(action = "test.RESULT")
        )

        assertSame(remote, actual)
        assertEquals("source-token", actual.token)
        assertEquals(4242, actual.requestCode)
    }

    @Test
    fun `connected authority fails closed for invalid Activity mutation response`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteMutation = { _, _ -> null },
            authorityConnected = { true }
        )

        val actual = service.finishActivity("instance-1", "token-1")

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_activity_ipc_mutation_invalid:finish", actual.message)
        verify(exactly = 0) { fallback.finishActivity(any(), any()) }
    }

    @Test
    fun `unavailable authority fails closed without Activity mutation fallback`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteMutation = { _, _ -> null },
            authorityConnected = { false }
        )

        val actual = service.setActivityResult(
            instanceId = "instance-1",
            token = "token-1",
            resultCode = 201,
            dataIntent = VirtualIntentSnapshot(action = "test.RESULT"),
            requestCode = 9,
            resultWho = null,
            frameworkDispatchAttempted = false,
            frameworkDispatchInvoked = false
        )

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_activity_authority_unavailable:set-result", actual.message)
        verify(exactly = 0) {
            fallback.setActivityResult(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `connected empty Activity consume does not read stale fallback state`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteConsume = { _, operation, _ ->
                EngineActivityIpcConsumeResponse(operation = operation, found = false)
            },
            authorityConnected = { true }
        )

        assertNull(service.consumeActivityResult("instance-1", "token-1"))
        verify(exactly = 0) { fallback.consumeActivityResult(any(), any()) }
    }

    @Test
    fun `unavailable authority never consumes stale Activity fallback state`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteConsume = { _, _, _ -> null },
            authorityConnected = { false }
        )

        assertNull(service.consumeActivityResult("instance-1", "token-1"))
        verify(exactly = 0) { fallback.consumeActivityResult(any(), any()) }
    }

    @Test
    fun `connected authority owns Activity task state query`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val remote = VirtualActivityTaskState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PARTIAL,
            taskCount = 1,
            activityCount = 2,
            message = "remote_activity_task_state"
        )
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteTaskState = { remote },
            authorityConnected = { true }
        )

        assertSame(remote, service.queryTaskState("instance-1"))
        verify(exactly = 0) { fallback.queryTaskState(any()) }
    }

    @Test
    fun `connected authority fails closed for invalid Activity task state`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteTaskState = { null },
            authorityConnected = { true }
        )

        val actual = service.queryTaskState("instance-1")

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_activity_ipc_task_state_invalid", actual.message)
        verify(exactly = 0) { fallback.queryTaskState(any()) }
    }

    @Test
    fun `connected authority owns Activity task snapshot synchronization`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val task = taskRecord()
        val remote = operationResult(
            operation = EngineActivityIpcOperation.MARK_STATE,
            message = "remote_activity_task_state_synced"
        ).copy(operation = "sync-task-state")
        var capturedTasks: List<VirtualTaskRecord> = emptyList()
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteTaskSync = { _, _, tasks ->
                capturedTasks = tasks
                remote
            },
            localTaskSnapshot = { listOf(task) },
            authorityConnected = { true }
        )

        val actual = service.syncActivityTaskState("instance-1", "launch-remapped")

        assertSame(remote, actual)
        assertEquals(listOf(task), capturedTasks)
        verify(exactly = 0) { fallback.syncActivityTaskState(any(), any(), any()) }
    }

    @Test
    fun `unavailable authority rejects Activity task sync without mutable fallback`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val task = taskRecord()
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteTaskSync = { _, _, _ -> null },
            localTaskSnapshot = { listOf(task) },
            authorityConnected = { false }
        )

        val actual = service.syncActivityTaskState("instance-1", "launch-remapped")

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertEquals("engine_activity_authority_unavailable:sync-task-state", actual.message)
        verify(exactly = 0) { fallback.syncActivityTaskState(any(), any(), any()) }
    }

    @Test
    fun `unavailable authority may expose explicit read only Activity snapshots as partial`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val taskState = VirtualActivityTaskState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            taskCount = 1,
            message = "durable_task_snapshot"
        )
        val binding = VirtualSubsystemRuntimeBinding(
            instanceId = "instance-1",
            subsystem = com.multiapp.core.model.engine.EngineSubsystem.ACTIVITY,
            verdict = EngineResultStatus.PASS,
            originPackageName = "com.example",
            message = "durable_runtime_snapshot"
        )
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteTaskState = { null },
            readOnlyTaskStateSnapshot = { taskState },
            readOnlyRuntimeBindingSnapshot = { binding },
            authorityConnected = { false }
        )

        val state = service.queryTaskState("instance-1")
        val runtime = service.queryRuntimeBinding("instance-1")

        assertEquals(EngineResultStatus.PARTIAL, state.verdict)
        assertEquals("engine_activity_read_only_task_snapshot:durable_task_snapshot", state.message)
        assertEquals(EngineResultStatus.PARTIAL, runtime.verdict)
        assertEquals("engine_activity_read_only_runtime_snapshot:durable_runtime_snapshot", runtime.message)
        verify(exactly = 0) { fallback.queryTaskState(any()) }
        verify(exactly = 0) { fallback.queryRuntimeBinding(any()) }
    }

    @Test
    fun `mismatched Activity response identity fails closed`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> plan("forged").copy(instanceId = "instance-2") },
            remoteMutation = { _, _ ->
                operationResult(EngineActivityIpcOperation.FINISH, message = "forged")
                    .copy(instanceId = "instance-2")
            },
            authorityConnected = { true }
        )

        assertEquals(
            EngineResultStatus.FAIL,
            service.planActivity("instance-1", VirtualActivityDispatchPlanRequest(action = "test.OPEN")).verdict
        )
        assertEquals(EngineResultStatus.FAIL, service.finishActivity("instance-1", "token-1").verdict)
        verify(exactly = 0) { fallback.planActivity(any(), any()) }
        verify(exactly = 0) { fallback.finishActivity(any(), any()) }
    }

    @Test
    fun `IPC proxy slot store uses only remote authority`() {
        val key = proxySlotKey()
        val proxy = "com.multiapp.app.container.ProxyActivity0"
        val store = IpcBackedProxyActivitySlotAssignmentStore(
            remoteFind = { proxySlotResult(key, PROXY_ACTIVITY_SLOT_QUERY_OPERATION, proxy) },
            remoteReserve = { _, _ ->
                proxySlotResult(key, PROXY_ACTIVITY_SLOT_RESERVE_OPERATION, proxy)
            },
            remoteCompareAndSet = { _, _, new ->
                proxySlotResult(
                    key = key,
                    operation = PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION,
                    proxyActivityClassName = new
                )
            }
        )

        assertEquals(proxy, store.find(key))
        assertEquals(proxy, store.reserve(key, listOf(proxy)))
        assertTrue(store.compareAndSet(key, proxy, null))
        assertFailsWith<UnsupportedOperationException> { store.save(key, proxy) }
        assertNull(store.ownerOf(proxy))
        assertEquals(0, store.removeInstance(key.instanceId))
        assertEquals(0, store.pruneStaleAssignments(setOf(key.instanceId), setOf(proxy), setOf(proxy)))
    }

    @Test
    fun `disconnected proxy slot store fails closed`() {
        val key = proxySlotKey()
        val proxy = "com.multiapp.app.container.ProxyActivity0"
        val store = IpcBackedProxyActivitySlotAssignmentStore(
            remoteFind = { null },
            remoteReserve = { _, _ -> throw IllegalStateException("binder disconnected") },
            remoteCompareAndSet = { _, _, _ -> null }
        )

        assertNull(store.find(key))
        assertNull(store.reserve(key, listOf(proxy)))
        assertFalse(store.compareAndSet(key, null, proxy))
    }

    @Test
    fun `proxy slot store rejects response identity and operation mismatch`() {
        val key = proxySlotKey()
        val otherKey = key.copy(instanceId = "instance-2")
        val proxy = "com.multiapp.app.container.ProxyActivity0"
        val store = IpcBackedProxyActivitySlotAssignmentStore(
            remoteFind = {
                proxySlotResult(otherKey, PROXY_ACTIVITY_SLOT_QUERY_OPERATION, proxy)
            },
            remoteReserve = { _, _ ->
                proxySlotResult(key, PROXY_ACTIVITY_SLOT_QUERY_OPERATION, proxy)
            },
            remoteCompareAndSet = { _, _, _ ->
                proxySlotResult(
                    key,
                    PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION,
                    proxy
                ).copy(instanceId = "instance-2")
            }
        )

        assertNull(store.find(key))
        assertNull(store.reserve(key, listOf(proxy)))
        assertFalse(store.compareAndSet(key, null, proxy))
    }

    @Test
    fun `proxy slot candidate budget is enforced before remote call`() {
        val key = proxySlotKey()
        var reserveCalls = 0
        val store = IpcBackedProxyActivitySlotAssignmentStore(
            remoteReserve = { _, _ ->
                reserveCalls += 1
                null
            }
        )
        val overBudget = List(EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_COUNT + 1) {
            "com.multiapp.app.container.ProxyActivity$it"
        }

        assertNull(store.reserve(key, overBudget))
        assertNull(store.reserve(key, listOf("x".repeat(
            EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_LENGTH + 1
        ))))
        assertEquals(0, reserveCalls)
    }

    @Test
    fun `IPC backed Activity service forwards proxy slot operations and rejects mismatch`() {
        val fallback = mockk<VirtualActivityService>(relaxed = true)
        val key = proxySlotKey()
        val proxy = "com.multiapp.app.container.ProxyActivity0"
        val remote = proxySlotResult(key, PROXY_ACTIVITY_SLOT_QUERY_OPERATION, proxy)
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteProxySlotQuery = { _, _ -> remote },
            remoteProxySlotReserve = { _, _, _ ->
                remote.copy(operation = PROXY_ACTIVITY_SLOT_RESERVE_OPERATION)
            },
            remoteProxySlotCompareAndSet = { _, _, _, _ ->
                remote.copy(
                    operation = PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION,
                    proxyActivityClassName = null
                )
            },
            authorityConnected = { true }
        )

        assertSame(remote, service.queryProxyActivitySlot(key.instanceId, key))
        assertEquals(
            EngineResultStatus.PASS,
            service.reserveProxyActivitySlot(key.instanceId, key, listOf(proxy)).verdict
        )
        assertEquals(
            EngineResultStatus.PASS,
            service.compareAndSetProxyActivitySlot(key.instanceId, key, proxy, null).verdict
        )
        assertEquals(
            EngineResultStatus.FAIL,
            service.queryProxyActivitySlot("instance-2", key).verdict
        )
    }

    private fun plan(message: String) = VirtualActivityDispatchPlan(
        instanceId = "instance-1",
        verdict = EngineResultStatus.PARTIAL,
        action = "test.OPEN",
        message = message
    )

    private fun operationResult(
        operation: EngineActivityIpcOperation,
        token: String = "token-1",
        state: VirtualActivityState? = null,
        message: String
    ) = VirtualActivityOperationResult(
        instanceId = "instance-1",
        operation = operation.wireName,
        verdict = EngineResultStatus.PASS,
        token = token,
        state = state,
        message = message
    )

    private fun proxySlotKey() = ProxyActivitySlotKey(
        instanceId = "instance-1",
        launchMode = "standard",
        taskKey = "task-1"
    )

    private fun proxySlotResult(
        key: ProxyActivitySlotKey,
        operation: String,
        proxyActivityClassName: String?
    ) = VirtualProxyActivitySlotOperationResult(
        instanceId = key.instanceId,
        operation = operation,
        verdict = EngineResultStatus.PASS,
        key = key,
        proxyActivityClassName = proxyActivityClassName,
        matched = true,
        removedCount = 0,
        message = "proxy_activity_slot_test"
    )

    private fun taskRecord() = VirtualTaskRecord(
        taskId = 7,
        affinity = "com.example:instance-1",
        activities = listOf(
            VirtualActivityRecord(
                token = "token-1",
                instanceId = "instance-1",
                originPackageName = "com.example",
                guestActivityClassName = "com.example.MainActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                taskId = 7
            )
        )
    )
}

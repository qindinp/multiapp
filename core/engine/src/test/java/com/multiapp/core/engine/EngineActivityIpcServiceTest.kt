package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `unavailable authority uses durable Activity fallback`() {
        val fallback = mockk<VirtualActivityService>()
        val request = VirtualActivityDispatchPlanRequest(action = "test.OPEN")
        val local = plan(message = "durable_activity_plan")
        every { fallback.planActivity("instance-1", request) } returns local
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remotePlan = { _, _ -> null },
            remoteRecord = { _, _ -> null },
            authorityConnected = { false }
        )

        assertSame(local, service.planActivity("instance-1", request))
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
    fun `unavailable authority uses durable Activity mutation fallback`() {
        val fallback = mockk<VirtualActivityService>()
        val local = operationResult(
            operation = EngineActivityIpcOperation.SET_RESULT,
            message = "durable_activity_result_persisted"
        )
        every {
            fallback.setActivityResult(
                "instance-1",
                "token-1",
                201,
                any(),
                9,
                null,
                false,
                false
            )
        } returns local
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

        assertSame(local, actual)
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
    fun `unavailable authority uses durable Activity consume fallback`() {
        val fallback = mockk<VirtualActivityService>()
        val local = VirtualActivityResult(resultCode = 202, requestCode = 9)
        every { fallback.consumeActivityResult("instance-1", "token-1") } returns local
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteConsume = { _, _, _ -> null },
            authorityConnected = { false }
        )

        assertSame(local, service.consumeActivityResult("instance-1", "token-1"))
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
    fun `unavailable authority persists Activity task snapshot through fallback`() {
        val fallback = mockk<VirtualActivityService>()
        val task = taskRecord()
        val local = operationResult(
            operation = EngineActivityIpcOperation.MARK_STATE,
            message = "durable_activity_task_state_synced"
        ).copy(operation = "sync-task-state")
        every {
            fallback.syncActivityTaskState("instance-1", "launch-remapped", listOf(task))
        } returns local
        val service = IpcBackedVirtualActivityService(
            fallback = fallback,
            remoteTaskSync = { _, _, _ -> null },
            localTaskSnapshot = { listOf(task) },
            authorityConnected = { false }
        )

        assertSame(local, service.syncActivityTaskState("instance-1", "launch-remapped"))
    }

    private fun plan(message: String) = VirtualActivityDispatchPlan(
        instanceId = "instance-1",
        verdict = EngineResultStatus.PARTIAL,
        action = "test.OPEN",
        message = message
    )

    private fun operationResult(
        operation: EngineActivityIpcOperation,
        state: VirtualActivityState? = null,
        message: String
    ) = VirtualActivityOperationResult(
        instanceId = "instance-1",
        operation = operation.wireName,
        verdict = EngineResultStatus.PASS,
        token = "token-1",
        state = state,
        message = message
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

package com.multiapp.core.engine

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.VirtualActivityLaunchAllocation
import com.multiapp.core.loader.VirtualActivityLaunchAllocationProvider
import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.loader.VirtualContextWrapper
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.VirtualActivityRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineActivityRuntimeTest {

    @Test
    fun `engine activity coordinator rejects guest Context as proxy owner`() {
        assertFailsWith<IllegalArgumentException> {
            EngineActivityLaunchCoordinator(
                hostContext = mockk(relaxed = true) {
                    every { packageName } returns "com.tencent.mobileqq"
                },
                processSlot = "com.multiapp.app:v1"
            )
        }
    }

    @Test
    fun `task controller persists snapshot through Activity service`() {
        val manager = VirtualActivityRecordManager().apply {
            registerLaunch(activityRecord(token = "token-persist"), intentFlags = 0)
        }
        val records = EngineActivityTaskRecords(
            manager = manager,
            stateStore = InMemoryEngineActivityTaskStateStore()
        )
        val service = mockk<VirtualActivityService>()
        every { service.queryTaskState("inst-001") } returns VirtualActivityTaskState(
            instanceId = "inst-001",
            verdict = EngineResultStatus.PASS,
            taskCount = 1,
            activityCount = 1,
            tasks = records.snapshot().tasks,
            message = "engine_owned_activity_task_state"
        )
        val controller = EngineActivityTaskController(service, records)

        val result = controller.persist("inst-001")

        assertEquals("ENGINE_OWNED", result.status)
        assertEquals(1, result.activityCount)
        verify(exactly = 1) { service.queryTaskState("inst-001") }
        verify(exactly = 0) { service.syncActivityTaskState(any(), any(), any()) }
    }

    @Test
    fun `engine activity launch coordinator allocates proxy record from engine plan`() {
        val hostPackageName = "com.multiapp.app"
        val processSlot = "com.multiapp.app:v1"
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns hostPackageName
        }
        val sourceIntent = mockk<Intent>(relaxed = true) {
            every { action } returns "test.OPEN"
            every { flags } returns 0
        }
        val proxyIntent = mockk<Intent>(relaxed = true)
        val manager = VirtualActivityRecordManager()
        val coordinator = EngineActivityLaunchCoordinator(
            hostContext = context,
            processSlot = processSlot,
            activityRecordManager = manager,
            allocationProvider = mockk(relaxed = true),
            proxyIntentFactory = { _, _, _ -> proxyIntent }
        )
        val proxyActivityClassName = EngineProxyActivitySlots.classNamesForProcessSlot(
            hostPackageName = hostPackageName,
            processSlot = processSlot,
            launchMode = "singleTop"
        ).first()
        val plan = VirtualActivityDispatchPlan(
            instanceId = "inst-001",
            verdict = EngineResultStatus.PARTIAL,
            action = "test.OPEN",
            targets = listOf(
                VirtualActivityDispatchTarget(
                    instanceId = "inst-001",
                    originPackageName = "com.example.app",
                    virtualPackageName = "com.multiapp.virtual.inst-001",
                    activityClassName = "com.example.app.MainActivity",
                    action = "test.OPEN",
                    reason = "implicit",
                    processSlot = processSlot,
                    launchMode = "singleTop",
                    taskAffinity = "com.example.app:inst-001",
                    proxyActivityClassName = proxyActivityClassName,
                    launchCapabilityToken = "capability-main",
                    runtimeEpoch = 42L,
                    engineSessionId = "engine-session-42"
                )
            ),
            message = "implicit_activity_route_planned"
        )

        val result = coordinator.remap(sourceIntent, plan)
        val record = manager.list().single()

        assertTrue(result is VirtualContextWrapper.StartActivityMappingResult.Remapped)
        assertEquals(proxyIntent, result.proxyIntent)
        assertEquals("inst-001", record.instanceId)
        assertEquals("com.example.app.MainActivity", record.guestActivityClassName)
        assertEquals("singleTop", record.launchMode)
        assertEquals("com.example.app:inst-001", record.taskAffinity)
        assertTrue(record.proxyActivityClassName in EngineProxyActivitySlots.classNames(hostPackageName))
    }

    @Test
    fun `engine activity launch coordinator rejects process slot mismatch`() {
        val manager = VirtualActivityRecordManager()
        val coordinator = EngineActivityLaunchCoordinator(
            hostContext = mockk(relaxed = true) {
                every { packageName } returns "com.multiapp.app"
            },
            processSlot = "com.multiapp.app:v1",
            activityRecordManager = manager,
            allocationProvider = mockk(relaxed = true),
            proxyIntentFactory = { _, _, _ -> mockk(relaxed = true) }
        )
        val plan = VirtualActivityDispatchPlan(
            instanceId = "inst-001",
            verdict = EngineResultStatus.PARTIAL,
            targets = listOf(
                VirtualActivityDispatchTarget(
                    instanceId = "inst-001",
                    originPackageName = "com.example.app",
                    virtualPackageName = "com.multiapp.virtual.inst-001",
                    activityClassName = "com.example.app.MainActivity",
                    action = null,
                    reason = "explicit",
                    processSlot = "com.multiapp.app:v2",
                    proxyActivityClassName = "com.multiapp.app.container.ProxyActivity2",
                    launchCapabilityToken = "capability-process-mismatch",
                    runtimeEpoch = 42L,
                    engineSessionId = "engine-session-42"
                )
            ),
            message = "explicit_activity_route_planned"
        )

        val result = coordinator.remap(mockk(relaxed = true), plan)

        assertTrue(result is VirtualContextWrapper.StartActivityMappingResult.Blocked)
        assertTrue(result.reason.contains("engine_activity_process_slot_mismatch"))
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `engine activity launch coordinator rolls back failed batch`() {
        val processSlot = "com.multiapp.app:v1"
        val manager = VirtualActivityRecordManager()
        val allocationProvider = RecordingAllocationProvider()
        var proxyIntentCount = 0
        val coordinator = EngineActivityLaunchCoordinator(
            hostContext = mockk(relaxed = true) {
                every { packageName } returns "com.multiapp.app"
            },
            processSlot = processSlot,
            activityRecordManager = manager,
            allocationProvider = allocationProvider,
            proxyIntentFactory = { _, _, _ ->
                proxyIntentCount += 1
                if (proxyIntentCount == 2) error("proxy_intent_failure")
                mockk(relaxed = true)
            }
        )
        val firstIntent = mockk<Intent>(relaxed = true) { every { flags } returns 0 }
        val secondIntent = mockk<Intent>(relaxed = true) { every { flags } returns 0 }

        val results = coordinator.remapBatch(
            listOf(
                firstIntent to activityPlan(
                    processSlot = processSlot,
                    activityClassName = "com.example.app.FirstActivity",
                    taskAffinity = "com.example.app:first"
                ),
                secondIntent to activityPlan(
                    processSlot = processSlot,
                    activityClassName = "com.example.app.SecondActivity",
                    taskAffinity = "com.example.app:second",
                    launchMode = "singleTop"
                )
            )
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it is VirtualContextWrapper.StartActivityMappingResult.Blocked })
        assertTrue(
            results.filterIsInstance<VirtualContextWrapper.StartActivityMappingResult.Blocked>()
                .all { it.reason.contains("engine_activity_batch_rolled_back") }
        )
        assertTrue(manager.list().isEmpty())
        assertTrue(manager.listTasks().isEmpty())
        assertEquals(
            listOf("capability-SecondActivity", "capability-FirstActivity"),
            allocationProvider.releasedCapabilityTokens
        )
    }

    @Test
    fun `proxy slots are exposed through engine facade`() {
        val hostPackageName = "com.multiapp.app"

        val classNames = EngineProxyActivitySlots.classNames(hostPackageName)
        val launchModes = EngineProxyActivitySlots.launchModeByClassName(hostPackageName)

        assertEquals(72, classNames.size)
        assertEquals("com.multiapp.app.container.ProxyActivity0", classNames.first())
        assertEquals(null, launchModes["com.multiapp.app.container.ProxyActivity0"])
        assertEquals("singleTop", launchModes["com.multiapp.app.container.ProxyActivitySingleTop0"])
        assertEquals("singleTask", launchModes["com.multiapp.app.container.ProxyActivitySingleTask0"])
    }

    private fun activityPlan(
        processSlot: String,
        activityClassName: String,
        taskAffinity: String,
        launchMode: String? = null
    ): VirtualActivityDispatchPlan = VirtualActivityDispatchPlan(
        instanceId = "inst-001",
        verdict = EngineResultStatus.PARTIAL,
        targets = listOf(
            VirtualActivityDispatchTarget(
                instanceId = "inst-001",
                originPackageName = "com.example.app",
                virtualPackageName = "com.multiapp.virtual.inst-001",
                activityClassName = activityClassName,
                action = null,
                reason = "explicit",
                processSlot = processSlot,
                launchMode = launchMode,
                taskAffinity = taskAffinity,
                proxyActivityClassName = EngineProxyActivitySlots.classNamesForProcessSlot(
                    hostPackageName = "com.multiapp.app",
                    processSlot = processSlot,
                    launchMode = launchMode
                ).first(),
                launchCapabilityToken = "capability-${activityClassName.substringAfterLast('.')}",
                runtimeEpoch = 42L,
                engineSessionId = "engine-session-42"
            )
        ),
        message = "explicit_activity_route_planned"
    )

    private class RecordingAllocationProvider : VirtualActivityLaunchAllocationProvider {
        val releasedCapabilityTokens = mutableListOf<String>()

        override fun allocate(
            request: VirtualActivityLaunchAllocationRequest
        ): VirtualActivityLaunchAllocation = error("coordinator must consume the authoritative plan allocation")

        override fun release(allocation: VirtualActivityLaunchAllocation): Boolean {
            releasedCapabilityTokens += allocation.launchIdentity?.capabilityToken.orEmpty()
            return true
        }
    }

    @Test
    fun `proxy observation recovers missing record from scalar extras`() {
        val manager = VirtualActivityRecordManager()
        val records = EngineProxyActivityRecords(manager)
        val proxyIntent = proxyIntent(
            launchMode = "singleTop",
            taskAffinity = "com.example.task",
            flagsValue = Intent.FLAG_ACTIVITY_NEW_TASK
        )

        val observation = records.observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivitySingleTop0",
                proxyIntent = proxyIntent,
                instanceId = "inst-001",
                token = "token-001",
                guestActivityClassName = "com.example.app.MainActivity",
                originPackageName = "com.example.app"
            )
        )

        val recoveredRecord = assertNotNull(manager.resolve("token-001"))
        assertFalse(observation.recordFound)
        assertTrue(observation.recordRecovered)
        assertEquals("singleTop", recoveredRecord.launchMode)
        assertEquals("com.example.task", recoveredRecord.taskAffinity)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, recoveredRecord.intentFlags)
        assertEquals(recoveredRecord.taskId, observation.taskId)
        assertEquals("com.example.task", observation.taskAffinity)
        assertEquals("singleTop", observation.launchMode)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, observation.intentFlags)
    }

    @Test
    fun `proxy observation does not recover incomplete identity`() {
        val manager = VirtualActivityRecordManager()
        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(),
                instanceId = "",
                token = "token-001",
                guestActivityClassName = "com.example.app.MainActivity",
                originPackageName = "com.example.app"
            )
        )

        assertFalse(observation.recordFound)
        assertFalse(observation.recordRecovered)
        assertEquals(null, manager.resolve("token-001"))
    }

    @Test
    fun `proxy observation rejects existing token with mismatched owner`() {
        val manager = VirtualActivityRecordManager()
        manager.register(
            activityRecord(
                token = "token-001",
                instanceId = "inst-001",
                originPackageName = "com.example.app",
                guestActivityClassName = "com.example.app.MainActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0"
            )
        )

        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(),
                instanceId = "inst-evil",
                token = "token-001",
                guestActivityClassName = "com.evil.app.MainActivity",
                originPackageName = "com.evil.app"
            )
        )

        assertFalse(observation.recordFound)
        assertFalse(observation.recordRecovered)
        assertEquals("inst-001", manager.resolve("token-001")?.instanceId)
    }

    @Test
    fun `proxy observation does not recover forged token over different slot owner`() {
        val manager = VirtualActivityRecordManager()
        manager.register(
            activityRecord(
                token = "token-001",
                instanceId = "inst-001",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                taskAffinity = "com.example.app:inst-001"
            )
        )

        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(token = "token-forged", taskAffinity = "com.evil.app:inst-evil"),
                instanceId = "inst-evil",
                token = "token-forged",
                guestActivityClassName = "com.evil.app.MainActivity",
                originPackageName = "com.evil.app"
            )
        )

        assertFalse(observation.recordFound)
        assertFalse(observation.recordRecovered)
        assertEquals("token-001", manager.resolveByProxy("com.multiapp.app.container.ProxyActivity0")?.token)
        assertEquals(null, manager.resolve("token-forged"))
    }

    @Test
    fun `proxy observation recovers same slot owner with different activity token`() {
        val manager = VirtualActivityRecordManager()
        manager.register(
            activityRecord(
                token = "token-001",
                guestActivityClassName = "com.example.app.RootActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                taskAffinity = "com.example.app:inst-001"
            )
        )

        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(token = "token-detail", taskAffinity = "com.example.app:inst-001"),
                instanceId = "inst-001",
                token = "token-detail",
                guestActivityClassName = "com.example.app.DetailActivity",
                originPackageName = "com.example.app"
            )
        )

        assertFalse(observation.recordFound)
        assertTrue(observation.recordRecovered)
        assertEquals("token-detail", manager.resolve("token-detail")?.token)
    }

    private fun activityRecord(
        token: String,
        instanceId: String = "inst-001",
        originPackageName: String = "com.example.app",
        guestActivityClassName: String = "com.example.app.MainActivity",
        proxyActivityClassName: String = "com.multiapp.app.container.ProxyActivity0",
        launchMode: String? = null,
        taskAffinity: String? = null
    ): VirtualActivityRecord = VirtualActivityRecord(
        token = token,
        instanceId = instanceId,
        originPackageName = originPackageName,
        guestActivityClassName = guestActivityClassName,
        proxyActivityClassName = proxyActivityClassName,
        launchMode = launchMode,
        taskAffinity = taskAffinity
    )

    private fun proxyIntent(
        launchMode: String? = null,
        taskAffinity: String? = null,
        flagsValue: Int = 0,
        token: String = "token-001"
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.guestActivityLaunchMode") } returns launchMode
        every { getStringExtra("multiapp.guestTaskAffinity") } returns taskAffinity
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
        every { flags } returns flagsValue
    }
}

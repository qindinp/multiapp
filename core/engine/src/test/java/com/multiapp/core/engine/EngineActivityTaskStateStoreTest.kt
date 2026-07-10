package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class EngineActivityTaskStateStoreTest {

    @Test
    fun `file backed task state restores task activity pending intent and result`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_activity_task_state.properties")
        val task = VirtualTaskRecord(
            taskId = 8,
            affinity = "com.test.minimal:inst-001",
            activities = listOf(
                activityRecord(
                    token = "token-root",
                    activityId = "activity-root",
                    taskId = 8,
                    taskAffinity = "com.test.minimal:inst-001",
                    pendingNewIntents = listOf(
                        VirtualActivityPendingNewIntent(
                            eventId = 9L,
                            sourceToken = "token-relaunch",
                            intentFlags = 17,
                            dataIntent = VirtualIntentSnapshot(
                                flags = 3,
                                action = "test.ACTION",
                                dataUri = "content://test/item/1",
                                categories = setOf("test.CATEGORY"),
                                extras = mapOf("key" to "value")
                            ),
                            createdAtMs = 3000L
                        )
                    ),
                    result = VirtualActivityResult(
                        resultCode = 200,
                        dataIntent = VirtualIntentSnapshot(action = "result.ACTION"),
                        updatedAtMs = 4000L
                    ),
                    resultToToken = "token-parent",
                    resultRequestCode = 42
                )
            ),
            createdAtMs = 2000L
        )

        FileBackedEngineActivityTaskStateStore(file).save(EngineActivityTaskStateSnapshot(listOf(task)))
        val restored = FileBackedEngineActivityTaskStateStore(file).load()

        val restoredTask = restored.tasks.single()
        val restoredActivity = restoredTask.activities.single()
        assertEquals(1, restored.activityCount)
        assertEquals(8, restoredTask.taskId)
        assertEquals("com.test.minimal:inst-001", restoredTask.affinity)
        assertEquals("token-root", restoredActivity.token)
        assertEquals(VirtualActivityState.RESUMED, restoredActivity.state)
        assertEquals("token-relaunch", restoredActivity.pendingNewIntents.single().sourceToken)
        assertEquals("content://test/item/1", restoredActivity.pendingNewIntents.single().dataIntent?.dataUri)
        assertEquals(mapOf("key" to "value"), restoredActivity.pendingNewIntents.single().dataIntent?.extras)
        assertEquals("token-parent", restoredActivity.resultToToken)
        assertEquals(42, restoredActivity.resultRequestCode)
        assertEquals(200, restoredActivity.result?.resultCode)
        assertEquals("result.ACTION", restoredActivity.result?.dataIntent?.action)
    }

    @Test
    fun `file backed task state merges instances without overwriting siblings`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_activity_task_state.properties")
        val firstStore = FileBackedEngineActivityTaskStateStore(file)
        val secondStore = FileBackedEngineActivityTaskStateStore(file)

        firstStore.mergeInstance(
            "inst-a",
            EngineActivityTaskStateSnapshot(
                listOf(
                    VirtualTaskRecord(
                        taskId = 1,
                        affinity = "com.test.minimal:inst-a",
                        activities = listOf(activityRecord(token = "token-a", instanceId = "inst-a"))
                    )
                )
            )
        )
        secondStore.mergeInstance(
            "inst-b",
            EngineActivityTaskStateSnapshot(
                listOf(
                    VirtualTaskRecord(
                        taskId = 2,
                        affinity = "com.test.minimal:inst-b",
                        activities = listOf(activityRecord(token = "token-b", instanceId = "inst-b"))
                    )
                )
            )
        )
        firstStore.mergeInstance(
            "inst-a",
            EngineActivityTaskStateSnapshot(
                listOf(
                    VirtualTaskRecord(
                        taskId = 3,
                        affinity = "com.test.minimal:inst-a",
                        activities = listOf(activityRecord(token = "token-a-new", instanceId = "inst-a"))
                    )
                )
            )
        )

        val activities = secondStore.load().tasks.flatMap { it.activities }

        assertEquals(setOf("token-a-new", "token-b"), activities.map { it.token }.toSet())
        assertNull(activities.singleOrNull { it.token == "token-a" })
        assertEquals("inst-b", activities.single { it.token == "token-b" }.instanceId)
    }

    @Test
    fun `engine task facade persists and restores manager records`() {
        val store = InMemoryEngineActivityTaskStateStore()
        val sourceManager = VirtualActivityRecordManager()
        sourceManager.registerLaunch(
            activityRecord(
                token = "token-root",
                activityId = "activity-root",
                proxyActivityClassName = "ProxyActivity0"
            ),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK
        )
        sourceManager.registerLaunch(
            activityRecord(
                token = "token-detail",
                activityId = "activity-detail",
                guestActivityClassName = "com.test.minimal.DetailActivity",
                proxyActivityClassName = "ProxyActivity1"
            )
        )

        val persisted = EngineActivityTaskRecords(sourceManager, store).persist()
        val restoredManager = VirtualActivityRecordManager()
        val restoredCount = EngineActivityTaskRecords(restoredManager, store).restorePersisted()

        assertEquals(2, persisted.activityCount)
        assertEquals(2, restoredCount)
        assertEquals("token-root", restoredManager.resolve("token-root")?.token)
        assertEquals("token-detail", restoredManager.resolveByActivityId("activity-detail")?.token)
        assertEquals("token-detail", restoredManager.resolveByProxy("ProxyActivity1")?.token)
    }

    @Test
    fun `engine task facade restores persisted state only when manager is empty`() {
        val store = InMemoryEngineActivityTaskStateStore()
        val persistedManager = VirtualActivityRecordManager()
        persistedManager.registerLaunch(activityRecord(token = "token-persisted", proxyActivityClassName = "ProxyActivity0"))
        EngineActivityTaskRecords(persistedManager, store).persist()

        val hotManager = VirtualActivityRecordManager()
        hotManager.registerLaunch(activityRecord(token = "token-hot", proxyActivityClassName = "ProxyActivity1"))
        val skippedCount = EngineActivityTaskRecords(hotManager, store).restorePersistedIfEmpty()

        val emptyManager = VirtualActivityRecordManager()
        val restoredCount = EngineActivityTaskRecords(emptyManager, store).restorePersistedIfEmpty()

        assertEquals(0, skippedCount)
        assertEquals("token-hot", hotManager.resolve("token-hot")?.token)
        assertNull(hotManager.resolve("token-persisted"))
        assertEquals(1, restoredCount)
        assertEquals("token-persisted", emptyManager.resolve("token-persisted")?.token)
    }

    @Test
    fun `engine task facade persists lifecycle state and removes finished records from snapshot`() {
        val store = InMemoryEngineActivityTaskStateStore()
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(activityRecord(token = "token-root", proxyActivityClassName = "ProxyActivity0"))
        manager.registerLaunch(
            activityRecord(
                token = "token-detail",
                guestActivityClassName = "com.test.minimal.DetailActivity",
                proxyActivityClassName = "ProxyActivity1"
            )
        )
        val records = EngineActivityTaskRecords(manager, store)

        records.markState("token-root", VirtualActivityState.STOPPED)
        records.finish("token-detail")

        val restoredManager = VirtualActivityRecordManager()
        EngineActivityTaskRecords(restoredManager, store).restorePersisted()

        assertEquals(VirtualActivityState.STOPPED, restoredManager.resolve("token-root")?.state)
        assertNull(restoredManager.resolve("token-detail"))
        assertEquals(listOf("token-root"), restoredManager.listTasks().single().activities.map { it.token })
    }

    @Test
    fun `engine task controller routes lifecycle operations through activity service`() {
        val store = InMemoryEngineActivityTaskStateStore()
        val manager = VirtualActivityRecordManager()
        val registry = EngineRuntimeRegistry()
        registry.register(runtime(instanceId = "inst-001"))
        val systemServer = DefaultVirtualSystemServer(
            registry = registry,
            activityTaskStateStore = store,
            activityRecordManager = manager
        )
        val controller = EngineActivityTaskController(
            activityService = systemServer.activityService,
            taskRecords = EngineActivityTaskRecords(manager, store)
        )
        manager.registerLaunch(activityRecord(token = "token-root", instanceId = "inst-001"))
        manager.registerLaunch(
            activityRecord(
                token = "token-other",
                instanceId = "inst-other",
                proxyActivityClassName = "ProxyActivity1"
            )
        )
        store.mergeInstance(
            "inst-other",
            EngineActivityTaskStateSnapshot(manager.exportTasks())
        )
        controller.persist("inst-001")

        val marked = controller.markState("inst-001", "token-root", VirtualActivityState.STOPPED)
        val rootStateAfterMark = manager.resolve("token-root")?.state
        val mismatch = controller.markState("inst-001", "token-other", VirtualActivityState.STOPPED)
        val finished = controller.finish("inst-001", "token-root")
        val persisted = store.load().tasks.flatMap { it.activities }

        assertEquals("PERSISTED", marked.status)
        assertEquals(VirtualActivityState.STOPPED, rootStateAfterMark)
        assertEquals("FAIL", mismatch.status)
        assertEquals("activity_record_instance_mismatch:token-other", mismatch.detail)
        assertEquals(VirtualActivityState.RESUMED, manager.resolve("token-other")?.state)
        assertEquals("PERSISTED", finished.status)
        assertEquals(1, finished.activityCount)
        assertNull(persisted.singleOrNull { it.token == "token-root" })
        assertEquals("token-other", persisted.single().token)
    }

    @Test
    fun `file backed task state clear removes persisted snapshot`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_activity_task_state.properties")
        val store = FileBackedEngineActivityTaskStateStore(file)

        store.save(EngineActivityTaskStateSnapshot(listOf(VirtualTaskRecord(1, "task", listOf(activityRecord())))))
        store.clear()

        assertNull(store.load().tasks.singleOrNull())
    }

    private fun activityRecord(
        token: String = "token-1",
        activityId: String = token,
        instanceId: String = "inst-001",
        originPackageName: String = "com.test.minimal",
        guestActivityClassName: String = "com.test.minimal.MainActivity",
        proxyActivityClassName: String = "ProxyActivity0",
        launchMode: String? = null,
        taskId: Int = 0,
        intentFlags: Int = 0,
        state: VirtualActivityState = VirtualActivityState.RESUMED,
        taskAffinity: String? = null,
        pendingNewIntents: List<VirtualActivityPendingNewIntent> = emptyList(),
        resultToToken: String? = null,
        resultRequestCode: Int = -1,
        result: VirtualActivityResult? = null
    ): VirtualActivityRecord = VirtualActivityRecord(
        token = token,
        activityId = activityId,
        instanceId = instanceId,
        originPackageName = originPackageName,
        guestActivityClassName = guestActivityClassName,
        proxyActivityClassName = proxyActivityClassName,
        launchMode = launchMode,
        createdAtMs = 1000L,
        taskId = taskId,
        intentFlags = intentFlags,
        state = state,
        taskAffinity = taskAffinity,
        pendingNewIntents = pendingNewIntents,
        resultToToken = resultToToken,
        resultRequestCode = resultRequestCode,
        result = result
    )

    private fun runtime(instanceId: String): VirtualInstanceRuntime = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = "com.test.minimal",
            virtualPackageName = "com.multiapp.virtual.$instanceId",
            applicationLabel = "Minimal",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/minimal.apk",
            dataDir = "build/tmp/$instanceId"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-$instanceId"
    )
}

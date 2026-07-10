package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualActivityRecordManagerTest {

    @Test
    fun `register stores record by token proxy class and activityId`() {
        val manager = VirtualActivityRecordManager()
        val record = record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0")

        manager.register(record)

        assertSame(record, manager.resolve("token-1"))
        assertSame(record, manager.resolveByProxy("ProxyActivity0"))
        assertSame(record, manager.resolveByActivityId("activity-1"))
    }

    @Test
    fun `consume removes token proxy mapping and activityId mapping`() {
        val manager = VirtualActivityRecordManager()
        val record = record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0")
        manager.register(record)

        assertSame(record, manager.consume("token-1"))
        assertNull(manager.resolve("token-1"))
        assertNull(manager.resolveByProxy("ProxyActivity0"))
        assertNull(manager.resolveByActivityId("activity-1"))
    }

    @Test
    fun `state snapshot restores records and tasks after partial mutation`() {
        val manager = VirtualActivityRecordManager()
        val first = manager.registerLaunch(
            record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0")
        ).activity
        val snapshot = manager.snapshotState()
        manager.registerLaunch(
            record(token = "token-2", activityId = "activity-2", proxyActivityClassName = "ProxyActivity1")
        )

        manager.restoreState(snapshot)

        assertEquals(listOf(first.token), manager.list().map { it.token })
        assertEquals(listOf(first.token), manager.listTasks().single().activities.map { it.token })
        assertNull(manager.resolve("token-2"))
        assertEquals(first.token, manager.lastLaunchResult()?.activity?.token)
    }

    @Test
    fun `clearByInstance removes only matching records`() {
        val manager = VirtualActivityRecordManager()
        val first = record(token = "token-1", instanceId = "inst-001", proxyActivityClassName = "ProxyActivity0")
        val second = record(token = "token-2", instanceId = "inst-002", proxyActivityClassName = "ProxyActivity1")
        manager.register(first)
        manager.register(second)

        val removed = manager.clearByInstance("inst-001")

        assertEquals(1, removed)
        assertNull(manager.resolve("token-1"))
        assertSame(second, manager.resolve("token-2"))
    }

    @Test
    fun `pruneStaleProxyRecords removes records whose proxy task is no longer live`() {
        val manager = VirtualActivityRecordManager()
        val live = manager.registerLaunch(
            record(token = "token-live", instanceId = "inst-001", proxyActivityClassName = "ProxyActivity0")
        ).activity
        val stale = manager.registerLaunch(
            record(token = "token-stale", instanceId = "inst-002", proxyActivityClassName = "ProxyActivity1")
        ).activity

        val removed = manager.pruneStaleProxyRecords(
            knownProxyActivityClassNames = setOf("ProxyActivity0", "ProxyActivity1"),
            liveProxyActivityClassNames = setOf("ProxyActivity0")
        )

        assertEquals(1, removed)
        assertEquals(live.token, manager.resolve(live.token)?.token)
        assertNull(manager.resolve(stale.token))
        assertNull(manager.resolveByProxy("ProxyActivity1"))
        assertEquals(listOf(live.token), manager.listTasks().single().activities.map { it.token })
    }

    @Test
    fun `pruneStaleProxyRecords removes records for proxy classes no longer declared`() {
        val manager = VirtualActivityRecordManager()
        val record = manager.registerLaunch(
            record(token = "token-old", proxyActivityClassName = "OldProxyActivity")
        ).activity

        val removed = manager.pruneStaleProxyRecords(
            knownProxyActivityClassNames = setOf("ProxyActivity0"),
            liveProxyActivityClassNames = setOf("OldProxyActivity")
        )

        assertEquals(1, removed)
        assertNull(manager.resolve(record.token))
        assertNull(manager.lastLaunchResult())
        assertEquals(emptyList(), manager.listTasks())
    }

    @Test
    fun `registerLaunch stores stack decision fields`() {
        val manager = VirtualActivityRecordManager()
        val result = manager.registerLaunch(
            record(token = "token-1", proxyActivityClassName = "ProxyActivity0"),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK
        )

        assertEquals(1, result.activity.taskId)
        assertEquals(VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK, result.activity.intentFlags)
        assertEquals("com.test.minimal:inst-001", result.activity.taskAffinity)
        assertSame(result, manager.lastLaunchResult())
        assertSame(result.activity, manager.resolve("token-1"))
    }

    @Test
    fun `singleTop relaunch records pending new intent on reused record`() {
        val manager = VirtualActivityRecordManager()
        val first = manager.registerLaunch(
            record(token = "token-1", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity0")
        ).activity
        val dataIntent = VirtualIntentSnapshot(action = "test.ACTION", dataUri = "content://test/item/1")

        val result = manager.registerLaunch(
            record(token = "token-2", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity1"),
            intentFlags = 3,
            dataIntent = dataIntent
        )

        assertTrue(result.reused)
        assertEquals(first.token, result.activity.token)
        assertEquals("token-2", result.pendingNewIntent?.sourceToken)
        assertEquals(3, result.pendingNewIntent?.intentFlags)
        assertEquals(dataIntent, result.pendingNewIntent?.dataIntent)
        assertEquals(listOfNotNull(result.pendingNewIntent), manager.resolve(first.token)?.pendingNewIntents)
    }

    @Test
    fun `singleTask relaunch reuses existing record and marks cleared records finished`() {
        val manager = VirtualActivityRecordManager()
        val root = manager.registerLaunch(
            record(token = "token-root", activity = "RootActivity", launchMode = "singleTask", proxyActivityClassName = "ProxyActivity0")
        ).activity
        val detail = manager.registerLaunch(
            record(token = "token-detail", activity = "DetailActivity", proxyActivityClassName = "ProxyActivity1")
        ).activity

        val result = manager.registerLaunch(
            record(token = "token-new-root", activity = "RootActivity", launchMode = "singleTask", proxyActivityClassName = "ProxyActivity2")
        )

        assertTrue(result.reused)
        assertEquals(root.token, result.activity.token)
        assertEquals(listOf(detail.token), result.clearedActivities.map { it.token })
        assertEquals(root.token, manager.resolve(root.token)?.token)
        assertEquals(VirtualActivityState.FINISHED, manager.resolve(detail.token)?.state)
        assertEquals(listOf(root.token), manager.listTasks().single().activities.map { it.token })
        assertEquals("token-new-root", result.pendingNewIntent?.sourceToken)
    }

    @Test
    fun `clearTop relaunch marks cleared records finished and records pending new intent`() {
        val manager = VirtualActivityRecordManager()
        val root = manager.registerLaunch(record(token = "token-root", activity = "RootActivity", proxyActivityClassName = "ProxyActivity0")).activity
        val detail = manager.registerLaunch(record(token = "token-detail", activity = "DetailActivity", proxyActivityClassName = "ProxyActivity1")).activity
        val settings = manager.registerLaunch(record(token = "token-settings", activity = "SettingsActivity", proxyActivityClassName = "ProxyActivity2")).activity

        val result = manager.registerLaunch(
            record(token = "token-new-detail", activity = "DetailActivity", proxyActivityClassName = "ProxyActivity3"),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_CLEAR_TOP
        )

        assertTrue(result.reused)
        assertEquals(detail.token, result.activity.token)
        assertEquals(listOf(settings.token), result.clearedActivities.map { it.token })
        assertEquals(root.token, manager.resolve(root.token)?.token)
        assertEquals(detail.token, manager.resolve(detail.token)?.token)
        assertEquals(VirtualActivityState.FINISHED, manager.resolve(settings.token)?.state)
        assertEquals(listOf(root.token, detail.token), manager.listTasks().single().activities.map { it.token })
        assertEquals("token-new-detail", result.pendingNewIntent?.sourceToken)
    }

    @Test
    fun `A to B back marks B finished and leaves A active`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-a", activity = "MainActivity", proxyActivityClassName = "ProxyActivity0"))
        manager.registerLaunch(record(token = "token-b", activity = "SecondActivity", proxyActivityClassName = "ProxyActivity1"))

        val finished = manager.finish("token-b")

        assertEquals(VirtualActivityState.FINISHED, finished?.state)
        assertEquals(VirtualActivityState.FINISHED, manager.resolve("token-b")?.state)
        assertEquals("token-a", manager.resolve("token-a")?.token)
        assertNull(manager.resolveByProxy("ProxyActivity1"))
        assertEquals(listOf("token-a"), manager.listTasks().single().activities.map { it.token })
    }

    @Test
    fun `finish missing token preserves active stack`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-a", activity = "MainActivity", proxyActivityClassName = "ProxyActivity0"))
        manager.registerLaunch(record(token = "token-b", activity = "SecondActivity", proxyActivityClassName = "ProxyActivity1"))

        val finished = manager.finish("missing-token")

        assertNull(finished)
        assertEquals(listOf("token-a", "token-b"), manager.listTasks().single().activities.map { it.token })
        assertEquals("token-a", manager.resolve("token-a")?.token)
        assertEquals("token-b", manager.resolve("token-b")?.token)
    }

    @Test
    fun `finish marks record finished removes proxy mapping and keeps task queryable`() {
        val manager = VirtualActivityRecordManager()
        val launched = manager.registerLaunch(record(token = "token-1", proxyActivityClassName = "ProxyActivity0")).activity

        val finished = manager.finish(launched.token)

        assertEquals(VirtualActivityState.FINISHED, finished?.state)
        assertEquals(VirtualActivityState.FINISHED, manager.resolve(launched.token)?.state)
        assertNull(manager.resolveByProxy("ProxyActivity0"))
        assertEquals(emptyList(), manager.listTasks().single().activities)
    }

    @Test
    fun `finishByActivityId marks matching record finished`() {
        val manager = VirtualActivityRecordManager()
        val launched = manager.registerLaunch(
            record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0")
        ).activity

        val finished = manager.finishByActivityId(launched.activityId)

        assertEquals(VirtualActivityState.FINISHED, finished?.state)
        assertEquals(launched.token, manager.resolveByActivityId("activity-1")?.token)
        assertEquals(VirtualActivityState.FINISHED, manager.resolveByActivityId("activity-1")?.state)
    }

    @Test
    fun `setResult stores result by token and activityId without Android Intent`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0"))
        val dataIntent = VirtualIntentSnapshot(action = "result.ACTION", extras = mapOf("ok" to "true"))

        val byToken = manager.setResult("token-1", resultCode = 100, dataIntent = dataIntent)
        val byActivityId = manager.setResultByActivityId("activity-1", resultCode = 200)

        assertEquals(100, byToken?.result?.resultCode)
        assertEquals(dataIntent, byToken?.result?.dataIntent)
        assertEquals(200, byActivityId?.result?.resultCode)
        assertEquals(200, manager.resolve("token-1")?.result?.resultCode)
    }

    @Test
    fun `resume fallback only consumes result when framework dispatch was not invoked`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0"))
        val dataIntent = VirtualIntentSnapshot(action = "result.ACTION", extras = mapOf("ok" to "true"))
        manager.setResult(
            token = "token-1",
            resultCode = 100,
            dataIntent = dataIntent,
            requestCode = 42
        )

        val consumed = manager.consumeResultForResumeFallback("token-1")

        assertEquals(100, consumed?.resultCode)
        assertEquals(42, consumed?.requestCode)
        assertEquals(dataIntent, consumed?.dataIntent)
        assertNull(manager.resolve("token-1")?.result)

        manager.setResult(
            token = "token-1",
            resultCode = 200,
            requestCode = 43
        )
        manager.markResultDispatchState(
            token = "token-1",
            frameworkDispatchAttempted = true,
            frameworkDispatchInvoked = true
        )

        assertNull(manager.consumeResultForResumeFallback("token-1"))
        assertEquals(200, manager.resolve("token-1")?.result?.resultCode)
        assertEquals(true, manager.resolve("token-1")?.result?.frameworkDispatchInvoked)
    }

    @Test
    fun `consumePendingNewIntent consumes oldest event and updates record`() {
        val manager = VirtualActivityRecordManager()
        val first = manager.registerLaunch(
            record(token = "token-1", activityId = "activity-1", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity0")
        ).activity
        val second = manager.registerLaunch(
            record(token = "token-2", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity1"),
            intentFlags = 2
        )
        val third = manager.registerLaunch(
            record(token = "token-3", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity2"),
            intentFlags = 3
        )

        val consumedByToken = manager.consumePendingNewIntent(first.token)
        val consumedByActivityId = manager.consumePendingNewIntentByActivityId(first.activityId)

        assertEquals(second.pendingNewIntent, consumedByToken)
        assertEquals(third.pendingNewIntent, consumedByActivityId)
        assertEquals(emptyList(), manager.resolve(first.token)?.pendingNewIntents)
        assertEquals(emptyList(), manager.listTasks().single().activities.single().pendingNewIntents)
        assertNull(manager.consumePendingNewIntent(first.token))
        assertNull(manager.consumePendingNewIntent("missing-token"))
    }

    @Test
    fun `consumePendingNewIntent returns null for finished diagnostic record`() {
        val manager = VirtualActivityRecordManager()
        val first = manager.registerLaunch(
            record(token = "token-1", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity0")
        ).activity
        manager.registerLaunch(
            record(token = "token-2", activity = "MainActivity", launchMode = "singleTop", proxyActivityClassName = "ProxyActivity1")
        )

        manager.finish(first.token)

        assertEquals(VirtualActivityState.FINISHED, manager.resolve(first.token)?.state)
        assertNull(manager.consumePendingNewIntent(first.token))
    }

    @Test
    fun `consumeResult consumes and clears active result by token or activityId`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0"))
        val dataIntent = VirtualIntentSnapshot(action = "result.ACTION", extras = mapOf("ok" to "true"))
        manager.setResult("token-1", resultCode = 100, dataIntent = dataIntent)

        val consumed = manager.consumeResultByActivityId("activity-1")

        assertEquals(100, consumed?.resultCode)
        assertEquals(dataIntent, consumed?.dataIntent)
        assertNull(manager.resolve("token-1")?.result)
        assertNull(manager.listTasks().single().activities.single().result)
        assertNull(manager.consumeResult("token-1"))
        assertNull(manager.consumeResult("missing-token"))
    }

    @Test
    fun `consumeResult can clear finished diagnostic record`() {
        val manager = VirtualActivityRecordManager()
        val launched = manager.registerLaunch(
            record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0")
        ).activity
        manager.setResult(launched.token, resultCode = 100)
        manager.finish(launched.token)

        val consumed = manager.consumeResultByActivityId(launched.activityId)

        assertEquals(100, consumed?.resultCode)
        assertEquals(VirtualActivityState.FINISHED, manager.resolve(launched.token)?.state)
        assertNull(manager.resolve(launched.token)?.result)
        assertNull(manager.consumeResult(launched.token))
    }

    @Test
    fun `updateState keeps active task records and finish removes them from stack`() {
        val manager = VirtualActivityRecordManager()
        val launched = manager.registerLaunch(
            record(token = "token-1", activityId = "activity-1", proxyActivityClassName = "ProxyActivity0")
        ).activity

        val stopped = manager.updateState(launched.token, VirtualActivityState.STOPPED)

        assertEquals(VirtualActivityState.STOPPED, stopped?.state)
        assertEquals(VirtualActivityState.STOPPED, manager.listTasks().single().activities.single().state)

        val finished = manager.updateStateByActivityId(launched.activityId, VirtualActivityState.FINISHED)

        assertEquals(VirtualActivityState.FINISHED, finished?.state)
        assertEquals(VirtualActivityState.FINISHED, manager.resolve(launched.token)?.state)
        assertEquals(emptyList(), manager.listTasks().single().activities)
    }

    @Test
    fun `conflictingProxyOwner reports mismatched active slot owner only`() {
        val manager = VirtualActivityRecordManager()
        val owner = manager.register(
            record(
                token = "token-owner",
                proxyActivityClassName = "ProxyActivity0",
                launchMode = "singleTop",
                taskAffinity = "com.test.minimal:inst-001"
            )
        )

        assertSame(
            owner,
            manager.conflictingProxyOwner(
                record(
                    token = "token-other-instance",
                    instanceId = "inst-evil",
                    proxyActivityClassName = "ProxyActivity0",
                    launchMode = "singleTop",
                    taskAffinity = "com.test.minimal:inst-001"
                )
            )
        )
        assertNull(
            manager.conflictingProxyOwner(
                record(
                    token = "token-other-activity",
                    activity = "DetailActivity",
                    proxyActivityClassName = "ProxyActivity0",
                    launchMode = "singleTop",
                    taskAffinity = "com.test.minimal:inst-001"
                )
            )
        )
        assertNull(manager.conflictingProxyOwner(owner))
        assertSame(
            owner,
            manager.conflictingProxyOwner(
                record(
                    token = "token-owner",
                    proxyActivityClassName = "ProxyActivity0",
                    launchMode = "singleTop",
                    taskAffinity = "com.evil.task"
                )
            )
        )

        manager.finish("token-owner")

        assertNull(
            manager.conflictingProxyOwner(
                record(
                    token = "token-other-instance",
                    instanceId = "inst-evil",
                    proxyActivityClassName = "ProxyActivity0",
                    launchMode = "singleTop",
                    taskAffinity = "com.test.minimal:inst-001"
                )
            )
        )
    }

    @Test
    fun `finish latest same owner proxy record keeps older active slot owner mapped`() {
        val manager = VirtualActivityRecordManager()
        val older = manager.registerLaunch(
            record(token = "token-old", activity = "MainActivity", proxyActivityClassName = "ProxyActivity0")
        ).activity
        val latest = manager.registerLaunch(
            record(token = "token-latest", activity = "DetailActivity", proxyActivityClassName = "ProxyActivity0")
        ).activity

        assertEquals(latest.token, manager.resolveByProxy("ProxyActivity0")?.token)

        manager.finish(latest.token)

        assertEquals(older.token, manager.resolveByProxy("ProxyActivity0")?.token)
        assertEquals(
            older.token,
            manager.conflictingProxyOwner(
                record(token = "token-evil", instanceId = "inst-evil", proxyActivityClassName = "ProxyActivity0")
            )?.token
        )
    }

    @Test
    fun `restoreTasks rebuilds stack and lookup indexes`() {
        val original = VirtualActivityRecordManager()
        val root = original.registerLaunch(
            record(
                token = "token-root",
                activityId = "activity-root",
                activity = "MainActivity",
                proxyActivityClassName = "ProxyActivity0"
            ),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK
        ).activity
        val detail = original.registerLaunch(
            record(
                token = "token-detail",
                activityId = "activity-detail",
                activity = "DetailActivity",
                proxyActivityClassName = "ProxyActivity1"
            )
        ).activity

        val restored = VirtualActivityRecordManager()
        val restoredCount = restored.restoreTasks(original.exportTasks())

        assertEquals(2, restoredCount)
        assertEquals(listOf(root.token, detail.token), restored.listTasks().single().activities.map { it.token })
        assertEquals(root.token, restored.resolve(root.token)?.token)
        assertEquals(detail.token, restored.resolveByProxy("ProxyActivity1")?.token)
        assertEquals(detail.token, restored.resolveByActivityId("activity-detail")?.token)
        assertNull(restored.lastLaunchResult())
    }

    @Test
    fun `restoreTasks filters finished destroyed and duplicate records`() {
        val active = record(token = "token-active", activityId = "activity-active", proxyActivityClassName = "ProxyActivity0")
        val finished = record(token = "token-finished", activityId = "activity-finished", proxyActivityClassName = "ProxyActivity1")
            .copy(state = VirtualActivityState.FINISHED)
        val destroyed = record(token = "token-destroyed", activityId = "activity-destroyed", proxyActivityClassName = "ProxyActivity2")
            .copy(state = VirtualActivityState.DESTROYED)
        val duplicate = record(token = active.token, activityId = "activity-duplicate", proxyActivityClassName = "ProxyActivity3")
        val task = VirtualTaskRecord(
            taskId = 7,
            affinity = "com.test.minimal:inst-001",
            activities = listOf(active, finished, destroyed, duplicate),
            createdAtMs = 2000L
        )
        val manager = VirtualActivityRecordManager()

        val restoredCount = manager.restoreTasks(listOf(task))

        assertEquals(1, restoredCount)
        assertEquals(listOf(active.token), manager.listTasks().single().activities.map { it.token })
        assertEquals(7, manager.resolve(active.token)?.taskId)
        assertEquals("com.test.minimal:inst-001", manager.resolve(active.token)?.taskAffinity)
        assertNull(manager.resolve(finished.token))
        assertNull(manager.resolve(destroyed.token))
        assertNull(manager.resolveByProxy("ProxyActivity3"))
    }

    @Test
    fun `clearAll clears records launch evidence and stack`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-1", proxyActivityClassName = "ProxyActivity0"))

        manager.clearAll()

        assertNull(manager.resolve("token-1"))
        assertNull(manager.lastLaunchResult())
        assertEquals(emptyList(), manager.listTasks())
    }

    @Test
    fun `finish result operation records child result on source activity`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-parent", proxyActivityClassName = "ProxyActivity0"))
        manager.registerLaunch(
            record(
                token = "token-child",
                activity = "ChildActivity",
                proxyActivityClassName = "ProxyActivity1",
                resultToToken = "token-parent",
                resultRequestCode = 42
            )
        )
        val operations = ManagerBackedVirtualActivityOperations(manager)
        val dataIntent = VirtualIntentSnapshot(action = "test.RESULT")

        val result = operations.recordActivityResultForFinish(
            instanceId = "inst-001",
            token = "token-child",
            resultCode = 200,
            dataIntent = dataIntent
        )

        assertTrue(result.recorded)
        assertEquals("", result.reason)
        assertEquals("token-parent", result.sourceToken)
        assertEquals(42, result.requestCode)
        assertEquals(200, manager.resolve("token-parent")?.result?.resultCode)
        assertEquals(42, manager.resolve("token-parent")?.result?.requestCode)
        assertEquals(dataIntent, manager.resolve("token-parent")?.result?.dataIntent)
    }

    @Test
    fun `finish result operation fails closed when source token belongs to another instance`() {
        val manager = VirtualActivityRecordManager()
        manager.registerLaunch(record(token = "token-parent", instanceId = "inst-other", proxyActivityClassName = "ProxyActivity0"))
        manager.registerLaunch(
            record(
                token = "token-child",
                proxyActivityClassName = "ProxyActivity1",
                resultToToken = "token-parent",
                resultRequestCode = 42
            )
        )

        val result = ManagerBackedVirtualActivityOperations(manager).recordActivityResultForFinish(
            instanceId = "inst-001",
            token = "token-child",
            resultCode = 200
        )

        assertEquals(false, result.recorded)
        assertEquals("RESULT_TARGET_INSTANCE_MISMATCH", result.reason)
        assertNull(manager.resolve("token-parent")?.result)
    }

    private fun record(
        token: String,
        activityId: String = token,
        instanceId: String = "inst-001",
        activity: String = "MainActivity",
        launchMode: String? = null,
        taskAffinity: String? = null,
        proxyActivityClassName: String,
        resultToToken: String? = null,
        resultRequestCode: Int = -1
    ) = VirtualActivityRecord(
        token = token,
        activityId = activityId,
        instanceId = instanceId,
        originPackageName = "com.test.minimal",
        guestActivityClassName = "com.test.minimal.$activity",
        proxyActivityClassName = proxyActivityClassName,
        launchMode = launchMode,
        taskAffinity = taskAffinity,
        resultToToken = resultToToken,
        resultRequestCode = resultRequestCode,
        createdAtMs = 1000L
    )
}

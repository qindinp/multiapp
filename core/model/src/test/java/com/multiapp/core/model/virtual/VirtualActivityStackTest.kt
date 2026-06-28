package com.multiapp.core.model.virtual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualActivityStackTest {

    @Test
    fun `standard launch always creates a new activity in current task`() {
        val stack = VirtualActivityStack()

        val first = stack.launch(record(token = "token-1", activity = "MainActivity"))
        val second = stack.launch(record(token = "token-2", activity = "MainActivity"))

        assertFalse(first.reused)
        assertFalse(second.reused)
        assertEquals(first.task.taskId, second.task.taskId)
        assertNotEquals(first.activity.token, second.activity.token)
        assertEquals(listOf("token-1", "token-2"), stack.topTask()?.activities?.map { it.token })
        assertEquals(emptyList(), second.activity.pendingNewIntents)
    }

    @Test
    fun `singleTop reuses top activity with same component and records pending new intent`() {
        val stack = VirtualActivityStack()
        val first = stack.launch(record(token = "token-1", activity = "MainActivity", launchMode = "singleTop"))
        val dataIntent = VirtualIntentSnapshot(action = "test.ACTION", dataUri = "content://test/item/1")

        val second = stack.launch(
            record(token = "token-2", activity = "MainActivity", launchMode = "singleTop"),
            intentFlags = 7,
            dataIntent = dataIntent
        )

        assertTrue(second.reused)
        assertEquals(first.activity.token, second.activity.token)
        assertEquals(1, stack.topTask()?.activities?.size)
        assertEquals("token-2", second.pendingNewIntent?.sourceToken)
        assertEquals(7, second.pendingNewIntent?.intentFlags)
        assertEquals(dataIntent, second.pendingNewIntent?.dataIntent)
        assertEquals(listOf(second.pendingNewIntent), second.activity.pendingNewIntents)
    }

    @Test
    fun `singleTask reuses existing component clears activities above it and records pending new intent`() {
        val stack = VirtualActivityStack()
        val root = stack.launch(record(token = "token-root", activity = "RootActivity", launchMode = "singleTask"))
        stack.launch(record(token = "token-detail", activity = "DetailActivity"))
        stack.launch(record(token = "token-settings", activity = "SettingsActivity"))

        val relaunched = stack.launch(record(token = "token-new-root", activity = "RootActivity", launchMode = "singleTask"))

        assertTrue(relaunched.reused)
        assertEquals(root.activity.token, relaunched.activity.token)
        assertEquals(listOf("token-detail", "token-settings"), relaunched.clearedActivities.map { it.token })
        assertEquals(listOf("token-root"), stack.topTask()?.activities?.map { it.token })
        assertEquals(VirtualActivityState.FINISHED, relaunched.clearedActivities.first().state)
        assertEquals("token-new-root", relaunched.pendingNewIntent?.sourceToken)
        assertEquals(listOf(relaunched.pendingNewIntent), relaunched.activity.pendingNewIntents)
    }

    @Test
    fun `clearTop reuses existing component in selected task clears above it and records pending new intent`() {
        val stack = VirtualActivityStack()
        stack.launch(record(token = "token-root", activity = "RootActivity"))
        stack.launch(record(token = "token-detail", activity = "DetailActivity"))
        stack.launch(record(token = "token-settings", activity = "SettingsActivity"))

        val result = stack.launch(
            record(token = "token-new-detail", activity = "DetailActivity"),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_CLEAR_TOP
        )

        assertTrue(result.reused)
        assertEquals("token-detail", result.activity.token)
        assertEquals(listOf("token-settings"), result.clearedActivities.map { it.token })
        assertEquals(listOf("token-root", "token-detail"), stack.topTask()?.activities?.map { it.token })
        assertEquals(VirtualActivityStack.FLAG_ACTIVITY_CLEAR_TOP, result.activity.intentFlags)
        assertEquals(VirtualActivityState.FINISHED, result.clearedActivities.single().state)
        assertEquals("token-new-detail", result.pendingNewIntent?.sourceToken)
        assertEquals(listOf(result.pendingNewIntent), result.activity.pendingNewIntents)
    }

    @Test
    fun `finish by token marks activity finished and removes it from task while task remains queryable`() {
        val stack = VirtualActivityStack()
        val launched = stack.launch(record(token = "token-1", activity = "MainActivity"))

        val finished = stack.finishByToken(launched.activity.token)

        assertEquals(VirtualActivityState.FINISHED, finished?.state)
        assertEquals("token-1", finished?.token)
        assertNull(stack.findByToken("token-1"))
        assertEquals(emptyList(), stack.listTasks().single().activities)
    }

    @Test
    fun `finish by activityId removes matching activity`() {
        val stack = VirtualActivityStack()
        stack.launch(record(token = "token-1", activityId = "activity-1", activity = "MainActivity"))

        val finished = stack.finishByActivityId("activity-1")

        assertEquals(VirtualActivityState.FINISHED, finished?.state)
        assertEquals("activity-1", finished?.activityId)
        assertNull(stack.findByActivityId("activity-1"))
    }

    @Test
    fun `setResult stores result on active activity by token and activityId`() {
        val stack = VirtualActivityStack()
        stack.launch(record(token = "token-1", activityId = "activity-1", activity = "MainActivity"))
        val dataIntent = VirtualIntentSnapshot(action = "result.ACTION", extras = mapOf("id" to "42"))

        val byToken = stack.setResultByToken("token-1", resultCode = 10, dataIntent = dataIntent)
        val byActivityId = stack.setResultByActivityId("activity-1", resultCode = 20)

        assertEquals(10, byToken?.result?.resultCode)
        assertEquals(dataIntent, byToken?.result?.dataIntent)
        assertEquals(20, byActivityId?.result?.resultCode)
        assertEquals(20, stack.findByToken("token-1")?.result?.resultCode)
    }

    @Test
    fun `consumePendingNewIntent returns oldest pending event and removes it`() {
        val stack = VirtualActivityStack()
        val first = stack.launch(record(token = "token-1", activityId = "activity-1", activity = "MainActivity", launchMode = "singleTop"))
        val second = stack.launch(record(token = "token-2", activity = "MainActivity", launchMode = "singleTop"), intentFlags = 2)
        val third = stack.launch(record(token = "token-3", activity = "MainActivity", launchMode = "singleTop"), intentFlags = 3)

        val consumedByToken = stack.consumePendingNewIntentByToken(first.activity.token)
        val consumedByActivityId = stack.consumePendingNewIntentByActivityId(first.activity.activityId)

        assertEquals(second.pendingNewIntent, consumedByToken)
        assertEquals(third.pendingNewIntent, consumedByActivityId)
        assertEquals(emptyList(), stack.findByToken(first.activity.token)?.pendingNewIntents)
        assertNull(stack.consumePendingNewIntentByToken(first.activity.token))
        assertNull(stack.consumePendingNewIntentByToken("missing-token"))
    }

    @Test
    fun `consumePendingNewIntent returns null after record is finished`() {
        val stack = VirtualActivityStack()
        val first = stack.launch(record(token = "token-1", activity = "MainActivity", launchMode = "singleTop"))
        stack.launch(record(token = "token-2", activity = "MainActivity", launchMode = "singleTop"))

        stack.finishByToken(first.activity.token)

        assertNull(stack.consumePendingNewIntentByToken(first.activity.token))
    }

    @Test
    fun `consumeResult returns stored result and clears it`() {
        val stack = VirtualActivityStack()
        stack.launch(record(token = "token-1", activityId = "activity-1", activity = "MainActivity"))
        val dataIntent = VirtualIntentSnapshot(action = "result.ACTION", extras = mapOf("id" to "42"))
        stack.setResultByToken("token-1", resultCode = 10, dataIntent = dataIntent)

        val consumed = stack.consumeResultByActivityId("activity-1")

        assertEquals(10, consumed?.resultCode)
        assertEquals(dataIntent, consumed?.dataIntent)
        assertNull(stack.findByToken("token-1")?.result)
        assertNull(stack.consumeResultByToken("token-1"))
        assertNull(stack.consumeResultByToken("missing-token"))
    }

    @Test
    fun `consumeResult returns null after record is finished in stack`() {
        val stack = VirtualActivityStack()
        stack.launch(record(token = "token-1", activity = "MainActivity"))
        stack.setResultByToken("token-1", resultCode = 10)

        stack.finishByToken("token-1")

        assertNull(stack.consumeResultByToken("token-1"))
    }

    @Test
    fun `newTask selects existing task with matching affinity`() {
        val stack = VirtualActivityStack()
        val first = stack.launch(
            record(token = "token-a1", activity = "AActivity", affinity = "affinity-a"),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK
        )
        val second = stack.launch(
            record(token = "token-b1", activity = "BActivity", affinity = "affinity-b"),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK
        )

        val third = stack.launch(
            record(token = "token-a2", activity = "ASecondActivity", affinity = "affinity-a"),
            intentFlags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK
        )

        assertNotEquals(first.task.taskId, second.task.taskId)
        assertEquals(first.task.taskId, third.task.taskId)
        assertEquals("affinity-a", third.task.affinity)
        assertEquals(listOf("token-a1", "token-a2"), third.task.activities.map { it.token })
        assertEquals(2, stack.listTasks().size)
    }

    @Test
    fun `activity record keeps backward compatible defaults`() {
        val record = record(token = "token-1", activity = "MainActivity")

        assertEquals("token-1", record.activityId)
        assertEquals(0, record.taskId)
        assertEquals(0, record.intentFlags)
        assertEquals(VirtualActivityState.CREATED, record.state)
        assertEquals(null, record.taskAffinity)
        assertEquals(emptyList(), record.pendingNewIntents)
        assertEquals(null, record.result)
    }

    private fun record(
        token: String,
        activityId: String = token,
        activity: String,
        launchMode: String? = null,
        affinity: String? = null,
        instanceId: String = "inst-001"
    ) = VirtualActivityRecord(
        token = token,
        activityId = activityId,
        instanceId = instanceId,
        originPackageName = "com.test.minimal",
        guestActivityClassName = "com.test.minimal.$activity",
        proxyActivityClassName = "ProxyActivity0",
        launchMode = launchMode,
        createdAtMs = 1000L,
        taskAffinity = affinity
    )
}

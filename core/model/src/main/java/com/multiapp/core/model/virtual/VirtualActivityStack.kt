package com.multiapp.core.model.virtual

class VirtualActivityStack {
    private val tasks = LinkedHashMap<Int, MutableTaskRecord>()
    private var nextTaskId = 1
    private var nextEventId = 1L

    @Synchronized
    fun launch(
        record: VirtualActivityRecord,
        intentFlags: Int = record.intentFlags,
        dataIntent: VirtualIntentSnapshot? = null
    ): LaunchResult {
        val normalizedLaunchMode = ProxyActivityRegistry.normalizeLaunchMode(record.launchMode)
        val requested = record.copy(
            launchMode = normalizedLaunchMode,
            intentFlags = intentFlags,
            state = VirtualActivityState.RESUMED
        )

        if (normalizedLaunchMode == LAUNCH_MODE_SINGLE_TASK) {
            findActivity(requested)?.let { found ->
                val cleared = clearAbove(found.task, found.index)
                val pendingNewIntent = pendingNewIntentFor(requested, intentFlags, dataIntent)
                val reused = found.task.activities[found.index].copy(
                    intentFlags = intentFlags,
                    state = VirtualActivityState.RESUMED,
                    resultToToken = requested.resultToToken,
                    resultRequestCode = requested.resultRequestCode,
                    pendingNewIntents = found.task.activities[found.index].pendingNewIntents + pendingNewIntent
                )
                found.task.activities[found.index] = reused
                moveToTop(found.task.taskId)
                return LaunchResult(
                    activity = reused,
                    task = found.task.toRecord(),
                    reused = true,
                    clearedActivities = cleared,
                    pendingNewIntent = pendingNewIntent
                )
            }
        }

        val targetTask = selectTaskFor(requested, intentFlags)
        val top = targetTask.activities.lastOrNull()
        if (normalizedLaunchMode == LAUNCH_MODE_SINGLE_TOP && top != null && top.sameComponent(requested)) {
            val pendingNewIntent = pendingNewIntentFor(requested, intentFlags, dataIntent)
            val reused = top.copy(
                intentFlags = intentFlags,
                state = VirtualActivityState.RESUMED,
                resultToToken = requested.resultToToken,
                resultRequestCode = requested.resultRequestCode,
                pendingNewIntents = top.pendingNewIntents + pendingNewIntent
            )
            targetTask.activities[targetTask.activities.lastIndex] = reused
            moveToTop(targetTask.taskId)
            return LaunchResult(
                activity = reused,
                task = targetTask.toRecord(),
                reused = true,
                pendingNewIntent = pendingNewIntent
            )
        }

        if (intentFlags.hasFlag(FLAG_ACTIVITY_CLEAR_TOP)) {
            val index = targetTask.activities.indexOfFirst { it.sameComponent(requested) }
            if (index >= 0) {
                val cleared = clearAbove(targetTask, index)
                val pendingNewIntent = pendingNewIntentFor(requested, intentFlags, dataIntent)
                val reused = targetTask.activities[index].copy(
                    intentFlags = intentFlags,
                    state = VirtualActivityState.RESUMED,
                    resultToToken = requested.resultToToken,
                    resultRequestCode = requested.resultRequestCode,
                    pendingNewIntents = targetTask.activities[index].pendingNewIntents + pendingNewIntent
                )
                targetTask.activities[index] = reused
                moveToTop(targetTask.taskId)
                return LaunchResult(
                    activity = reused,
                    task = targetTask.toRecord(),
                    reused = true,
                    clearedActivities = cleared,
                    pendingNewIntent = pendingNewIntent
                )
            }
        }

        val launched = requested.copy(
            taskId = targetTask.taskId,
            taskAffinity = targetTask.affinity,
            state = VirtualActivityState.RESUMED
        )
        targetTask.activities += launched
        moveToTop(targetTask.taskId)
        return LaunchResult(launched, targetTask.toRecord(), reused = false)
    }

    @Synchronized
    fun listTasks(): List<VirtualTaskRecord> = tasks.values.map { it.toRecord() }

    @Synchronized
    fun restore(restoredTasks: List<VirtualTaskRecord>): Int {
        tasks.clear()
        val seenTokens = mutableSetOf<String>()
        val seenActivityIds = mutableSetOf<String>()
        var maxTaskId = 0
        var maxEventId = 0L
        restoredTasks.forEach { task ->
            val activities = task.activities
                .asSequence()
                .filter { it.isRestorableActivity() }
                .filter { seenTokens.add(it.token) }
                .filter { seenActivityIds.add(it.activityId) }
                .map { activity ->
                    val restoredActivity = activity.copy(
                        taskId = task.taskId,
                        taskAffinity = activity.taskAffinity ?: task.affinity
                    )
                    restoredActivity.pendingNewIntents.forEach { pending ->
                        if (pending.eventId > maxEventId) {
                            maxEventId = pending.eventId
                        }
                    }
                    restoredActivity
                }
                .toMutableList()
            if (activities.isNotEmpty()) {
                tasks[task.taskId] = MutableTaskRecord(
                    taskId = task.taskId,
                    affinity = task.affinity,
                    createdAtMs = task.createdAtMs,
                    activities = activities
                )
                if (task.taskId > maxTaskId) {
                    maxTaskId = task.taskId
                }
            }
        }
        nextTaskId = maxTaskId + 1
        if (nextTaskId < 1) {
            nextTaskId = 1
        }
        nextEventId = maxEventId + 1
        if (nextEventId < 1L) {
            nextEventId = 1L
        }
        return tasks.values.sumOf { it.activities.size }
    }

    @Synchronized
    fun topTask(): VirtualTaskRecord? = tasks.values.lastOrNull()?.toRecord()

    @Synchronized
    fun topActivity(): VirtualActivityRecord? = tasks.values.lastOrNull()?.activities?.lastOrNull()

    @Synchronized
    fun findByToken(token: String): VirtualActivityRecord? = tasks.values
        .asSequence()
        .flatMap { it.activities.asSequence() }
        .firstOrNull { it.token == token }

    @Synchronized
    fun findByActivityId(activityId: String): VirtualActivityRecord? = tasks.values
        .asSequence()
        .flatMap { it.activities.asSequence() }
        .firstOrNull { it.activityId == activityId }

    @Synchronized
    fun finishByToken(token: String): VirtualActivityRecord? = removeFirst { it.token == token }

    @Synchronized
    fun finishByActivityId(activityId: String): VirtualActivityRecord? = removeFirst { it.activityId == activityId }

    @Synchronized
    fun setResultByToken(
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null,
        requestCode: Int = -1,
        resultWho: String? = null,
        frameworkDispatchAttempted: Boolean = false,
        frameworkDispatchInvoked: Boolean = false
    ): VirtualActivityRecord? = updateFirst({ it.token == token }) { record ->
        record.copy(
            result = VirtualActivityResult(
                resultCode = resultCode,
                dataIntent = dataIntent,
                requestCode = requestCode,
                resultWho = resultWho,
                frameworkDispatchAttempted = frameworkDispatchAttempted,
                frameworkDispatchInvoked = frameworkDispatchInvoked
            )
        )
    }

    @Synchronized
    fun setResultByActivityId(
        activityId: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null,
        requestCode: Int = -1,
        resultWho: String? = null,
        frameworkDispatchAttempted: Boolean = false,
        frameworkDispatchInvoked: Boolean = false
    ): VirtualActivityRecord? = updateFirst({ it.activityId == activityId }) { record ->
        record.copy(
            result = VirtualActivityResult(
                resultCode = resultCode,
                dataIntent = dataIntent,
                requestCode = requestCode,
                resultWho = resultWho,
                frameworkDispatchAttempted = frameworkDispatchAttempted,
                frameworkDispatchInvoked = frameworkDispatchInvoked
            )
        )
    }

    @Synchronized
    fun updateResultDispatchStateByToken(
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityRecord? = updateFirst({ it.token == token && it.result != null }) { record ->
        record.copy(
            result = record.result?.copy(
                frameworkDispatchAttempted = frameworkDispatchAttempted,
                frameworkDispatchInvoked = frameworkDispatchInvoked
            )
        )
    }

    @Synchronized
    fun updateStateByToken(
        token: String,
        state: VirtualActivityState
    ): VirtualActivityRecord? = updateFirst({ it.token == token }) { record ->
        record.copy(state = state)
    }

    @Synchronized
    fun updateStateByActivityId(
        activityId: String,
        state: VirtualActivityState
    ): VirtualActivityRecord? = updateFirst({ it.activityId == activityId }) { record ->
        record.copy(state = state)
    }

    @Synchronized
    fun consumePendingNewIntentByToken(token: String): VirtualActivityPendingNewIntent? =
        consumePendingNewIntent { it.token == token }

    @Synchronized
    fun consumePendingNewIntentByActivityId(activityId: String): VirtualActivityPendingNewIntent? =
        consumePendingNewIntent { it.activityId == activityId }

    @Synchronized
    fun consumeResultByToken(token: String): VirtualActivityResult? = consumeResult { it.token == token }

    @Synchronized
    fun consumeResultByActivityId(activityId: String): VirtualActivityResult? =
        consumeResult { it.activityId == activityId }

    @Synchronized
    fun removeByToken(token: String): VirtualActivityRecord? = finishByToken(token)

    @Synchronized
    fun removeByInstance(instanceId: String): List<VirtualActivityRecord> {
        val removed = mutableListOf<VirtualActivityRecord>()
        tasks.values.forEach { task ->
            val iterator = task.activities.listIterator()
            while (iterator.hasNext()) {
                val record = iterator.next()
                if (record.instanceId == instanceId) {
                    iterator.remove()
                    removed += record.copy(state = VirtualActivityState.FINISHED)
                }
            }
        }
        return removed
    }

    @Synchronized
    fun pruneEmptyTasks(): Int {
        val emptyTaskIds = tasks.values
            .filter { it.activities.isEmpty() }
            .map { it.taskId }
        emptyTaskIds.forEach { tasks.remove(it) }
        return emptyTaskIds.size
    }

    @Synchronized
    fun clearAll() {
        tasks.clear()
        nextTaskId = 1
        nextEventId = 1L
    }

    private fun selectTaskFor(record: VirtualActivityRecord, intentFlags: Int): MutableTaskRecord {
        val affinity = record.taskAffinity ?: record.instanceScopedAffinity()
        if (intentFlags.hasFlag(FLAG_ACTIVITY_NEW_TASK)) {
            return tasks.values.lastOrNull { it.affinity == affinity } ?: createTask(affinity)
        }
        return tasks.values.lastOrNull { it.affinity == affinity } ?: createTask(affinity)
    }

    private fun createTask(affinity: String): MutableTaskRecord {
        val task = MutableTaskRecord(
            taskId = nextTaskId++,
            affinity = affinity,
            createdAtMs = System.currentTimeMillis()
        )
        tasks[task.taskId] = task
        return task
    }

    private fun findActivity(record: VirtualActivityRecord): ActivityPosition? {
        tasks.values.forEach { task ->
            val index = task.activities.indexOfFirst { it.sameComponent(record) }
            if (index >= 0) return ActivityPosition(task, index)
        }
        return null
    }

    private fun clearAbove(task: MutableTaskRecord, index: Int): List<VirtualActivityRecord> {
        if (index >= task.activities.lastIndex) return emptyList()
        val cleared = task.activities.subList(index + 1, task.activities.size)
            .map { it.copy(state = VirtualActivityState.FINISHED) }
        task.activities.subList(index + 1, task.activities.size).clear()
        return cleared
    }

    private fun removeFirst(predicate: (VirtualActivityRecord) -> Boolean): VirtualActivityRecord? {
        tasks.values.forEach { task ->
            val index = task.activities.indexOfFirst(predicate)
            if (index >= 0) {
                return task.activities.removeAt(index).copy(state = VirtualActivityState.FINISHED)
            }
        }
        return null
    }

    private fun updateFirst(
        predicate: (VirtualActivityRecord) -> Boolean,
        transform: (VirtualActivityRecord) -> VirtualActivityRecord
    ): VirtualActivityRecord? {
        tasks.values.forEach { task ->
            val index = task.activities.indexOfFirst(predicate)
            if (index >= 0) {
                val updated = transform(task.activities[index])
                task.activities[index] = updated
                return updated
            }
        }
        return null
    }

    private fun consumePendingNewIntent(
        predicate: (VirtualActivityRecord) -> Boolean
    ): VirtualActivityPendingNewIntent? {
        tasks.values.forEach { task ->
            val index = task.activities.indexOfFirst(predicate)
            if (index >= 0) {
                val record = task.activities[index]
                val pending = record.pendingNewIntents.firstOrNull() ?: return null
                task.activities[index] = record.copy(pendingNewIntents = record.pendingNewIntents.drop(1))
                return pending
            }
        }
        return null
    }

    private fun consumeResult(predicate: (VirtualActivityRecord) -> Boolean): VirtualActivityResult? {
        tasks.values.forEach { task ->
            val index = task.activities.indexOfFirst(predicate)
            if (index >= 0) {
                val record = task.activities[index]
                val result = record.result ?: return null
                task.activities[index] = record.copy(result = null)
                return result
            }
        }
        return null
    }

    private fun pendingNewIntentFor(
        requested: VirtualActivityRecord,
        intentFlags: Int,
        dataIntent: VirtualIntentSnapshot?
    ): VirtualActivityPendingNewIntent = VirtualActivityPendingNewIntent(
        eventId = nextEventId++,
        sourceToken = requested.token,
        intentFlags = intentFlags,
        dataIntent = dataIntent
    )

    private fun moveToTop(taskId: Int) {
        val task = tasks.remove(taskId) ?: return
        tasks[taskId] = task
    }

    private fun Int.hasFlag(flag: Int): Boolean = this and flag != 0

    private fun VirtualActivityRecord.sameComponent(other: VirtualActivityRecord): Boolean =
        instanceId == other.instanceId &&
            originPackageName == other.originPackageName &&
            guestActivityClassName == other.guestActivityClassName

    private fun VirtualActivityRecord.isRestorableActivity(): Boolean =
        state != VirtualActivityState.FINISHED &&
            state != VirtualActivityState.DESTROYED &&
            token.isNotBlank() &&
            activityId.isNotBlank() &&
            instanceId.isNotBlank() &&
            originPackageName.isNotBlank() &&
            guestActivityClassName.isNotBlank() &&
            proxyActivityClassName.isNotBlank()

    private fun VirtualActivityRecord.instanceScopedAffinity(): String =
        "$originPackageName:$instanceId"

    data class LaunchResult(
        val activity: VirtualActivityRecord,
        val task: VirtualTaskRecord,
        val reused: Boolean,
        val clearedActivities: List<VirtualActivityRecord> = emptyList(),
        val pendingNewIntent: VirtualActivityPendingNewIntent? = null
    )

    private data class ActivityPosition(
        val task: MutableTaskRecord,
        val index: Int
    )

    private data class MutableTaskRecord(
        val taskId: Int,
        val affinity: String,
        val createdAtMs: Long,
        val activities: MutableList<VirtualActivityRecord> = mutableListOf()
    ) {
        fun toRecord(): VirtualTaskRecord = VirtualTaskRecord(
            taskId = taskId,
            affinity = affinity,
            activities = activities.toList(),
            createdAtMs = createdAtMs
        )
    }

    companion object {
        const val FLAG_ACTIVITY_NEW_TASK: Int = 0x10000000.toInt()
        const val FLAG_ACTIVITY_CLEAR_TOP: Int = 0x04000000

        private const val LAUNCH_MODE_SINGLE_TOP = "singleTop"
        private const val LAUNCH_MODE_SINGLE_TASK = "singleTask"
    }
}

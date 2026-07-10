package com.multiapp.core.engine

import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

data class EngineActivityTaskStateSnapshot(
    val tasks: List<VirtualTaskRecord> = emptyList()
) {
    val activityCount: Int
        get() = tasks.sumOf { it.activities.size }

    companion object {
        val EMPTY = EngineActivityTaskStateSnapshot()
    }

    fun replacingInstance(
        instanceId: String,
        replacement: EngineActivityTaskStateSnapshot
    ): EngineActivityTaskStateSnapshot {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        val preserved = tasks.mapNotNull { task ->
            val activities = task.activities.filterNot { it.instanceId == instanceId }
            task.copy(activities = activities).takeIf { activities.isNotEmpty() }
        }.toMutableList()
        replacement.tasks.forEach { task ->
            val activities = task.activities.filter { it.instanceId == instanceId }
            if (activities.isEmpty()) return@forEach
            val replacementTask = task.copy(activities = activities)
            val existingIndex = preserved.indexOfFirst {
                it.taskId == replacementTask.taskId && it.affinity == replacementTask.affinity
            }
            if (existingIndex < 0) {
                preserved += replacementTask
            } else {
                val existing = preserved[existingIndex]
                preserved[existingIndex] = existing.copy(
                    activities = existing.activities + replacementTask.activities,
                    createdAtMs = minOf(existing.createdAtMs, replacementTask.createdAtMs)
                )
            }
        }
        return EngineActivityTaskStateSnapshot(preserved)
    }
}

interface EngineActivityTaskStateStore {
    fun save(snapshot: EngineActivityTaskStateSnapshot)
    fun mergeInstance(
        instanceId: String,
        snapshot: EngineActivityTaskStateSnapshot
    ): EngineActivityTaskStateSnapshot
    fun load(): EngineActivityTaskStateSnapshot
    fun clear()
}

object EngineActivityTaskStateFiles {
    const val DEFAULT_FILE_NAME = "engine_activity_task_state.properties"
}

class InMemoryEngineActivityTaskStateStore : EngineActivityTaskStateStore {
    private var snapshot: EngineActivityTaskStateSnapshot = EngineActivityTaskStateSnapshot.EMPTY

    @Synchronized
    override fun save(snapshot: EngineActivityTaskStateSnapshot) {
        this.snapshot = snapshot
    }

    @Synchronized
    override fun mergeInstance(
        instanceId: String,
        snapshot: EngineActivityTaskStateSnapshot
    ): EngineActivityTaskStateSnapshot = this.snapshot
        .replacingInstance(instanceId, snapshot)
        .also { merged -> this.snapshot = merged }

    @Synchronized
    override fun load(): EngineActivityTaskStateSnapshot = snapshot

    @Synchronized
    override fun clear() {
        snapshot = EngineActivityTaskStateSnapshot.EMPTY
    }
}

class FileBackedEngineActivityTaskStateStore(
    private val file: File
) : EngineActivityTaskStateStore {

    override fun save(snapshot: EngineActivityTaskStateSnapshot) = withFileLock {
        writeSnapshot(snapshot)
    }

    override fun mergeInstance(
        instanceId: String,
        snapshot: EngineActivityTaskStateSnapshot
    ): EngineActivityTaskStateSnapshot = withFileLock {
        readSnapshot()
            .replacingInstance(instanceId, snapshot)
            .also(::writeSnapshot)
    }

    override fun load(): EngineActivityTaskStateSnapshot = withFileLock {
        readSnapshot()
    }

    override fun clear() = withFileLock {
        if (file.exists()) file.delete()
    }

    private fun writeSnapshot(snapshot: EngineActivityTaskStateSnapshot) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        properties.setProperty(TASK_COUNT, snapshot.tasks.size.toString())
        snapshot.tasks.forEachIndexed { taskIndex, task ->
            val taskPrefix = "$TASK_PREFIX.$taskIndex."
            properties.setProperty(taskPrefix + TASK_ID, task.taskId.toString())
            properties.setProperty(taskPrefix + TASK_AFFINITY, task.affinity)
            properties.setProperty(taskPrefix + CREATED_AT_MS, task.createdAtMs.toString())
            properties.storeActivities(taskPrefix + ACTIVITY_PREFIX, task.activities)
        }
        file.outputStream().use { output ->
            properties.store(output, "MultiApp engine activity task state")
        }
    }

    private fun readSnapshot(): EngineActivityTaskStateSnapshot {
        if (!file.isFile) return EngineActivityTaskStateSnapshot.EMPTY
        val properties = Properties()
        file.inputStream().use { input -> properties.load(input) }
        val tasks = (0 until properties.int(TASK_COUNT))
            .mapNotNull { taskIndex ->
                runCatching {
                    val taskPrefix = "$TASK_PREFIX.$taskIndex."
                    val taskId = properties.required(taskPrefix + TASK_ID).toInt()
                    val affinity = properties.required(taskPrefix + TASK_AFFINITY)
                    val createdAtMs = properties.long(taskPrefix + CREATED_AT_MS)
                    val activities = properties.decodeActivities(taskPrefix + ACTIVITY_PREFIX)
                    VirtualTaskRecord(
                        taskId = taskId,
                        affinity = affinity,
                        activities = activities,
                        createdAtMs = createdAtMs
                    )
                }.getOrNull()
            }
        return EngineActivityTaskStateSnapshot(tasks)
    }

    private fun <T> withFileLock(block: () -> T): T {
        file.parentFile?.mkdirs()
        val monitor = FILE_MONITORS.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }
        return synchronized(monitor) {
            val lockFile = File(file.absolutePath + LOCK_SUFFIX)
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                val lock = channel.lock()
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun Properties.storeActivities(prefix: String, activities: List<VirtualActivityRecord>) {
        setProperty(prefix + COUNT, activities.size.toString())
        activities.forEachIndexed { index, activity ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + TOKEN, activity.token)
            setProperty(itemPrefix + ACTIVITY_ID, activity.activityId)
            setProperty(itemPrefix + INSTANCE_ID, activity.instanceId)
            setProperty(itemPrefix + ORIGIN_PACKAGE_NAME, activity.originPackageName)
            setProperty(itemPrefix + GUEST_ACTIVITY_CLASS_NAME, activity.guestActivityClassName)
            setProperty(itemPrefix + PROXY_ACTIVITY_CLASS_NAME, activity.proxyActivityClassName)
            setNullable(itemPrefix + LAUNCH_MODE, activity.launchMode)
            setProperty(itemPrefix + CREATED_AT_MS, activity.createdAtMs.toString())
            setProperty(itemPrefix + TASK_ID, activity.taskId.toString())
            setProperty(itemPrefix + INTENT_FLAGS, activity.intentFlags.toString())
            setProperty(itemPrefix + STATE, activity.state.name)
            setNullable(itemPrefix + TASK_AFFINITY, activity.taskAffinity)
            setNullable(itemPrefix + RESULT_TO_TOKEN, activity.resultToToken)
            setProperty(itemPrefix + RESULT_REQUEST_CODE, activity.resultRequestCode.toString())
            storePendingNewIntents(itemPrefix + PENDING_NEW_INTENTS, activity.pendingNewIntents)
            storeResult(itemPrefix + RESULT, activity.result)
        }
    }

    private fun Properties.decodeActivities(prefix: String): List<VirtualActivityRecord> =
        (0 until int(prefix + COUNT)).mapNotNull { index ->
            runCatching {
                val itemPrefix = "$prefix.$index."
                VirtualActivityRecord(
                    token = required(itemPrefix + TOKEN),
                    activityId = required(itemPrefix + ACTIVITY_ID),
                    instanceId = required(itemPrefix + INSTANCE_ID),
                    originPackageName = required(itemPrefix + ORIGIN_PACKAGE_NAME),
                    guestActivityClassName = required(itemPrefix + GUEST_ACTIVITY_CLASS_NAME),
                    proxyActivityClassName = required(itemPrefix + PROXY_ACTIVITY_CLASS_NAME),
                    launchMode = getProperty(itemPrefix + LAUNCH_MODE),
                    createdAtMs = long(itemPrefix + CREATED_AT_MS),
                    taskId = int(itemPrefix + TASK_ID),
                    intentFlags = int(itemPrefix + INTENT_FLAGS),
                    state = enumValueOf(getProperty(itemPrefix + STATE) ?: VirtualActivityState.CREATED.name),
                    taskAffinity = getProperty(itemPrefix + TASK_AFFINITY),
                    resultToToken = getProperty(itemPrefix + RESULT_TO_TOKEN),
                    resultRequestCode = if (getProperty(itemPrefix + RESULT_TO_TOKEN).isNullOrBlank()) {
                        -1
                    } else {
                        int(itemPrefix + RESULT_REQUEST_CODE).takeIf { it >= 0 } ?: -1
                    },
                    pendingNewIntents = decodePendingNewIntents(itemPrefix + PENDING_NEW_INTENTS),
                    result = decodeResult(itemPrefix + RESULT)
                )
            }.getOrNull()
        }

    private fun Properties.storePendingNewIntents(
        prefix: String,
        pendingNewIntents: List<VirtualActivityPendingNewIntent>
    ) {
        setProperty(prefix + COUNT, pendingNewIntents.size.toString())
        pendingNewIntents.forEachIndexed { index, pending ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + EVENT_ID, pending.eventId.toString())
            setProperty(itemPrefix + SOURCE_TOKEN, pending.sourceToken)
            setProperty(itemPrefix + INTENT_FLAGS, pending.intentFlags.toString())
            setProperty(itemPrefix + CREATED_AT_MS, pending.createdAtMs.toString())
            storeIntentSnapshot(itemPrefix + DATA_INTENT, pending.dataIntent)
        }
    }

    private fun Properties.decodePendingNewIntents(prefix: String): List<VirtualActivityPendingNewIntent> =
        (0 until int(prefix + COUNT)).mapNotNull { index ->
            runCatching {
                val itemPrefix = "$prefix.$index."
                VirtualActivityPendingNewIntent(
                    eventId = required(itemPrefix + EVENT_ID).toLong(),
                    sourceToken = required(itemPrefix + SOURCE_TOKEN),
                    intentFlags = int(itemPrefix + INTENT_FLAGS),
                    dataIntent = decodeIntentSnapshot(itemPrefix + DATA_INTENT),
                    createdAtMs = long(itemPrefix + CREATED_AT_MS)
                )
            }.getOrNull()
        }

    private fun Properties.storeResult(prefix: String, result: VirtualActivityResult?) {
        setProperty(prefix + PRESENT, (result != null).toString())
        if (result == null) return
        setProperty(prefix + RESULT_CODE, result.resultCode.toString())
        setProperty(prefix + RESULT_REQUEST_CODE, result.requestCode.toString())
        setNullable(prefix + RESULT_WHO, result.resultWho)
        setProperty(prefix + RESULT_FRAMEWORK_DISPATCH_ATTEMPTED, result.frameworkDispatchAttempted.toString())
        setProperty(prefix + RESULT_FRAMEWORK_DISPATCH_INVOKED, result.frameworkDispatchInvoked.toString())
        setProperty(prefix + UPDATED_AT_MS, result.updatedAtMs.toString())
        storeIntentSnapshot(prefix + DATA_INTENT, result.dataIntent)
    }

    private fun Properties.decodeResult(prefix: String): VirtualActivityResult? {
        if (!getProperty(prefix + PRESENT).toBoolean()) return null
        return runCatching {
            VirtualActivityResult(
                resultCode = int(prefix + RESULT_CODE),
                dataIntent = decodeIntentSnapshot(prefix + DATA_INTENT),
                requestCode = int(prefix + RESULT_REQUEST_CODE).let { if (it >= 0) it else -1 },
                resultWho = getProperty(prefix + RESULT_WHO),
                frameworkDispatchAttempted = getProperty(prefix + RESULT_FRAMEWORK_DISPATCH_ATTEMPTED).toBoolean(),
                frameworkDispatchInvoked = getProperty(prefix + RESULT_FRAMEWORK_DISPATCH_INVOKED).toBoolean(),
                updatedAtMs = long(prefix + UPDATED_AT_MS)
            )
        }.getOrNull()
    }

    private fun Properties.storeIntentSnapshot(prefix: String, intent: VirtualIntentSnapshot?) {
        setProperty(prefix + PRESENT, (intent != null).toString())
        if (intent == null) return
        setProperty(prefix + FLAGS, intent.flags.toString())
        setNullable(prefix + ACTION, intent.action)
        setNullable(prefix + DATA_URI, intent.dataUri)
        setProperty(prefix + CATEGORIES, intent.categories.toList().encodeStringList())
        storeStringMap(prefix + EXTRAS, intent.extras)
    }

    private fun Properties.decodeIntentSnapshot(prefix: String): VirtualIntentSnapshot? {
        if (!getProperty(prefix + PRESENT).toBoolean()) return null
        return VirtualIntentSnapshot(
            flags = int(prefix + FLAGS),
            action = getProperty(prefix + ACTION),
            dataUri = getProperty(prefix + DATA_URI),
            categories = getProperty(prefix + CATEGORIES).decodeStringList().toSet(),
            extras = decodeStringMap(prefix + EXTRAS)
        )
    }

    private fun Properties.storeStringMap(prefix: String, values: Map<String, String>) {
        setProperty(prefix + COUNT, values.size.toString())
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + MAP_KEY, entry.key)
            setProperty(itemPrefix + MAP_VALUE, entry.value)
        }
    }

    private fun Properties.decodeStringMap(prefix: String): Map<String, String> {
        val count = int(prefix + COUNT)
        return (0 until count)
            .mapNotNull { index ->
                val itemPrefix = "$prefix.$index."
                val key = getProperty(itemPrefix + MAP_KEY) ?: return@mapNotNull null
                key to getProperty(itemPrefix + MAP_VALUE).orEmpty()
            }
            .toMap()
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() } ?: error("Missing activity task state property: $key")

    private fun Properties.int(key: String): Int =
        getProperty(key).orEmpty().toIntOrNull() ?: 0

    private fun Properties.long(key: String): Long =
        getProperty(key).orEmpty().toLongOrNull() ?: 0L

    private fun Properties.setNullable(key: String, value: String?) {
        if (!value.isNullOrBlank()) {
            setProperty(key, value)
        }
    }

    private fun List<String>.encodeStringList(): String =
        joinToString(LIST_SEPARATOR) { value -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }

    private fun String?.decodeStringList(): List<String> {
        if (isNullOrBlank()) return emptyList()
        return split(LIST_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { encoded -> String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }
    }

    companion object {
        private const val LOCK_SUFFIX = ".lock"
        private val FILE_MONITORS = ConcurrentHashMap<String, Any>()
        private const val TASK_COUNT = "task.count"
        private const val TASK_PREFIX = "task"
        private const val ACTIVITY_PREFIX = "activity"
        private const val COUNT = ".count"
        private const val TASK_ID = "taskId"
        private const val TASK_AFFINITY = "taskAffinity"
        private const val CREATED_AT_MS = "createdAtMs"
        private const val TOKEN = "token"
        private const val ACTIVITY_ID = "activityId"
        private const val INSTANCE_ID = "instanceId"
        private const val ORIGIN_PACKAGE_NAME = "originPackageName"
        private const val GUEST_ACTIVITY_CLASS_NAME = "guestActivityClassName"
        private const val PROXY_ACTIVITY_CLASS_NAME = "proxyActivityClassName"
        private const val LAUNCH_MODE = "launchMode"
        private const val INTENT_FLAGS = "intentFlags"
        private const val STATE = "state"
        private const val RESULT_TO_TOKEN = "resultToToken"
        private const val RESULT_REQUEST_CODE = "resultRequestCode"
        private const val RESULT_WHO = "resultWho"
        private const val RESULT_FRAMEWORK_DISPATCH_ATTEMPTED = "frameworkDispatchAttempted"
        private const val RESULT_FRAMEWORK_DISPATCH_INVOKED = "frameworkDispatchInvoked"
        private const val PENDING_NEW_INTENTS = "pendingNewIntents"
        private const val RESULT = "result"
        private const val PRESENT = ".present"
        private const val EVENT_ID = "eventId"
        private const val SOURCE_TOKEN = "sourceToken"
        private const val DATA_INTENT = "dataIntent"
        private const val RESULT_CODE = "resultCode"
        private const val UPDATED_AT_MS = "updatedAtMs"
        private const val FLAGS = "flags"
        private const val ACTION = "action"
        private const val DATA_URI = "dataUri"
        private const val CATEGORIES = "categories"
        private const val EXTRAS = "extras"
        private const val MAP_KEY = "key"
        private const val MAP_VALUE = "value"
        private const val LIST_SEPARATOR = ","
    }
}

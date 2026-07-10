package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

enum class EngineBroadcastDeliveryState {
    DELIVERED,
    BLOCKED,
    FAILED
}

data class EngineBroadcastRuntimeRecord(
    val instanceId: String,
    val receiverClassName: String?,
    val action: String?,
    val processSlot: String,
    val runtimeEpoch: Long,
    val state: EngineBroadcastDeliveryState,
    val lastVerdict: EngineResultStatus,
    val lastReason: String,
    val dispatchCount: Long = 1L,
    val deliveredCount: Long = 0L,
    val blockedCount: Long = 0L,
    val failureCount: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(receiverClassName == null || receiverClassName.isNotBlank()) {
            "receiverClassName must not be blank"
        }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(receiverClassName != null || action != null) {
            "receiverClassName or action must be present"
        }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(lastReason.isNotBlank()) { "lastReason must not be blank" }
        require(dispatchCount > 0L) { "dispatchCount must be positive" }
        require(deliveredCount >= 0L) { "deliveredCount must not be negative" }
        require(blockedCount >= 0L) { "blockedCount must not be negative" }
        require(failureCount >= 0L) { "failureCount must not be negative" }
        require(deliveredCount + blockedCount + failureCount <= dispatchCount) {
            "delivery counters must not exceed dispatchCount"
        }
        require(updatedAtMs > 0L) { "updatedAtMs must be positive" }
    }
}

interface EngineBroadcastRuntimeStateStore {
    fun upsert(record: EngineBroadcastRuntimeRecord): EngineBroadcastRuntimeRecord
    fun update(
        instanceId: String,
        receiverClassName: String?,
        action: String?,
        transform: (EngineBroadcastRuntimeRecord?) -> EngineBroadcastRuntimeRecord
    ): EngineBroadcastRuntimeRecord
    fun list(instanceId: String): List<EngineBroadcastRuntimeRecord>
    fun clear(instanceId: String)
}

object EngineBroadcastRuntimeStateFiles {
    const val DEFAULT_FILE_NAME = "engine_broadcast_runtime_state.properties"
}

class InMemoryEngineBroadcastRuntimeStateStore : EngineBroadcastRuntimeStateStore {
    private val records = linkedMapOf<String, EngineBroadcastRuntimeRecord>()

    @Synchronized
    override fun upsert(record: EngineBroadcastRuntimeRecord): EngineBroadcastRuntimeRecord = record.also {
        records[record.key()] = record
    }

    @Synchronized
    override fun update(
        instanceId: String,
        receiverClassName: String?,
        action: String?,
        transform: (EngineBroadcastRuntimeRecord?) -> EngineBroadcastRuntimeRecord
    ): EngineBroadcastRuntimeRecord {
        val key = recordKey(instanceId, receiverClassName, action)
        return transform(records[key]).also { record ->
            require(record.key() == key) { "updated record key must match requested Broadcast key" }
            records[key] = record
        }
    }

    @Synchronized
    override fun list(instanceId: String): List<EngineBroadcastRuntimeRecord> = records.values
        .filter { it.instanceId == instanceId }
        .sortedWith(compareBy(EngineBroadcastRuntimeRecord::receiverClassName, EngineBroadcastRuntimeRecord::action))

    @Synchronized
    override fun clear(instanceId: String) {
        records.entries.removeAll { it.value.instanceId == instanceId }
    }
}

class FileBackedEngineBroadcastRuntimeStateStore(
    private val file: File
) : EngineBroadcastRuntimeStateStore {
    override fun upsert(record: EngineBroadcastRuntimeRecord): EngineBroadcastRuntimeRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        records[record.key()] = record
        writeRecords(records.values.toList())
        record
    }

    override fun update(
        instanceId: String,
        receiverClassName: String?,
        action: String?,
        transform: (EngineBroadcastRuntimeRecord?) -> EngineBroadcastRuntimeRecord
    ): EngineBroadcastRuntimeRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        val key = recordKey(instanceId, receiverClassName, action)
        transform(records[key]).also { record ->
            require(record.key() == key) { "updated record key must match requested Broadcast key" }
            records[key] = record
            writeRecords(records.values.toList())
        }
    }

    override fun list(instanceId: String): List<EngineBroadcastRuntimeRecord> = withFileLock {
        readRecords()
            .filter { it.instanceId == instanceId }
            .sortedWith(compareBy(EngineBroadcastRuntimeRecord::receiverClassName, EngineBroadcastRuntimeRecord::action))
    }

    override fun clear(instanceId: String) = withFileLock {
        writeRecords(readRecords().filterNot { it.instanceId == instanceId })
    }

    private fun readRecords(): List<EngineBroadcastRuntimeRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return (0 until properties.int(RECORD_COUNT)).mapNotNull { index ->
            runCatching {
                val prefix = "$RECORD_PREFIX.$index."
                EngineBroadcastRuntimeRecord(
                    instanceId = properties.required(prefix + INSTANCE_ID),
                    receiverClassName = properties.optional(prefix + RECEIVER_CLASS_NAME),
                    action = properties.optional(prefix + ACTION),
                    processSlot = properties.required(prefix + PROCESS_SLOT),
                    runtimeEpoch = properties.required(prefix + RUNTIME_EPOCH).toLong(),
                    state = EngineBroadcastDeliveryState.valueOf(properties.required(prefix + STATE)),
                    lastVerdict = EngineResultStatus.valueOf(properties.required(prefix + LAST_VERDICT)),
                    lastReason = properties.required(prefix + LAST_REASON),
                    dispatchCount = properties.required(prefix + DISPATCH_COUNT).toLong(),
                    deliveredCount = properties.required(prefix + DELIVERED_COUNT).toLong(),
                    blockedCount = properties.required(prefix + BLOCKED_COUNT).toLong(),
                    failureCount = properties.required(prefix + FAILURE_COUNT).toLong(),
                    updatedAtMs = properties.required(prefix + UPDATED_AT_MS).toLong()
                )
            }.getOrNull()
        }
    }

    private fun writeRecords(records: List<EngineBroadcastRuntimeRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedWith(
                compareBy(
                    EngineBroadcastRuntimeRecord::instanceId,
                    EngineBroadcastRuntimeRecord::receiverClassName,
                    EngineBroadcastRuntimeRecord::action
                )
            ).forEachIndexed { index, record ->
                val prefix = "$RECORD_PREFIX.$index."
                setProperty(prefix + INSTANCE_ID, record.instanceId)
                setProperty(prefix + RECEIVER_CLASS_NAME, record.receiverClassName.orEmpty())
                setProperty(prefix + ACTION, record.action.orEmpty())
                setProperty(prefix + PROCESS_SLOT, record.processSlot)
                setProperty(prefix + RUNTIME_EPOCH, record.runtimeEpoch.toString())
                setProperty(prefix + STATE, record.state.name)
                setProperty(prefix + LAST_VERDICT, record.lastVerdict.name)
                setProperty(prefix + LAST_REASON, record.lastReason)
                setProperty(prefix + DISPATCH_COUNT, record.dispatchCount.toString())
                setProperty(prefix + DELIVERED_COUNT, record.deliveredCount.toString())
                setProperty(prefix + BLOCKED_COUNT, record.blockedCount.toString())
                setProperty(prefix + FAILURE_COUNT, record.failureCount.toString())
                setProperty(prefix + UPDATED_AT_MS, record.updatedAtMs.toString())
            }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MultiApp engine broadcast runtime state")
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> withFileLock(block: () -> T): T {
        file.parentFile?.mkdirs()
        val monitor = FILE_MONITORS.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }
        return synchronized(monitor) {
            RandomAccessFile(File(file.absolutePath + ".lock"), "rw").channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() } ?: error("missing property: $key")

    private fun Properties.optional(key: String): String? = getProperty(key)?.takeIf { it.isNotBlank() }

    private fun Properties.int(key: String): Int = getProperty(key)?.toIntOrNull() ?: 0

    private companion object {
        const val RECORD_COUNT = "record.count"
        const val RECORD_PREFIX = "record"
        const val INSTANCE_ID = "instanceId"
        const val RECEIVER_CLASS_NAME = "receiverClassName"
        const val ACTION = "action"
        const val PROCESS_SLOT = "processSlot"
        const val RUNTIME_EPOCH = "runtimeEpoch"
        const val STATE = "state"
        const val LAST_VERDICT = "lastVerdict"
        const val LAST_REASON = "lastReason"
        const val DISPATCH_COUNT = "dispatchCount"
        const val DELIVERED_COUNT = "deliveredCount"
        const val BLOCKED_COUNT = "blockedCount"
        const val FAILURE_COUNT = "failureCount"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private fun EngineBroadcastRuntimeRecord.key(): String =
    recordKey(instanceId, receiverClassName, action)

private fun recordKey(instanceId: String, receiverClassName: String?, action: String?): String =
    "$instanceId\u0000${receiverClassName.orEmpty()}\u0000${action.orEmpty()}"

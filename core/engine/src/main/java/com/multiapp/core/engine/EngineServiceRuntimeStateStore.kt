package com.multiapp.core.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

enum class EngineServiceLifecycleState {
    STARTED,
    FOREGROUND,
    BOUND,
    STOPPED
}

data class EngineServiceRuntimeRecord(
    val instanceId: String,
    val serviceClassName: String,
    val processSlot: String,
    val runtimeEpoch: Long,
    val state: EngineServiceLifecycleState,
    val activeStartCount: Int = 0,
    val activeBindCount: Int = 0,
    val cached: Boolean = false,
    val startCommandResult: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(activeStartCount >= 0) { "activeStartCount must not be negative" }
        require(activeBindCount >= 0) { "activeBindCount must not be negative" }
        require(updatedAtMs > 0L) { "updatedAtMs must be positive" }
    }
}

interface EngineServiceRuntimeStateStore {
    fun upsert(record: EngineServiceRuntimeRecord): EngineServiceRuntimeRecord
    fun list(instanceId: String): List<EngineServiceRuntimeRecord>
    fun clear(instanceId: String)
}

object EngineServiceRuntimeStateFiles {
    const val DEFAULT_FILE_NAME = "engine_service_runtime_state.properties"
}

class InMemoryEngineServiceRuntimeStateStore : EngineServiceRuntimeStateStore {
    private val records = linkedMapOf<String, EngineServiceRuntimeRecord>()

    @Synchronized
    override fun upsert(record: EngineServiceRuntimeRecord): EngineServiceRuntimeRecord = record.also {
        records[record.key()] = record
    }

    @Synchronized
    override fun list(instanceId: String): List<EngineServiceRuntimeRecord> = records.values
        .filter { it.instanceId == instanceId }
        .sortedBy { it.serviceClassName }

    @Synchronized
    override fun clear(instanceId: String) {
        records.entries.removeAll { it.value.instanceId == instanceId }
    }
}

class FileBackedEngineServiceRuntimeStateStore(
    private val file: File
) : EngineServiceRuntimeStateStore {

    override fun upsert(record: EngineServiceRuntimeRecord): EngineServiceRuntimeRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        records[record.key()] = record
        writeRecords(records.values.toList())
        record
    }

    override fun list(instanceId: String): List<EngineServiceRuntimeRecord> = withFileLock {
        readRecords().filter { it.instanceId == instanceId }.sortedBy { it.serviceClassName }
    }

    override fun clear(instanceId: String) = withFileLock {
        writeRecords(readRecords().filterNot { it.instanceId == instanceId })
    }

    private fun readRecords(): List<EngineServiceRuntimeRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return (0 until properties.int(RECORD_COUNT)).mapNotNull { index ->
            runCatching {
                val prefix = "$RECORD_PREFIX.$index."
                EngineServiceRuntimeRecord(
                    instanceId = properties.required(prefix + INSTANCE_ID),
                    serviceClassName = properties.required(prefix + SERVICE_CLASS_NAME),
                    processSlot = properties.required(prefix + PROCESS_SLOT),
                    runtimeEpoch = properties.required(prefix + RUNTIME_EPOCH).toLong(),
                    state = EngineServiceLifecycleState.valueOf(properties.required(prefix + STATE)),
                    activeStartCount = properties.int(prefix + ACTIVE_START_COUNT),
                    activeBindCount = properties.int(prefix + ACTIVE_BIND_COUNT),
                    cached = properties.getProperty(prefix + CACHED)?.toBooleanStrictOrNull() ?: false,
                    startCommandResult = properties.getProperty(prefix + START_COMMAND_RESULT)
                        ?.takeIf { it.isNotBlank() }
                        ?.toInt(),
                    updatedAtMs = properties.required(prefix + UPDATED_AT_MS).toLong()
                )
            }.getOrNull()
        }
    }

    private fun writeRecords(records: List<EngineServiceRuntimeRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedWith(compareBy(EngineServiceRuntimeRecord::instanceId, EngineServiceRuntimeRecord::serviceClassName))
                .forEachIndexed { index, record ->
                    val prefix = "$RECORD_PREFIX.$index."
                    setProperty(prefix + INSTANCE_ID, record.instanceId)
                    setProperty(prefix + SERVICE_CLASS_NAME, record.serviceClassName)
                    setProperty(prefix + PROCESS_SLOT, record.processSlot)
                    setProperty(prefix + RUNTIME_EPOCH, record.runtimeEpoch.toString())
                    setProperty(prefix + STATE, record.state.name)
                    setProperty(prefix + ACTIVE_START_COUNT, record.activeStartCount.toString())
                    setProperty(prefix + ACTIVE_BIND_COUNT, record.activeBindCount.toString())
                    setProperty(prefix + CACHED, record.cached.toString())
                    setProperty(prefix + START_COMMAND_RESULT, record.startCommandResult?.toString().orEmpty())
                    setProperty(prefix + UPDATED_AT_MS, record.updatedAtMs.toString())
                }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MultiApp engine service runtime state")
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

    private fun Properties.int(key: String): Int = getProperty(key)?.toIntOrNull() ?: 0

    private companion object {
        const val RECORD_COUNT = "record.count"
        const val RECORD_PREFIX = "record"
        const val INSTANCE_ID = "instanceId"
        const val SERVICE_CLASS_NAME = "serviceClassName"
        const val PROCESS_SLOT = "processSlot"
        const val RUNTIME_EPOCH = "runtimeEpoch"
        const val STATE = "state"
        const val ACTIVE_START_COUNT = "activeStartCount"
        const val ACTIVE_BIND_COUNT = "activeBindCount"
        const val CACHED = "cached"
        const val START_COMMAND_RESULT = "startCommandResult"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private fun EngineServiceRuntimeRecord.key(): String = "$instanceId\u0000$serviceClassName"

package com.multiapp.core.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

enum class EngineProviderLifecycleState {
    READY
}

data class EngineProviderRuntimeRecord(
    val instanceId: String,
    val guestAuthority: String,
    val providerClassName: String,
    val processSlot: String,
    val runtimeEpoch: Long,
    val state: EngineProviderLifecycleState = EngineProviderLifecycleState.READY,
    val cached: Boolean = false,
    val lastOperation: EngineProviderOperation,
    val operationCount: Long = 1L,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(providerClassName.isNotBlank()) { "providerClassName must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(operationCount > 0L) { "operationCount must be positive" }
        require(updatedAtMs > 0L) { "updatedAtMs must be positive" }
    }
}

interface EngineProviderRuntimeStateStore {
    fun upsert(record: EngineProviderRuntimeRecord): EngineProviderRuntimeRecord
    fun list(instanceId: String): List<EngineProviderRuntimeRecord>
    fun clear(instanceId: String)
}

object EngineProviderRuntimeStateFiles {
    const val DEFAULT_FILE_NAME = "engine_provider_runtime_state.properties"
}

class InMemoryEngineProviderRuntimeStateStore : EngineProviderRuntimeStateStore {
    private val records = linkedMapOf<String, EngineProviderRuntimeRecord>()

    @Synchronized
    override fun upsert(record: EngineProviderRuntimeRecord): EngineProviderRuntimeRecord = record.also {
        records[record.key()] = record
    }

    @Synchronized
    override fun list(instanceId: String): List<EngineProviderRuntimeRecord> = records.values
        .filter { it.instanceId == instanceId }
        .sortedBy { it.guestAuthority }

    @Synchronized
    override fun clear(instanceId: String) {
        records.entries.removeAll { it.value.instanceId == instanceId }
    }
}

class FileBackedEngineProviderRuntimeStateStore(
    private val file: File
) : EngineProviderRuntimeStateStore {
    override fun upsert(record: EngineProviderRuntimeRecord): EngineProviderRuntimeRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        records[record.key()] = record
        writeRecords(records.values.toList())
        record
    }

    override fun list(instanceId: String): List<EngineProviderRuntimeRecord> = withFileLock {
        readRecords().filter { it.instanceId == instanceId }.sortedBy { it.guestAuthority }
    }

    override fun clear(instanceId: String) = withFileLock {
        writeRecords(readRecords().filterNot { it.instanceId == instanceId })
    }

    private fun readRecords(): List<EngineProviderRuntimeRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return (0 until properties.int(RECORD_COUNT)).mapNotNull { index ->
            runCatching {
                val prefix = "$RECORD_PREFIX.$index."
                EngineProviderRuntimeRecord(
                    instanceId = properties.required(prefix + INSTANCE_ID),
                    guestAuthority = properties.required(prefix + GUEST_AUTHORITY),
                    providerClassName = properties.required(prefix + PROVIDER_CLASS_NAME),
                    processSlot = properties.required(prefix + PROCESS_SLOT),
                    runtimeEpoch = properties.required(prefix + RUNTIME_EPOCH).toLong(),
                    state = EngineProviderLifecycleState.valueOf(properties.required(prefix + STATE)),
                    cached = properties.getProperty(prefix + CACHED)?.toBooleanStrictOrNull() ?: false,
                    lastOperation = EngineProviderOperation.valueOf(
                        properties.required(prefix + LAST_OPERATION)
                    ),
                    operationCount = properties.required(prefix + OPERATION_COUNT).toLong(),
                    updatedAtMs = properties.required(prefix + UPDATED_AT_MS).toLong()
                )
            }.getOrNull()
        }
    }

    private fun writeRecords(records: List<EngineProviderRuntimeRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedWith(compareBy(EngineProviderRuntimeRecord::instanceId, EngineProviderRuntimeRecord::guestAuthority))
                .forEachIndexed { index, record ->
                    val prefix = "$RECORD_PREFIX.$index."
                    setProperty(prefix + INSTANCE_ID, record.instanceId)
                    setProperty(prefix + GUEST_AUTHORITY, record.guestAuthority)
                    setProperty(prefix + PROVIDER_CLASS_NAME, record.providerClassName)
                    setProperty(prefix + PROCESS_SLOT, record.processSlot)
                    setProperty(prefix + RUNTIME_EPOCH, record.runtimeEpoch.toString())
                    setProperty(prefix + STATE, record.state.name)
                    setProperty(prefix + CACHED, record.cached.toString())
                    setProperty(prefix + LAST_OPERATION, record.lastOperation.name)
                    setProperty(prefix + OPERATION_COUNT, record.operationCount.toString())
                    setProperty(prefix + UPDATED_AT_MS, record.updatedAtMs.toString())
                }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MultiApp engine provider runtime state")
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
        const val GUEST_AUTHORITY = "guestAuthority"
        const val PROVIDER_CLASS_NAME = "providerClassName"
        const val PROCESS_SLOT = "processSlot"
        const val RUNTIME_EPOCH = "runtimeEpoch"
        const val STATE = "state"
        const val CACHED = "cached"
        const val LAST_OPERATION = "lastOperation"
        const val OPERATION_COUNT = "operationCount"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private fun EngineProviderRuntimeRecord.key(): String = "$instanceId\u0000$guestAuthority"

package com.multiapp.core.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

object EngineAppOpModes {
    const val ALLOWED = 0
    const val IGNORED = 1
    const val ERRORED = 2
    const val DEFAULT = 3
    const val FOREGROUND = 4

    fun isValid(mode: Int): Boolean = mode in ALLOWED..FOREGROUND
}

data class EngineAppOpModeRecord(
    val instanceId: String,
    val opCode: Int,
    val mode: Int,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(opCode >= 0) { "opCode must not be negative" }
        require(EngineAppOpModes.isValid(mode)) { "invalid AppOps mode: $mode" }
        require(updatedAtMs > 0L) { "updatedAtMs must be positive" }
    }
}

interface EngineAppOpsStateStore {
    fun get(instanceId: String, opCode: Int): EngineAppOpModeRecord?
    fun set(record: EngineAppOpModeRecord): EngineAppOpModeRecord
    fun list(instanceId: String): List<EngineAppOpModeRecord>
    fun reset(instanceId: String, opCode: Int? = null): Int
}

object EngineAppOpsStateFiles {
    const val DEFAULT_FILE_NAME = "engine_app_ops.properties"
}

class InMemoryEngineAppOpsStateStore : EngineAppOpsStateStore {
    private val records = linkedMapOf<String, EngineAppOpModeRecord>()

    @Synchronized
    override fun get(instanceId: String, opCode: Int): EngineAppOpModeRecord? =
        records[key(instanceId, opCode)]

    @Synchronized
    override fun set(record: EngineAppOpModeRecord): EngineAppOpModeRecord {
        records[key(record.instanceId, record.opCode)] = record
        return record
    }

    @Synchronized
    override fun list(instanceId: String): List<EngineAppOpModeRecord> = records.values
        .filter { it.instanceId == instanceId }
        .sortedBy { it.opCode }

    @Synchronized
    override fun reset(instanceId: String, opCode: Int?): Int {
        val before = records.size
        records.entries.removeAll { entry ->
            entry.value.instanceId == instanceId && (opCode == null || entry.value.opCode == opCode)
        }
        return before - records.size
    }
}

class FileBackedEngineAppOpsStateStore(
    private val file: File
) : EngineAppOpsStateStore {
    override fun get(instanceId: String, opCode: Int): EngineAppOpModeRecord? = withFileLock {
        readRecords().firstOrNull { it.instanceId == instanceId && it.opCode == opCode }
    }

    override fun set(record: EngineAppOpModeRecord): EngineAppOpModeRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { key(it.instanceId, it.opCode) }
        records[key(record.instanceId, record.opCode)] = record
        writeRecords(records.values.toList())
        record
    }

    override fun list(instanceId: String): List<EngineAppOpModeRecord> = withFileLock {
        readRecords().filter { it.instanceId == instanceId }.sortedBy { it.opCode }
    }

    override fun reset(instanceId: String, opCode: Int?): Int = withFileLock {
        val current = readRecords()
        val retained = current.filterNot {
            it.instanceId == instanceId && (opCode == null || it.opCode == opCode)
        }
        if (retained.size != current.size) writeRecords(retained)
        current.size - retained.size
    }

    private fun readRecords(): List<EngineAppOpModeRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return (0 until (properties.getProperty(RECORD_COUNT)?.toIntOrNull() ?: 0)).mapNotNull { index ->
            runCatching {
                val prefix = "$RECORD_PREFIX.$index."
                EngineAppOpModeRecord(
                    instanceId = properties.required(prefix + INSTANCE_ID),
                    opCode = properties.required(prefix + OP_CODE).toInt(),
                    mode = properties.required(prefix + MODE).toInt(),
                    updatedAtMs = properties.required(prefix + UPDATED_AT_MS).toLong()
                )
            }.getOrNull()
        }
    }

    private fun writeRecords(records: List<EngineAppOpModeRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedWith(compareBy(EngineAppOpModeRecord::instanceId, EngineAppOpModeRecord::opCode))
                .forEachIndexed { index, record ->
                    val prefix = "$RECORD_PREFIX.$index."
                    setProperty(prefix + INSTANCE_ID, record.instanceId)
                    setProperty(prefix + OP_CODE, record.opCode.toString())
                    setProperty(prefix + MODE, record.mode.toString())
                    setProperty(prefix + UPDATED_AT_MS, record.updatedAtMs.toString())
                }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MultiApp engine AppOps modes")
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

    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf { it.isNotBlank() } ?: error("missing property: $name")

    private companion object {
        const val RECORD_COUNT = "record.count"
        const val RECORD_PREFIX = "record"
        const val INSTANCE_ID = "instanceId"
        const val OP_CODE = "opCode"
        const val MODE = "mode"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private fun key(instanceId: String, opCode: Int): String = "$instanceId\u0000$opCode"

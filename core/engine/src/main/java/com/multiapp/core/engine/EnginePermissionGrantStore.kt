package com.multiapp.core.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

enum class EnginePermissionGrantSource {
    ENGINE_POLICY,
    USER_DECISION,
    SOURCE_APP_MIRROR
}

data class EnginePermissionGrantRecord(
    val instanceId: String,
    val permissionName: String,
    val granted: Boolean,
    val source: EnginePermissionGrantSource,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(permissionName.isNotBlank()) { "permissionName must not be blank" }
        require(updatedAtMs > 0L) { "updatedAtMs must be positive" }
    }
}

interface EnginePermissionGrantStore {
    fun get(instanceId: String, permissionName: String): EnginePermissionGrantRecord?
    fun set(record: EnginePermissionGrantRecord): EnginePermissionGrantRecord
    fun list(instanceId: String): List<EnginePermissionGrantRecord>
    fun clear(instanceId: String, permissionName: String? = null): Int
}

object EnginePermissionGrantFiles {
    const val DEFAULT_FILE_NAME = "engine_permission_grants.properties"
}

class InMemoryEnginePermissionGrantStore : EnginePermissionGrantStore {
    private val records = linkedMapOf<String, EnginePermissionGrantRecord>()

    @Synchronized
    override fun get(instanceId: String, permissionName: String): EnginePermissionGrantRecord? =
        records[key(instanceId, permissionName)]

    @Synchronized
    override fun set(record: EnginePermissionGrantRecord): EnginePermissionGrantRecord {
        records[key(record.instanceId, record.permissionName)] = record
        return record
    }

    @Synchronized
    override fun list(instanceId: String): List<EnginePermissionGrantRecord> = records.values
        .filter { it.instanceId == instanceId }
        .sortedBy { it.permissionName }

    @Synchronized
    override fun clear(instanceId: String, permissionName: String?): Int {
        val before = records.size
        records.entries.removeAll { entry ->
            entry.value.instanceId == instanceId &&
                (permissionName == null || entry.value.permissionName == permissionName)
        }
        return before - records.size
    }
}

class FileBackedEnginePermissionGrantStore(
    private val file: File
) : EnginePermissionGrantStore {
    override fun get(instanceId: String, permissionName: String): EnginePermissionGrantRecord? = withFileLock {
        readRecords().firstOrNull {
            it.instanceId == instanceId && it.permissionName == permissionName
        }
    }

    override fun set(record: EnginePermissionGrantRecord): EnginePermissionGrantRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) {
            key(it.instanceId, it.permissionName)
        }
        records[key(record.instanceId, record.permissionName)] = record
        writeRecords(records.values.toList())
        record
    }

    override fun list(instanceId: String): List<EnginePermissionGrantRecord> = withFileLock {
        readRecords().filter { it.instanceId == instanceId }.sortedBy { it.permissionName }
    }

    override fun clear(instanceId: String, permissionName: String?): Int = withFileLock {
        val current = readRecords()
        val retained = current.filterNot {
            it.instanceId == instanceId && (permissionName == null || it.permissionName == permissionName)
        }
        if (retained.size != current.size) writeRecords(retained)
        current.size - retained.size
    }

    private fun readRecords(): List<EnginePermissionGrantRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        val count = properties.getProperty(RECORD_COUNT)?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { index ->
            runCatching {
                val prefix = "$RECORD_PREFIX.$index."
                EnginePermissionGrantRecord(
                    instanceId = properties.required(prefix + INSTANCE_ID),
                    permissionName = properties.required(prefix + PERMISSION_NAME),
                    granted = properties.required(prefix + GRANTED).toBooleanStrict(),
                    source = EnginePermissionGrantSource.valueOf(
                        properties.required(prefix + SOURCE)
                    ),
                    updatedAtMs = properties.required(prefix + UPDATED_AT_MS).toLong()
                )
            }.getOrNull()
        }
    }

    private fun writeRecords(records: List<EnginePermissionGrantRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedWith(
                compareBy(
                    EnginePermissionGrantRecord::instanceId,
                    EnginePermissionGrantRecord::permissionName
                )
            ).forEachIndexed { index, record ->
                val prefix = "$RECORD_PREFIX.$index."
                setProperty(prefix + INSTANCE_ID, record.instanceId)
                setProperty(prefix + PERMISSION_NAME, record.permissionName)
                setProperty(prefix + GRANTED, record.granted.toString())
                setProperty(prefix + SOURCE, record.source.name)
                setProperty(prefix + UPDATED_AT_MS, record.updatedAtMs.toString())
            }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MultiApp engine permission grants")
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
        const val PERMISSION_NAME = "permissionName"
        const val GRANTED = "granted"
        const val SOURCE = "source"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private fun key(instanceId: String, permissionName: String): String =
    "$instanceId\u0000$permissionName"

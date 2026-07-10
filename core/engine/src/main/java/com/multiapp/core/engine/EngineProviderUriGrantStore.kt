package com.multiapp.core.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

data class EngineProviderUriGrantRecord(
    val ownerInstanceId: String,
    val targetInstanceId: String,
    val targetPackageName: String,
    val guestAuthority: String,
    val encodedPath: String,
    val modeFlags: Int,
    val prefix: Boolean,
    val persistable: Boolean,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs
) {
    init {
        require(ownerInstanceId.isNotBlank()) { "ownerInstanceId must not be blank" }
        require(targetInstanceId.isNotBlank()) { "targetInstanceId must not be blank" }
        require(targetPackageName.isNotBlank()) { "targetPackageName must not be blank" }
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(encodedPath.startsWith('/')) { "encodedPath must be absolute" }
        require(modeFlags > 0) { "modeFlags must be positive" }
        require(createdAtMs > 0L) { "createdAtMs must be positive" }
        require(updatedAtMs >= createdAtMs) { "updatedAtMs must not precede createdAtMs" }
    }
}

interface EngineProviderUriGrantStore {
    fun grant(record: EngineProviderUriGrantRecord): EngineProviderUriGrantRecord
    fun revoke(
        ownerInstanceId: String,
        targetInstanceId: String?,
        guestAuthority: String,
        encodedPath: String,
        modeFlags: Int
    ): Int

    fun findGrant(
        ownerInstanceId: String,
        targetInstanceId: String,
        guestAuthority: String,
        encodedPath: String,
        requiredModeFlags: Int
    ): EngineProviderUriGrantRecord?

    fun listForInstance(instanceId: String): List<EngineProviderUriGrantRecord>
    fun clearInstance(instanceId: String)
}

object EngineProviderUriGrantFiles {
    const val DEFAULT_FILE_NAME = "engine_provider_uri_grants.properties"
}

class InMemoryEngineProviderUriGrantStore : EngineProviderUriGrantStore {
    private val records = linkedMapOf<String, EngineProviderUriGrantRecord>()

    @Synchronized
    override fun grant(record: EngineProviderUriGrantRecord): EngineProviderUriGrantRecord {
        val key = record.key()
        val previous = records[key]
        val merged = if (previous == null) {
            record
        } else {
            previous.copy(
                modeFlags = previous.modeFlags or record.modeFlags,
                persistable = previous.persistable || record.persistable,
                updatedAtMs = maxOf(previous.updatedAtMs, record.updatedAtMs)
            )
        }
        records[key] = merged
        return merged
    }

    @Synchronized
    override fun revoke(
        ownerInstanceId: String,
        targetInstanceId: String?,
        guestAuthority: String,
        encodedPath: String,
        modeFlags: Int
    ): Int = revokeRecords(
        records = records,
        ownerInstanceId = ownerInstanceId,
        targetInstanceId = targetInstanceId,
        guestAuthority = guestAuthority,
        encodedPath = encodedPath,
        modeFlags = modeFlags
    )

    @Synchronized
    override fun findGrant(
        ownerInstanceId: String,
        targetInstanceId: String,
        guestAuthority: String,
        encodedPath: String,
        requiredModeFlags: Int
    ): EngineProviderUriGrantRecord? = records.values
        .asSequence()
        .filter {
            it.ownerInstanceId == ownerInstanceId &&
                it.targetInstanceId == targetInstanceId &&
                it.guestAuthority == guestAuthority &&
                it.modeFlags and requiredModeFlags == requiredModeFlags &&
                it.matchesPath(encodedPath)
        }
        .maxByOrNull { it.encodedPath.length }

    @Synchronized
    override fun listForInstance(instanceId: String): List<EngineProviderUriGrantRecord> = records.values
        .filter { it.ownerInstanceId == instanceId || it.targetInstanceId == instanceId }
        .sortedWith(grantComparator)

    @Synchronized
    override fun clearInstance(instanceId: String) {
        records.entries.removeAll {
            it.value.ownerInstanceId == instanceId || it.value.targetInstanceId == instanceId
        }
    }
}

class FileBackedEngineProviderUriGrantStore(
    private val file: File
) : EngineProviderUriGrantStore {
    override fun grant(record: EngineProviderUriGrantRecord): EngineProviderUriGrantRecord = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        val previous = records[record.key()]
        val merged = if (previous == null) {
            record
        } else {
            previous.copy(
                modeFlags = previous.modeFlags or record.modeFlags,
                persistable = previous.persistable || record.persistable,
                updatedAtMs = maxOf(previous.updatedAtMs, record.updatedAtMs)
            )
        }
        records[merged.key()] = merged
        writeRecords(records.values.toList())
        merged
    }

    override fun revoke(
        ownerInstanceId: String,
        targetInstanceId: String?,
        guestAuthority: String,
        encodedPath: String,
        modeFlags: Int
    ): Int = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        val changed = revokeRecords(
            records,
            ownerInstanceId,
            targetInstanceId,
            guestAuthority,
            encodedPath,
            modeFlags
        )
        if (changed > 0) writeRecords(records.values.toList())
        changed
    }

    override fun findGrant(
        ownerInstanceId: String,
        targetInstanceId: String,
        guestAuthority: String,
        encodedPath: String,
        requiredModeFlags: Int
    ): EngineProviderUriGrantRecord? = withFileLock {
        readRecords()
            .asSequence()
            .filter {
                it.ownerInstanceId == ownerInstanceId &&
                    it.targetInstanceId == targetInstanceId &&
                    it.guestAuthority == guestAuthority &&
                    it.modeFlags and requiredModeFlags == requiredModeFlags &&
                    it.matchesPath(encodedPath)
            }
            .maxByOrNull { it.encodedPath.length }
    }

    override fun listForInstance(instanceId: String): List<EngineProviderUriGrantRecord> = withFileLock {
        readRecords()
            .filter { it.ownerInstanceId == instanceId || it.targetInstanceId == instanceId }
            .sortedWith(grantComparator)
    }

    override fun clearInstance(instanceId: String) = withFileLock {
        writeRecords(
            readRecords().filterNot {
                it.ownerInstanceId == instanceId || it.targetInstanceId == instanceId
            }
        )
    }

    private fun readRecords(): List<EngineProviderUriGrantRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return (0 until properties.int(RECORD_COUNT)).mapNotNull { index ->
            runCatching {
                val prefix = "$RECORD_PREFIX.$index."
                EngineProviderUriGrantRecord(
                    ownerInstanceId = properties.required(prefix + OWNER_INSTANCE_ID),
                    targetInstanceId = properties.required(prefix + TARGET_INSTANCE_ID),
                    targetPackageName = properties.required(prefix + TARGET_PACKAGE_NAME),
                    guestAuthority = properties.required(prefix + GUEST_AUTHORITY),
                    encodedPath = properties.required(prefix + ENCODED_PATH),
                    modeFlags = properties.required(prefix + MODE_FLAGS).toInt(),
                    prefix = properties.required(prefix + PREFIX).toBooleanStrict(),
                    persistable = properties.required(prefix + PERSISTABLE).toBooleanStrict(),
                    createdAtMs = properties.required(prefix + CREATED_AT_MS).toLong(),
                    updatedAtMs = properties.required(prefix + UPDATED_AT_MS).toLong()
                )
            }.getOrNull()
        }
    }

    private fun writeRecords(records: List<EngineProviderUriGrantRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedWith(grantComparator).forEachIndexed { index, record ->
                val prefix = "$RECORD_PREFIX.$index."
                setProperty(prefix + OWNER_INSTANCE_ID, record.ownerInstanceId)
                setProperty(prefix + TARGET_INSTANCE_ID, record.targetInstanceId)
                setProperty(prefix + TARGET_PACKAGE_NAME, record.targetPackageName)
                setProperty(prefix + GUEST_AUTHORITY, record.guestAuthority)
                setProperty(prefix + ENCODED_PATH, record.encodedPath)
                setProperty(prefix + MODE_FLAGS, record.modeFlags.toString())
                setProperty(prefix + PREFIX, record.prefix.toString())
                setProperty(prefix + PERSISTABLE, record.persistable.toString())
                setProperty(prefix + CREATED_AT_MS, record.createdAtMs.toString())
                setProperty(prefix + UPDATED_AT_MS, record.updatedAtMs.toString())
            }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MultiApp engine Provider URI grants")
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
        const val OWNER_INSTANCE_ID = "ownerInstanceId"
        const val TARGET_INSTANCE_ID = "targetInstanceId"
        const val TARGET_PACKAGE_NAME = "targetPackageName"
        const val GUEST_AUTHORITY = "guestAuthority"
        const val ENCODED_PATH = "encodedPath"
        const val MODE_FLAGS = "modeFlags"
        const val PREFIX = "prefix"
        const val PERSISTABLE = "persistable"
        const val CREATED_AT_MS = "createdAtMs"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private val grantComparator = compareBy(
    EngineProviderUriGrantRecord::ownerInstanceId,
    EngineProviderUriGrantRecord::targetInstanceId,
    EngineProviderUriGrantRecord::guestAuthority,
    EngineProviderUriGrantRecord::encodedPath,
    EngineProviderUriGrantRecord::prefix
)

private fun EngineProviderUriGrantRecord.key(): String = listOf(
    ownerInstanceId,
    targetInstanceId,
    guestAuthority,
    encodedPath,
    prefix.toString()
).joinToString("\u0000")

private fun EngineProviderUriGrantRecord.matchesPath(requestedPath: String): Boolean =
    encodedPath == requestedPath || prefix && requestedPath.isSameOrDescendantOf(encodedPath)

private fun String.isSameOrDescendantOf(parent: String): Boolean =
    this == parent || when {
        parent == "/" -> startsWith('/')
        else -> startsWith(parent.trimEnd('/') + "/")
    }

private fun revokeRecords(
    records: MutableMap<String, EngineProviderUriGrantRecord>,
    ownerInstanceId: String,
    targetInstanceId: String?,
    guestAuthority: String,
    encodedPath: String,
    modeFlags: Int
): Int {
    if (modeFlags <= 0) return 0
    var changed = 0
    val replacements = mutableListOf<EngineProviderUriGrantRecord>()
    val iterator = records.entries.iterator()
    while (iterator.hasNext()) {
        val record = iterator.next().value
        if (
            record.ownerInstanceId != ownerInstanceId ||
            targetInstanceId != null && record.targetInstanceId != targetInstanceId ||
            record.guestAuthority != guestAuthority ||
            !record.encodedPath.isSameOrDescendantOf(encodedPath)
        ) {
            continue
        }
        val remainingModes = record.modeFlags and modeFlags.inv()
        if (remainingModes == record.modeFlags) continue
        iterator.remove()
        changed += 1
        if (remainingModes > 0) {
            replacements += record.copy(modeFlags = remainingModes, updatedAtMs = System.currentTimeMillis())
        }
    }
    replacements.forEach { records[it.key()] = it }
    return changed
}

internal fun normalizeProviderGrantPath(encodedPath: String?): String =
    encodedPath?.takeIf { it.isNotBlank() }?.let { if (it.startsWith('/')) it else "/$it" } ?: "/"

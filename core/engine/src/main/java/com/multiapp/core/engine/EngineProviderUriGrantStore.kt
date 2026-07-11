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
    val persistedModeFlags: Int = 0,
    val persistedAtMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs
) {
    init {
        require(ownerInstanceId.isNotBlank()) { "ownerInstanceId must not be blank" }
        require(targetInstanceId.isNotBlank()) { "targetInstanceId must not be blank" }
        require(targetPackageName.isNotBlank()) { "targetPackageName must not be blank" }
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(encodedPath.startsWith('/')) { "encodedPath must be absolute" }
        require(modeFlags > 0 || persistedModeFlags > 0) {
            "transient or persisted modeFlags must be positive"
        }
        require(persistedModeFlags and EngineProviderUriGrantModes.ACCESS_MASK == persistedModeFlags) {
            "persistedModeFlags must contain only read/write access modes"
        }
        require(persistedModeFlags == 0 || persistable) {
            "persisted modes require a persistable grant"
        }
        require((persistedModeFlags == 0) == (persistedAtMs == null)) {
            "persistedAtMs must match persistedModeFlags"
        }
        require(persistedAtMs == null || persistedAtMs > 0L) { "persistedAtMs must be positive" }
        require(createdAtMs > 0L) { "createdAtMs must be positive" }
        require(updatedAtMs >= createdAtMs) { "updatedAtMs must not precede createdAtMs" }
    }
}

interface EngineProviderUriGrantStore {
    fun grant(record: EngineProviderUriGrantRecord): EngineProviderUriGrantRecord
    fun takePersistable(
        record: EngineProviderUriGrantRecord,
        modeFlags: Int,
        persistedAtMs: Long
    ): EngineProviderUriGrantRecord?

    fun releasePersistable(
        record: EngineProviderUriGrantRecord,
        modeFlags: Int,
        updatedAtMs: Long
    ): EngineProviderUriGrantRecord?

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
    fun listPersistedForTarget(instanceId: String): List<EngineProviderUriGrantRecord>
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
    override fun takePersistable(
        record: EngineProviderUriGrantRecord,
        modeFlags: Int,
        persistedAtMs: Long
    ): EngineProviderUriGrantRecord? = updatePersistedGrant(
        records = records,
        record = record,
        modeFlags = modeFlags,
        take = true,
        timestampMs = persistedAtMs
    )

    @Synchronized
    override fun releasePersistable(
        record: EngineProviderUriGrantRecord,
        modeFlags: Int,
        updatedAtMs: Long
    ): EngineProviderUriGrantRecord? = updatePersistedGrant(
        records = records,
        record = record,
        modeFlags = modeFlags,
        take = false,
        timestampMs = updatedAtMs
    )

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
                it.effectiveModeFlags() and requiredModeFlags == requiredModeFlags &&
                it.matchesPath(encodedPath)
        }
        .maxByOrNull { it.encodedPath.length }

    @Synchronized
    override fun listForInstance(instanceId: String): List<EngineProviderUriGrantRecord> = records.values
        .filter { it.ownerInstanceId == instanceId || it.targetInstanceId == instanceId }
        .sortedWith(grantComparator)

    @Synchronized
    override fun listPersistedForTarget(instanceId: String): List<EngineProviderUriGrantRecord> = records.values
        .filter { it.targetInstanceId == instanceId && it.persistedModeFlags > 0 }
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

    override fun takePersistable(
        record: EngineProviderUriGrantRecord,
        modeFlags: Int,
        persistedAtMs: Long
    ): EngineProviderUriGrantRecord? = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        updatePersistedGrant(records, record, modeFlags, take = true, timestampMs = persistedAtMs)
            ?.also { writeRecords(records.values.toList()) }
    }

    override fun releasePersistable(
        record: EngineProviderUriGrantRecord,
        modeFlags: Int,
        updatedAtMs: Long
    ): EngineProviderUriGrantRecord? = withFileLock {
        val records = readRecords().associateByTo(linkedMapOf()) { it.key() }
        updatePersistedGrant(records, record, modeFlags, take = false, timestampMs = updatedAtMs)
            ?.also { writeRecords(records.values.toList()) }
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

    override fun listPersistedForTarget(instanceId: String): List<EngineProviderUriGrantRecord> = withFileLock {
        readRecords()
            .filter { it.targetInstanceId == instanceId && it.persistedModeFlags > 0 }
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
                    persistedModeFlags = properties.int(prefix + PERSISTED_MODE_FLAGS),
                    persistedAtMs = properties.longOrNull(prefix + PERSISTED_AT_MS),
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
                setProperty(prefix + PERSISTED_MODE_FLAGS, record.persistedModeFlags.toString())
                record.persistedAtMs?.let { setProperty(prefix + PERSISTED_AT_MS, it.toString()) }
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

    private fun Properties.longOrNull(key: String): Long? = getProperty(key)?.toLongOrNull()

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
        const val PERSISTED_MODE_FLAGS = "persistedModeFlags"
        const val PERSISTED_AT_MS = "persistedAtMs"
        const val CREATED_AT_MS = "createdAtMs"
        const val UPDATED_AT_MS = "updatedAtMs"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

private fun EngineProviderUriGrantRecord.effectiveModeFlags(): Int = modeFlags or persistedModeFlags

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

internal fun EngineProviderUriGrantRecord.matchesPath(requestedPath: String): Boolean =
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
        val remainingPersistedModes = record.persistedModeFlags and modeFlags.inv()
        if (
            remainingModes == record.modeFlags &&
            remainingPersistedModes == record.persistedModeFlags
        ) {
            continue
        }
        iterator.remove()
        changed += 1
        if (remainingModes > 0 || remainingPersistedModes > 0) {
            replacements += record.copy(
                modeFlags = remainingModes,
                persistedModeFlags = remainingPersistedModes,
                persistedAtMs = record.persistedAtMs.takeIf { remainingPersistedModes > 0 },
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }
    replacements.forEach { records[it.key()] = it }
    return changed
}

private fun updatePersistedGrant(
    records: MutableMap<String, EngineProviderUriGrantRecord>,
    record: EngineProviderUriGrantRecord,
    modeFlags: Int,
    take: Boolean,
    timestampMs: Long
): EngineProviderUriGrantRecord? {
    val requestedModes = modeFlags and EngineProviderUriGrantModes.ACCESS_MASK
    if (requestedModes == 0 || timestampMs <= 0L) return null
    val current = records[record.key()] ?: return null
    val persistedModes = if (take) {
        if (!current.persistable || current.modeFlags and requestedModes != requestedModes) return null
        current.persistedModeFlags or requestedModes
    } else {
        if (current.persistedModeFlags and requestedModes == 0) return null
        current.persistedModeFlags and requestedModes.inv()
    }
    val updated = current.copy(
        persistedModeFlags = persistedModes,
        persistedAtMs = timestampMs.takeIf { persistedModes > 0 },
        updatedAtMs = maxOf(current.updatedAtMs, timestampMs)
    )
    if (updated.modeFlags == 0 && updated.persistedModeFlags == 0) {
        records.remove(current.key())
    } else {
        records[current.key()] = updated
    }
    return updated
}

internal fun normalizeProviderGrantPath(encodedPath: String?): String =
    encodedPath?.takeIf { it.isNotBlank() }?.let { if (it.startsWith('/')) it else "/$it" } ?: "/"

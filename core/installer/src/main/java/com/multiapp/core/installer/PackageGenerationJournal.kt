package com.multiapp.core.installer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

interface PackageGenerationTransactionJournal {
    fun begin(
        packageName: String,
        creationRequestId: String,
        payloadFingerprint: String
    ): PackageGenerationTransaction

    companion object {
        val NO_OP: PackageGenerationTransactionJournal = object : PackageGenerationTransactionJournal {
            override fun begin(
                packageName: String,
                creationRequestId: String,
                payloadFingerprint: String
            ): PackageGenerationTransaction = PackageGenerationTransaction.NO_OP
        }
    }
}

interface PackageGenerationTransaction {
    /** Removes the durable marker after an authoritative engine response. */
    fun complete(): Boolean

    /** Leaves the marker for startup recovery when the engine result is unknown. */
    fun abandon()

    companion object {
        val NO_OP: PackageGenerationTransaction = object : PackageGenerationTransaction {
            override fun complete(): Boolean = true
            override fun abandon() = Unit
        }
    }
}

@Singleton
class PackageGenerationJournal internal constructor(
    private val journalDir: File,
    private val clock: () -> Long,
    private val transactionIdFactory: () -> String,
    private val faultInjector: PackageGenerationFaultInjector
) : PackageGenerationTransactionJournal {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        journalDir = PackageGenerationLayout.fromFilesDir(context.filesDir).journalDir,
        clock = System::currentTimeMillis,
        transactionIdFactory = { UUID.randomUUID().toString() },
        faultInjector = PackageGenerationFaultInjector.NONE
    )

    override fun begin(
        packageName: String,
        creationRequestId: String,
        payloadFingerprint: String
    ): PackageGenerationTransaction {
        requireSafePackageName(packageName)
        require(creationRequestId.isNotBlank()) { "creationRequestId must not be blank" }
        require(payloadFingerprint.matches(SHA_256_PATTERN)) {
            "payloadFingerprint must be a lowercase SHA-256 digest"
        }

        val root = requireOwnedDirectory(journalDir, "package generation journal")
        val transactionId = transactionIdFactory()
        require(transactionId.matches(SAFE_TRANSACTION_ID_PATTERN)) {
            "Invalid package generation transaction id"
        }
        val target = requireDirectChild(root, File(root, "generation-$transactionId.journal"))
        val temp = requireDirectChild(root, File(root, "${target.name}.tmp"))
        check(!target.exists() && !temp.exists()) {
            "Package generation transaction id already exists"
        }

        val properties = Properties().apply {
            setProperty("schemaVersion", JOURNAL_SCHEMA_VERSION.toString())
            setProperty("transactionId", transactionId)
            setProperty("packageName", packageName)
            setProperty("creationRequestId", creationRequestId)
            setProperty("payloadFingerprint", payloadFingerprint)
            setProperty("startedAtMs", clock().toString())
            setProperty("phase", "PREPARED")
        }

        try {
            FileOutputStream(temp, false).use { output ->
                properties.store(output, null)
                output.flush()
                output.fd.sync()
            }
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_JOURNAL_TEMP_SYNCED)
            moveAtomically(temp, target)
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_JOURNAL_PUBLISHED)
        } catch (error: Exception) {
            deleteOwnedFileQuietly(temp, root)
            throw IllegalStateException("Unable to persist package generation journal", error)
        }

        return FilePackageGenerationTransaction(target, root, faultInjector)
    }

    private class FilePackageGenerationTransaction(
        private val journalFile: File,
        private val journalRoot: File,
        private val faultInjector: PackageGenerationFaultInjector
    ) : PackageGenerationTransaction {
        private val closed = AtomicBoolean(false)

        override fun complete(): Boolean {
            if (!closed.compareAndSet(false, true)) return !journalFile.exists()
            val deleted = deleteOwnedFile(journalFile, journalRoot)
            if (deleted) {
                faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_JOURNAL_DELETED)
            }
            return deleted
        }

        override fun abandon() {
            closed.compareAndSet(false, true)
        }
    }
}

internal data class PackageGenerationLayout(
    val installRecordDir: File,
    val artifactDir: File,
    val journalDir: File
) {
    companion object {
        const val INSTALL_RECORDS_DIR = "installs"
        const val ARTIFACTS_DIR = "artifacts"
        const val JOURNAL_DIR = "package_generation_journal"

        fun fromFilesDir(filesDir: File): PackageGenerationLayout = PackageGenerationLayout(
            installRecordDir = File(filesDir, INSTALL_RECORDS_DIR),
            artifactDir = File(filesDir, ARTIFACTS_DIR),
            journalDir = File(filesDir, JOURNAL_DIR)
        )
    }
}

internal enum class PackageGenerationFaultPoint {
    AFTER_JOURNAL_TEMP_SYNCED,
    AFTER_JOURNAL_PUBLISHED,
    AFTER_JOURNAL_DELETED,
    AFTER_RECORD_TEMP_PROMOTED,
    AFTER_RECORD_BACKUP_RESTORED,
    AFTER_TOMBSTONE_RESTORED,
    AFTER_STAGING_DELETED,
    AFTER_TOMBSTONE_DELETED,
    AFTER_ORPHAN_DELETED
}

internal fun interface PackageGenerationFaultInjector {
    fun onFaultPoint(point: PackageGenerationFaultPoint)

    companion object {
        val NONE = PackageGenerationFaultInjector { }
    }
}

internal val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
private val SAFE_TRANSACTION_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
private const val JOURNAL_SCHEMA_VERSION = 1

internal fun requireSafePackageName(packageName: String) {
    require(packageName.isNotBlank()) { "packageName must not be blank" }
    require(!packageName.contains("..") && !packageName.contains('/') && !packageName.contains('\\')) {
        "Invalid packageName: $packageName"
    }
}

internal fun requireOwnedDirectory(directory: File, description: String): File {
    val absolute = directory.absoluteFile
    require(!Files.isSymbolicLink(absolute.toPath())) { "$description directory must not be a symlink" }
    if (!absolute.exists() && !absolute.mkdirs()) {
        throw IllegalStateException("Unable to create $description directory")
    }
    require(absolute.isDirectory) { "$description path is not a directory" }
    return absolute.canonicalFile
}

internal fun requireDirectChild(root: File, file: File): File {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = file.canonicalFile
    require(canonicalFile.parentFile == canonicalRoot) { "Path escapes owned directory" }
    return canonicalFile
}

internal fun moveAtomically(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        if (!source.renameTo(target)) {
            throw IllegalStateException("Unable to move ${source.name} to ${target.name}")
        }
    }
}

internal fun deleteOwnedFile(file: File, root: File): Boolean {
    val absolute = file.absoluteFile
    if (!absolute.exists() && !Files.isSymbolicLink(absolute.toPath())) return true
    if (Files.isSymbolicLink(absolute.toPath())) return false
    val owned = runCatching { absolute.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
    if (!owned || !absolute.isFile) return false
    absolute.setWritable(true, false)
    return absolute.delete()
}

private fun deleteOwnedFileQuietly(file: File, root: File) {
    runCatching { deleteOwnedFile(file, root) }
}

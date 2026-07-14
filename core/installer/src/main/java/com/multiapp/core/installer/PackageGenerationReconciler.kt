package com.multiapp.core.installer

import com.google.gson.Gson
import com.multiapp.core.model.installer.InstallRecord
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Properties

data class PackageGenerationReconcileResult(
    val success: Boolean,
    val recordsEnumerated: Int,
    val recordFilesRecovered: Int,
    val stagingFilesDeleted: Int,
    val tombstonesRestored: Int,
    val tombstonesDeleted: Int,
    val orphanArtifactsDeleted: Int,
    val abandonedJournalsDeleted: Int,
    val orphanGcSkipped: Boolean,
    val errors: List<String>
)

/**
 * Repairs the file boundaries used by package generation before the engine is created.
 * Install records remain authoritative; content-addressed APKs are collected only after
 * every record has been enumerated and its referenced bytes have passed SHA-256 checks.
 */
class PackageGenerationReconciler internal constructor(
    private val layout: PackageGenerationLayout,
    private val directoryLister: PackageGenerationDirectoryLister,
    private val faultInjector: PackageGenerationFaultInjector
) {
    constructor(
        installRecordDir: File,
        artifactDir: File,
        journalDir: File
    ) : this(
        layout = PackageGenerationLayout(installRecordDir, artifactDir, journalDir),
        directoryLister = PackageGenerationDirectoryLister.DEFAULT,
        faultInjector = PackageGenerationFaultInjector.NONE
    )

    fun reconcile(): PackageGenerationReconcileResult {
        val state = ReconcileState()
        try {
            val installRoot = requireOwnedDirectory(layout.installRecordDir, "install record")
            val artifactRoot = requireOwnedDirectory(layout.artifactDir, "package artifact")
            val journalRoot = requireOwnedDirectory(layout.journalDir, "package generation journal")
            val lockFile = requireDirectChild(journalRoot, File(journalRoot, RECONCILE_LOCK_FILE))
            require(!Files.isSymbolicLink(lockFile.toPath())) {
                "Package generation reconcile lock must not be a symlink"
            }

            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use {
                    reconcileLocked(installRoot, artifactRoot, journalRoot, state)
                }
            }
        } catch (error: Exception) {
            state.fail(error.message ?: error.javaClass.simpleName)
        }
        return state.toResult()
    }

    private fun reconcileLocked(
        installRoot: File,
        artifactRoot: File,
        journalRoot: File,
        state: ReconcileState
    ) {
        val journals = listJournalEntries(journalRoot, state) ?: return
        val artifactEntries = listOwnedEntries(artifactRoot, "artifact", state) ?: return
        val inventory = buildArtifactInventory(artifactRoot, artifactEntries, state)
        val recordEntries = listOwnedEntries(installRoot, "install record", state) ?: return
        val records = recoverAndEnumerateRecords(
            installRoot = installRoot,
            artifactRoot = artifactRoot,
            recordEntries = recordEntries,
            inventory = inventory,
            abandonedPackages = journals.abandonedPackages,
            state = state
        )
        if (records == null || state.errors.isNotEmpty()) return

        val references = mergeReferences(records, state) ?: return
        restoreReferencedTombstones(artifactRoot, inventory, references, state)
        if (state.errors.isNotEmpty()) return
        if (!validateFinalReferences(artifactRoot, references, state)) return

        deleteStagingFiles(inventory.stagingFiles, artifactRoot, state)
        deleteAbandonedTombstones(inventory.tombstones, artifactRoot, state)
        if (state.errors.isNotEmpty()) return

        val refreshedEntries = listOwnedEntries(artifactRoot, "artifact", state) ?: return
        val orphanCandidates = collectVerifiedOrphans(
            artifactRoot = artifactRoot,
            entries = refreshedEntries,
            referencedPaths = references.keys,
            state = state
        ) ?: return

        state.orphanGcSkipped = false
        orphanCandidates.forEach { orphan ->
            if (!deleteOwnedFile(orphan, artifactRoot)) {
                state.fail("Unable to delete orphan artifact ${orphan.name}")
                return@forEach
            }
            state.orphanArtifactsDeleted += 1
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_ORPHAN_DELETED)
        }
        if (state.errors.isNotEmpty()) return

        journals.files.forEach { journal ->
            if (!deleteOwnedFile(journal, journalRoot)) {
                state.fail("Unable to delete abandoned journal ${journal.name}")
                return@forEach
            }
            state.abandonedJournalsDeleted += 1
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_JOURNAL_DELETED)
        }
    }

    private fun listJournalEntries(root: File, state: ReconcileState): JournalInventory? {
        val entries = listOwnedEntries(root, "package generation journal", state) ?: return null
        val journals = mutableListOf<File>()
        val abandonedPackages = linkedSetOf<String>()
        entries.forEach { entry ->
            if (!JOURNAL_FILE_PATTERN.matches(entry.name)) return@forEach
            if (!isSafeRegularEntry(entry, root)) {
                state.fail("Unsafe package generation journal ${entry.name}")
            } else {
                val canonical = entry.canonicalFile
                journals += canonical
                if (!entry.name.endsWith(".tmp")) {
                    val packageName = readPublishedJournalPackage(canonical)
                    if (packageName == null) {
                        state.fail("Invalid package generation journal ${entry.name}")
                    } else {
                        abandonedPackages += packageName
                    }
                }
            }
        }
        return JournalInventory(journals, abandonedPackages).takeIf { state.errors.isEmpty() }
    }

    private fun readPublishedJournalPackage(file: File): String? = runCatching {
        val properties = Properties().apply {
            file.inputStream().use { input -> load(input) }
        }
        require(properties.getProperty("schemaVersion") == JOURNAL_SCHEMA_VERSION.toString())
        require(properties.getProperty("phase") == "PREPARED")
        require(properties.getProperty("transactionId").orEmpty().matches(SAFE_JOURNAL_ID_PATTERN))
        require(properties.getProperty("creationRequestId").orEmpty().isNotBlank())
        require(properties.getProperty("payloadFingerprint").orEmpty().matches(SHA_256_PATTERN))
        require(properties.getProperty("startedAtMs").orEmpty().toLong() >= 0L)
        val packageName = requireNotNull(properties.getProperty("packageName"))
        requireSafePackageName(packageName)
        packageName
    }.getOrNull()

    private fun listOwnedEntries(
        root: File,
        description: String,
        state: ReconcileState
    ): Array<File>? {
        val entries = runCatching { directoryLister.list(root) }.getOrElse { error ->
            state.fail("Unable to enumerate $description directory: ${error.message}")
            return null
        }
        if (entries == null) {
            state.fail("Unable to enumerate $description directory")
            return null
        }
        return entries
    }

    private fun buildArtifactInventory(
        artifactRoot: File,
        entries: Array<File>,
        state: ReconcileState
    ): ArtifactInventory {
        val staging = mutableListOf<File>()
        val tombstones = mutableListOf<ArtifactTombstone>()

        entries.forEach { entry ->
            val relevant = entry.name.endsWith(".apk") ||
                STAGING_FILE_PATTERN.matches(entry.name) ||
                TOMBSTONE_FILE_PATTERN.matches(entry.name)
            if (!relevant) return@forEach
            if (!isSafeRegularEntry(entry, artifactRoot)) {
                state.fail("Unsafe package artifact entry ${entry.name}")
                return@forEach
            }
            val canonical = entry.canonicalFile
            when {
                STAGING_FILE_PATTERN.matches(entry.name) -> staging += canonical
                TOMBSTONE_FILE_PATTERN.matches(entry.name) -> {
                    val match = checkNotNull(TOMBSTONE_FILE_PATTERN.matchEntire(entry.name))
                    val originalName = match.groupValues[1]
                    if (!CONTENT_ADDRESSED_APK_PATTERN.matches(originalName)) {
                        state.fail("Invalid package artifact tombstone ${entry.name}")
                    } else {
                        tombstones += ArtifactTombstone(canonical, originalName)
                    }
                }
                entry.name.endsWith(".apk") -> Unit
            }
        }
        return ArtifactInventory(staging, tombstones)
    }

    private fun recoverAndEnumerateRecords(
        installRoot: File,
        artifactRoot: File,
        recordEntries: Array<File>,
        inventory: ArtifactInventory,
        abandonedPackages: Set<String>,
        state: ReconcileState
    ): List<ValidatedRecord>? {
        val groups = linkedMapOf<String, RecordFileGroup>()
        recordEntries.forEach { entry ->
            val match = RECORD_FILE_PATTERN.matchEntire(entry.name) ?: return@forEach
            val packageName = match.groupValues[1]
            if (runCatching { requireSafePackageName(packageName) }.isFailure) {
                state.fail("Unsafe install record filename ${entry.name}")
                return@forEach
            }
            if (!isSafeRegularEntry(entry, installRoot)) {
                state.fail("Unsafe install record entry ${entry.name}")
                return@forEach
            }
            val group = groups.getOrPut(packageName) { RecordFileGroup(packageName) }
            when (match.groupValues[2]) {
                "tmp" -> group.temp = entry.canonicalFile
                "bak" -> group.backup = entry.canonicalFile
                else -> group.target = entry.canonicalFile
            }
        }
        if (state.errors.isNotEmpty()) return null

        val records = mutableListOf<ValidatedRecord>()
        groups.values.sortedBy { it.packageName }.forEach { group ->
            val target = validateRecordCandidate(group.target, group.packageName, artifactRoot, inventory)
            val temp = validateRecordCandidate(group.temp, group.packageName, artifactRoot, inventory)
            val backup = validateRecordCandidate(group.backup, group.packageName, artifactRoot, inventory)
            val selected = when {
                target != null -> {
                    deleteSidecar(group.temp, installRoot, state)
                    deleteSidecar(group.backup, installRoot, state)
                    target
                }
                temp != null -> {
                    if (!removeInvalidTarget(group.target, installRoot, state)) return@forEach
                    val targetFile = requireDirectChild(installRoot, File(installRoot, "${group.packageName}.json"))
                    moveAtomically(checkNotNull(group.temp), targetFile)
                    state.recordFilesRecovered += 1
                    faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_RECORD_TEMP_PROMOTED)
                    deleteSidecar(group.backup, installRoot, state)
                    temp
                }
                backup != null -> {
                    if (!removeInvalidTarget(group.target, installRoot, state)) return@forEach
                    val targetFile = requireDirectChild(installRoot, File(installRoot, "${group.packageName}.json"))
                    moveAtomically(checkNotNull(group.backup), targetFile)
                    state.recordFilesRecovered += 1
                    faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_RECORD_BACKUP_RESTORED)
                    deleteSidecar(group.temp, installRoot, state)
                    backup
                }
                else -> {
                    val abandonedFirstInstall = group.packageName in abandonedPackages &&
                        group.target == null && group.backup == null && group.temp != null
                    if (abandonedFirstInstall) {
                        deleteSidecar(group.temp, installRoot, state)
                    } else {
                        state.fail("No valid install record generation for ${group.packageName}")
                    }
                    null
                }
            }
            if (selected != null) records += selected
        }
        if (state.errors.isNotEmpty()) return null
        state.recordsEnumerated = records.size
        return records
    }

    private fun validateRecordCandidate(
        file: File?,
        expectedPackageName: String,
        artifactRoot: File,
        inventory: ArtifactInventory
    ): ValidatedRecord? {
        if (file == null) return null
        return runCatching {
            val record = gson.fromJson(file.readText(Charsets.UTF_8), InstallRecord::class.java)
                ?: return null
            validateRecordStructure(record, expectedPackageName)
            val references = recordArtifactReferences(record, artifactRoot)
            references.forEach { (path, digest) ->
                requireArtifactAvailable(path, digest, artifactRoot, inventory)
            }
            ValidatedRecord(references)
        }.getOrNull()
    }

    private fun validateRecordStructure(record: InstallRecord, expectedPackageName: String) {
        require(record.schemaVersion == INSTALL_RECORD_SCHEMA_VERSION) { "Unsupported install record schema" }
        require(record.packageName == expectedPackageName) { "Install record package mismatch" }
        requireSafePackageName(record.packageName)
        require(record.originApkPath.isNotBlank()) { "Missing base APK path" }
        require(record.originApkSha256.matches(SHA_256_PATTERN)) { "Invalid base APK digest" }
        require(record.versionCode > 0 && record.versionName.isNotBlank()) { "Invalid package version" }
        require(record.targetSdk > 0 && record.minSdk > 0) { "Invalid package SDK range" }
        require(record.splitApkPaths.size == record.splitApkSha256s.size) {
            "Split APK digest count mismatch"
        }
        require(record.splitApkPaths.none { it.isBlank() }) { "Blank split APK path" }
        require(record.splitApkSha256s.all { it.matches(SHA_256_PATTERN) }) {
            "Invalid split APK digest"
        }
    }

    private fun recordArtifactReferences(record: InstallRecord, artifactRoot: File): Map<String, String> {
        val references = linkedMapOf<String, String>()
        addRecordReference(references, record.originApkPath, record.originApkSha256, artifactRoot)
        record.splitApkPaths.forEachIndexed { index, path ->
            addRecordReference(references, path, record.splitApkSha256s[index], artifactRoot)
        }
        return references
    }

    private fun addRecordReference(
        references: MutableMap<String, String>,
        path: String,
        digest: String,
        artifactRoot: File
    ) {
        val raw = File(path)
        require(raw.isAbsolute) { "Install record artifact path must be absolute" }
        require(!Files.isSymbolicLink(raw.toPath())) { "Install record artifact must not be a symlink" }
        val canonical = raw.canonicalFile
        require(canonical.parentFile == artifactRoot) { "Install record artifact escapes artifact root" }
        require(canonical.name.endsWith("-$digest.apk")) { "Artifact filename does not match digest" }
        val previous = references.put(canonical.absolutePath, digest)
        require(previous == null || previous == digest) { "Conflicting artifact digest references" }
    }

    private fun requireArtifactAvailable(
        path: String,
        digest: String,
        artifactRoot: File,
        inventory: ArtifactInventory
    ) {
        val canonical = File(path).canonicalFile
        if (isSafeRegularEntry(canonical, artifactRoot)) {
            require(computeSha256(canonical) == digest) { "Artifact digest mismatch" }
            return
        }
        require(!canonical.exists() && !Files.isSymbolicLink(canonical.toPath())) {
            "Unsafe or missing package artifact"
        }
        val tombstones = inventory.tombstones.filter { it.originalName == canonical.name }
        require(tombstones.size == 1) { "Referenced artifact has no unique recovery tombstone" }
        require(computeSha256(tombstones.single().file) == digest) { "Tombstone digest mismatch" }
    }

    private fun removeInvalidTarget(target: File?, root: File, state: ReconcileState): Boolean {
        if (target == null || (!target.exists() && !Files.isSymbolicLink(target.toPath()))) return true
        if (!deleteOwnedFile(target, root)) {
            state.fail("Unable to remove invalid install record ${target.name}")
            return false
        }
        return true
    }

    private fun deleteSidecar(sidecar: File?, root: File, state: ReconcileState) {
        if (sidecar == null || (!sidecar.exists() && !Files.isSymbolicLink(sidecar.toPath()))) return
        if (!deleteOwnedFile(sidecar, root)) {
            state.fail("Unable to delete install record sidecar ${sidecar.name}")
        }
    }

    private fun mergeReferences(
        records: List<ValidatedRecord>,
        state: ReconcileState
    ): Map<String, String>? {
        val merged = linkedMapOf<String, String>()
        records.forEach { record ->
            record.references.forEach { (path, digest) ->
                val previous = merged.put(path, digest)
                if (previous != null && previous != digest) {
                    state.fail("Conflicting record digests for ${File(path).name}")
                }
            }
        }
        return merged.takeIf { state.errors.isEmpty() }
    }

    private fun restoreReferencedTombstones(
        artifactRoot: File,
        inventory: ArtifactInventory,
        references: Map<String, String>,
        state: ReconcileState
    ) {
        references.forEach { (path, digest) ->
            val target = File(path)
            if (isSafeRegularEntry(target, artifactRoot)) return@forEach
            if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                state.fail("Unsafe referenced artifact ${target.name}")
                return@forEach
            }
            val tombstones = inventory.tombstones.filter { it.originalName == target.name && it.file.exists() }
            if (tombstones.size != 1 || computeSha256(tombstones.single().file) != digest) {
                state.fail("Unable to restore referenced artifact ${target.name}")
                return@forEach
            }
            moveAtomically(tombstones.single().file, target)
            state.tombstonesRestored += 1
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_TOMBSTONE_RESTORED)
        }
    }

    private fun validateFinalReferences(
        artifactRoot: File,
        references: Map<String, String>,
        state: ReconcileState
    ): Boolean {
        references.forEach { (path, digest) ->
            val file = File(path)
            if (!isSafeRegularEntry(file, artifactRoot)) {
                state.fail("Referenced package artifact is unavailable: ${file.name}")
            } else if (computeSha256(file) != digest) {
                state.fail("Referenced package artifact digest mismatch: ${file.name}")
            }
        }
        return state.errors.isEmpty()
    }

    private fun deleteStagingFiles(files: List<File>, root: File, state: ReconcileState) {
        files.forEach { file ->
            if (!file.exists()) return@forEach
            if (!deleteOwnedFile(file, root)) {
                state.fail("Unable to delete abandoned staging file ${file.name}")
                return@forEach
            }
            state.stagingFilesDeleted += 1
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_STAGING_DELETED)
        }
    }

    private fun deleteAbandonedTombstones(
        tombstones: List<ArtifactTombstone>,
        root: File,
        state: ReconcileState
    ) {
        tombstones.forEach { tombstone ->
            if (!tombstone.file.exists()) return@forEach
            if (!deleteOwnedFile(tombstone.file, root)) {
                state.fail("Unable to delete abandoned tombstone ${tombstone.file.name}")
                return@forEach
            }
            state.tombstonesDeleted += 1
            faultInjector.onFaultPoint(PackageGenerationFaultPoint.AFTER_TOMBSTONE_DELETED)
        }
    }

    private fun collectVerifiedOrphans(
        artifactRoot: File,
        entries: Array<File>,
        referencedPaths: Set<String>,
        state: ReconcileState
    ): List<File>? {
        val candidates = mutableListOf<File>()
        entries.forEach { entry ->
            if (!CONTENT_ADDRESSED_APK_PATTERN.matches(entry.name)) return@forEach
            if (!isSafeRegularEntry(entry, artifactRoot)) {
                state.fail("Unsafe content-addressed artifact ${entry.name}")
                return@forEach
            }
            val canonical = entry.canonicalFile
            if (canonical.absolutePath in referencedPaths) return@forEach
            val digest = CONTENT_ADDRESSED_APK_PATTERN.matchEntire(entry.name)!!.groupValues[1]
            if (computeSha256(canonical) != digest) {
                state.fail("Content-addressed artifact digest mismatch: ${entry.name}")
                return@forEach
            }
            candidates += canonical
        }
        return candidates.takeIf { state.errors.isEmpty() }
    }

    private fun isSafeRegularEntry(file: File, root: File): Boolean {
        val absolute = file.absoluteFile
        if (Files.isSymbolicLink(absolute.toPath())) return false
        val canonical = runCatching { absolute.canonicalFile }.getOrNull() ?: return false
        if (canonical.parentFile != root) return false
        return Files.isRegularFile(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private data class RecordFileGroup(
        val packageName: String,
        var target: File? = null,
        var temp: File? = null,
        var backup: File? = null
    )

    private data class ValidatedRecord(
        val references: Map<String, String>
    )

    private data class JournalInventory(
        val files: List<File>,
        val abandonedPackages: Set<String>
    )

    private data class ArtifactTombstone(
        val file: File,
        val originalName: String
    )

    private data class ArtifactInventory(
        val stagingFiles: List<File>,
        val tombstones: List<ArtifactTombstone>
    )

    private class ReconcileState {
        var recordsEnumerated = 0
        var recordFilesRecovered = 0
        var stagingFilesDeleted = 0
        var tombstonesRestored = 0
        var tombstonesDeleted = 0
        var orphanArtifactsDeleted = 0
        var abandonedJournalsDeleted = 0
        var orphanGcSkipped = true
        val errors = mutableListOf<String>()

        fun fail(message: String) {
            errors += message
        }

        fun toResult() = PackageGenerationReconcileResult(
            success = errors.isEmpty(),
            recordsEnumerated = recordsEnumerated,
            recordFilesRecovered = recordFilesRecovered,
            stagingFilesDeleted = stagingFilesDeleted,
            tombstonesRestored = tombstonesRestored,
            tombstonesDeleted = tombstonesDeleted,
            orphanArtifactsDeleted = orphanArtifactsDeleted,
            abandonedJournalsDeleted = abandonedJournalsDeleted,
            orphanGcSkipped = orphanGcSkipped,
            errors = errors.toList()
        )
    }

    private companion object {
        const val INSTALL_RECORD_SCHEMA_VERSION = 1
        const val JOURNAL_SCHEMA_VERSION = 1
        const val RECONCILE_LOCK_FILE = ".reconcile.lock"
        val RECORD_FILE_PATTERN = Regex("^(.+)\\.json(?:\\.(tmp|bak))?$")
        val STAGING_FILE_PATTERN = Regex("^\\.install-.+\\.tmp$")
        val TOMBSTONE_FILE_PATTERN = Regex("^\\.(.+\\.apk)\\.delete-[A-Fa-f0-9-]+$")
        val CONTENT_ADDRESSED_APK_PATTERN = Regex("^.+-([0-9a-f]{64})\\.apk$")
        val JOURNAL_FILE_PATTERN = Regex("^generation-[A-Za-z0-9_-]{1,128}\\.journal(?:\\.tmp)?$")
        val SAFE_JOURNAL_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val gson = Gson()
    }
}

internal fun interface PackageGenerationDirectoryLister {
    fun list(directory: File): Array<File>?

    companion object {
        val DEFAULT = PackageGenerationDirectoryLister { directory -> directory.listFiles() }
    }
}

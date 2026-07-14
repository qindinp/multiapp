package com.multiapp.core.installer

import com.google.gson.GsonBuilder
import com.multiapp.core.model.installer.InstallRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class PackageGenerationReconcilerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `reconcile deletes abandoned staging orphan and journal after complete enumeration`() {
        val layout = layout("cleanup")
        val referenced = artifact(layout, "referenced")
        val orphan = artifact(layout, "orphan")
        writeRecord(layout, record(referenced))
        val staging = File(layout.artifactDir, ".install-abandoned-base.tmp").apply {
            writeText("staged")
        }
        val journal = writeJournal(layout)

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertEquals(1, result.recordsEnumerated)
        assertEquals(1, result.stagingFilesDeleted)
        assertEquals(1, result.orphanArtifactsDeleted)
        assertEquals(1, result.abandonedJournalsDeleted)
        assertFalse(result.orphanGcSkipped)
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertFalse(staging.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun `record temp is promoted when target is absent and backup is retired`() {
        val layout = layout("temp-promotion")
        val currentArtifact = artifact(layout, "current")
        val previousArtifact = artifact(layout, "previous")
        writeRecord(layout, record(currentArtifact, versionCode = 2), suffix = ".json.tmp")
        writeRecord(layout, record(previousArtifact, versionCode = 1), suffix = ".json.bak")

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertEquals(1, result.recordFilesRecovered)
        val recovered = readRecord(File(layout.installRecordDir, "$PACKAGE_NAME.json"))
        assertEquals(2L, recovered.versionCode)
        assertFalse(File(layout.installRecordDir, "$PACKAGE_NAME.json.tmp").exists())
        assertFalse(File(layout.installRecordDir, "$PACKAGE_NAME.json.bak").exists())
        assertFalse(previousArtifact.exists())
        assertTrue(currentArtifact.exists())
    }

    @Test
    fun `valid target wins over pre-commit temp generation`() {
        val layout = layout("target-wins")
        val previousArtifact = artifact(layout, "previous")
        val attemptedArtifact = artifact(layout, "attempted")
        writeRecord(layout, record(previousArtifact, versionCode = 1))
        writeRecord(layout, record(attemptedArtifact, versionCode = 2), suffix = ".json.tmp")

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertEquals(1L, readRecord(File(layout.installRecordDir, "$PACKAGE_NAME.json")).versionCode)
        assertTrue(previousArtifact.exists())
        assertFalse(attemptedArtifact.exists())
    }

    @Test
    fun `backup restores a corrupt target record`() {
        val layout = layout("backup")
        val artifact = artifact(layout, "backup")
        writeRecord(layout, record(artifact), suffix = ".json.bak")
        File(layout.installRecordDir, "$PACKAGE_NAME.json").writeText("{not-json")

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertEquals(1, result.recordFilesRecovered)
        assertEquals(PACKAGE_NAME, readRecord(File(layout.installRecordDir, "$PACKAGE_NAME.json")).packageName)
        assertFalse(File(layout.installRecordDir, "$PACKAGE_NAME.json.bak").exists())
    }

    @Test
    fun `referenced delete tombstone is restored and unreferenced tombstone is removed`() {
        val layout = layout("tombstones")
        val referenced = artifact(layout, "referenced")
        val record = record(referenced)
        writeRecord(layout, record)
        val referencedTombstone = tombstone(referenced)
        val abandonedArtifact = artifact(layout, "abandoned")
        val abandonedTombstone = tombstone(abandonedArtifact)

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertEquals(1, result.tombstonesRestored)
        assertEquals(1, result.tombstonesDeleted)
        assertTrue(referenced.exists())
        assertFalse(referencedTombstone.exists())
        assertFalse(abandonedTombstone.exists())
    }

    @Test
    fun `record enumeration failure forbids orphan gc and retains journal`() {
        val layout = layout("enumeration-failure")
        val orphan = artifact(layout, "orphan")
        File(layout.installRecordDir, "$PACKAGE_NAME.json").writeText("broken")
        val journal = writeJournal(layout)

        val result = reconciler(layout).reconcile()

        assertFalse(result.success)
        assertTrue(result.orphanGcSkipped)
        assertTrue(orphan.exists())
        assertTrue(journal.exists())
    }

    @Test
    fun `abandoned first install with partial record temp rolls back and permits gc`() {
        val layout = layout("partial-first-install")
        val orphan = artifact(layout, "committed-before-record")
        val partial = File(layout.installRecordDir, "$PACKAGE_NAME.json.tmp").apply {
            writeText("{partial")
        }
        val journal = writeJournal(layout)

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertFalse(result.orphanGcSkipped)
        assertFalse(partial.exists())
        assertFalse(orphan.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun `directory listing failure forbids orphan gc`() {
        val layout = layout("listing-failure")
        val orphan = artifact(layout, "orphan")
        val failingLister = PackageGenerationDirectoryLister { directory ->
            if (directory.canonicalFile == layout.installRecordDir.canonicalFile) null else directory.listFiles()
        }
        val reconciler = PackageGenerationReconciler(
            layout = layout,
            directoryLister = failingLister,
            faultInjector = PackageGenerationFaultInjector.NONE
        )

        val result = reconciler.reconcile()

        assertFalse(result.success)
        assertTrue(result.orphanGcSkipped)
        assertTrue(orphan.exists())
    }

    @Test
    fun `record artifact outside canonical root blocks gc`() {
        val layout = layout("containment")
        val external = File(tempDir, "outside.apk").apply { writeText("outside") }
        val internalOrphan = artifact(layout, "orphan")
        writeRecord(layout, record(external))

        val result = reconciler(layout).reconcile()

        assertFalse(result.success)
        assertTrue(result.orphanGcSkipped)
        assertTrue(external.exists())
        assertTrue(internalOrphan.exists())
    }

    @Test
    fun `record digest mismatch blocks gc`() {
        val layout = layout("digest")
        val expectedDigest = "0".repeat(64)
        val corrupt = File(layout.artifactDir, "$PACKAGE_NAME-base-$expectedDigest.apk").apply {
            writeText("different bytes")
        }
        val orphan = artifact(layout, "orphan")
        writeRecord(layout, record(corrupt, digest = expectedDigest))

        val result = reconciler(layout).reconcile()

        assertFalse(result.success)
        assertTrue(result.orphanGcSkipped)
        assertTrue(corrupt.exists())
        assertTrue(orphan.exists())
    }

    @Test
    fun `symlink artifact is rejected without touching target`() {
        val layout = layout("symlink")
        val external = File(tempDir, "external.apk").apply { writeText("external bytes") }
        val digest = sha256(external)
        val link = File(layout.artifactDir, "$PACKAGE_NAME-base-$digest.apk")
        try {
            Files.createSymbolicLink(link.toPath(), external.toPath())
        } catch (error: Exception) {
            assumeTrue(false, "Symbolic links unavailable: ${error.message}")
        }
        writeRecord(layout, record(link, digest = digest))

        val result = reconciler(layout).reconcile()

        assertFalse(result.success)
        assertTrue(result.orphanGcSkipped)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(external.exists())
        assertEquals("external bytes", external.readText())
    }

    @Test
    fun `fault injection at every recovery mutation converges on next startup`() {
        val points = listOf(
            PackageGenerationFaultPoint.AFTER_RECORD_TEMP_PROMOTED,
            PackageGenerationFaultPoint.AFTER_RECORD_BACKUP_RESTORED,
            PackageGenerationFaultPoint.AFTER_TOMBSTONE_RESTORED,
            PackageGenerationFaultPoint.AFTER_STAGING_DELETED,
            PackageGenerationFaultPoint.AFTER_TOMBSTONE_DELETED,
            PackageGenerationFaultPoint.AFTER_ORPHAN_DELETED,
            PackageGenerationFaultPoint.AFTER_JOURNAL_DELETED
        )

        points.forEachIndexed { index, point ->
            val layout = faultScenario("fault-$index", point)
            var injected = false
            val crashing = PackageGenerationReconciler(
                layout = layout,
                directoryLister = PackageGenerationDirectoryLister.DEFAULT,
                faultInjector = PackageGenerationFaultInjector { reached ->
                    if (!injected && reached == point) {
                        injected = true
                        throw SimulatedCrash()
                    }
                }
            )

            assertThrows(SimulatedCrash::class.java, crashing::reconcile, point.name)
            assertTrue(injected, "$point was not reached")

            val recovered = reconciler(layout).reconcile()
            assertTrue(recovered.success, "$point: ${recovered.errors}")
            val idempotent = reconciler(layout).reconcile()
            assertTrue(idempotent.success, "$point second pass: ${idempotent.errors}")
        }
    }

    private fun faultScenario(name: String, point: PackageGenerationFaultPoint): PackageGenerationLayout {
        val layout = layout(name)
        when (point) {
            PackageGenerationFaultPoint.AFTER_RECORD_TEMP_PROMOTED -> {
                val current = artifact(layout, "current")
                val previous = artifact(layout, "previous")
                writeRecord(layout, record(current, versionCode = 2), suffix = ".json.tmp")
                writeRecord(layout, record(previous, versionCode = 1), suffix = ".json.bak")
            }
            PackageGenerationFaultPoint.AFTER_RECORD_BACKUP_RESTORED -> {
                val artifact = artifact(layout, "backup")
                writeRecord(layout, record(artifact), suffix = ".json.bak")
            }
            PackageGenerationFaultPoint.AFTER_TOMBSTONE_RESTORED -> {
                val artifact = artifact(layout, "restore")
                writeRecord(layout, record(artifact))
                tombstone(artifact)
            }
            PackageGenerationFaultPoint.AFTER_STAGING_DELETED -> {
                File(layout.artifactDir, ".install-abandoned-base.tmp").writeText("staged")
            }
            PackageGenerationFaultPoint.AFTER_TOMBSTONE_DELETED -> {
                tombstone(artifact(layout, "delete"))
            }
            PackageGenerationFaultPoint.AFTER_ORPHAN_DELETED -> artifact(layout, "orphan")
            PackageGenerationFaultPoint.AFTER_JOURNAL_DELETED -> {
                writeJournal(layout)
            }
            else -> error("Journal publication faults are covered by PackageGenerationJournalTest")
        }
        return layout
    }

    private fun layout(name: String): PackageGenerationLayout {
        val root = File(tempDir, name)
        return PackageGenerationLayout(
            installRecordDir = File(root, "installs").apply { mkdirs() },
            artifactDir = File(root, "artifacts").apply { mkdirs() },
            journalDir = File(root, "journal").apply { mkdirs() }
        )
    }

    private fun reconciler(layout: PackageGenerationLayout) = PackageGenerationReconciler(
        layout = layout,
        directoryLister = PackageGenerationDirectoryLister.DEFAULT,
        faultInjector = PackageGenerationFaultInjector.NONE
    )

    private fun artifact(layout: PackageGenerationLayout, content: String): File {
        val digest = sha256(content.toByteArray())
        return File(layout.artifactDir, "$PACKAGE_NAME-base-$digest.apk").apply {
            writeText(content)
        }
    }

    private fun tombstone(artifact: File): File {
        val tombstone = File(artifact.parentFile, ".${artifact.name}.delete-$DELETE_ID")
        assertTrue(artifact.renameTo(tombstone))
        return tombstone
    }

    private fun record(
        artifact: File,
        versionCode: Long = 1,
        digest: String = sha256(artifact)
    ): InstallRecord = InstallRecord(
        packageName = PACKAGE_NAME,
        originApkPath = artifact.absolutePath,
        originApkSha256 = digest,
        originCertSha256 = "",
        versionCode = versionCode,
        versionName = versionCode.toString(),
        targetSdk = 36,
        minSdk = 28,
        installTimeMs = 1_000L
    )

    private fun writeRecord(
        layout: PackageGenerationLayout,
        record: InstallRecord,
        suffix: String = ".json"
    ) {
        File(layout.installRecordDir, "${record.packageName}$suffix")
            .writeText(gson.toJson(record), Charsets.UTF_8)
    }

    private fun writeJournal(layout: PackageGenerationLayout): File =
        File(layout.journalDir, "generation-abandoned.journal").apply {
            writeText(
                """
                schemaVersion=1
                transactionId=abandoned
                packageName=$PACKAGE_NAME
                creationRequestId=create-request-1
                payloadFingerprint=${"a".repeat(64)}
                startedAtMs=1000
                phase=PREPARED
                """.trimIndent()
            )
        }

    private fun readRecord(file: File): InstallRecord =
        gson.fromJson(file.readText(Charsets.UTF_8), InstallRecord::class.java)

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class SimulatedCrash : Error()

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        const val DELETE_ID = "00000000-0000-0000-0000-000000000001"
        val gson = GsonBuilder().setPrettyPrinting().create()
    }
}

package com.multiapp.core.model.installer

import com.multiapp.core.model.VirtualApp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstalledPackageImporterTransactionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `split name sanitization collisions produce stable unique verified artifacts`() {
        val baseApk = apk("collision-base.apk", "base")
        val dotSplit = apk("config.en.apk", "dot split")
        val underscoreSplit = apk("config_en.apk", "underscore split")
        val store = JsonInstallRecordStore(File(tempDir, "collision-records"))
        val artifactDir = File(tempDir, "collision-artifacts")
        val importer = InstalledPackageImporter(store, artifactDir)

        val firstResult = importer.importFromMetadata(
            packageName = "com.example.collision",
            originApkPath = baseApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            splitApkPaths = listOf(dotSplit.absolutePath, underscoreSplit.absolutePath),
            splitNames = listOf("config.en", "config_en")
        )

        assertTrue(firstResult.isSuccess)
        val firstRecord = store.load("com.example.collision")!!
        assertEquals(2, firstRecord.splitApkPaths.distinct().size)
        assertTrue(File(firstRecord.splitApkPaths[0]).name.contains("-split-000-config_en-"))
        assertTrue(File(firstRecord.splitApkPaths[1]).name.contains("-split-001-config_en-"))
        assertArtifactDigest(firstRecord.originApkPath, firstRecord.originApkSha256)
        firstRecord.splitApkPaths.forEachIndexed { index, path ->
            assertArtifactDigest(path, firstRecord.splitApkSha256s[index])
        }
        assertEquals("dot split", File(firstRecord.splitApkPaths[0]).readText())
        assertEquals("underscore split", File(firstRecord.splitApkPaths[1]).readText())

        val secondResult = importer.importFromMetadata(
            packageName = "com.example.collision",
            originApkPath = baseApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            splitApkPaths = listOf(dotSplit.absolutePath, underscoreSplit.absolutePath),
            splitNames = listOf("config.en", "config_en")
        )

        assertTrue(secondResult.isSuccess)
        val secondRecord = store.load("com.example.collision")!!
        assertEquals(firstRecord.originApkPath, secondRecord.originApkPath)
        assertEquals(firstRecord.splitApkPaths, secondRecord.splitApkPaths)
        assertEquals(3, artifactDir.listFiles().orEmpty().count { it.isFile })
        assertNoStagingFiles(artifactDir)
    }

    @Test
    fun `record save failure rolls back all newly committed artifacts`() {
        val baseApk = apk("failed-base.apk", "base")
        val splitApk = apk("failed-split.apk", "split")
        val artifactDir = File(tempDir, "failed-artifacts")
        val failingStore = object : InstallRecordStore {
            override fun save(record: InstallRecord): Result<String> =
                Result.failure(IllegalStateException("injected record save failure"))

            override fun load(packageName: String): InstallRecord? = null
            override fun listAll(): List<InstallRecord> = emptyList()
            override fun delete(packageName: String): Boolean = false
        }
        val importer = InstalledPackageImporter(failingStore, artifactDir)

        val result = importer.importFromMetadata(
            packageName = "com.example.failed",
            originApkPath = baseApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            splitApkPaths = listOf(splitApk.absolutePath),
            splitNames = listOf("feature")
        )

        assertTrue(result.isFailure)
        assertTrue(artifactDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `failed replacement preserves the existing record and generation`() {
        val oldBase = apk("old-base.apk", "old base")
        val oldSplit = apk("old-split.apk", "old split")
        val newBase = apk("new-base.apk", "new base")
        val newSplit = apk("new-split.apk", "new split")
        val store = JsonInstallRecordStore(File(tempDir, "generation-records"))
        val artifactDir = File(tempDir, "generation-artifacts")
        val importer = InstalledPackageImporter(store, artifactDir)
        importer.importFromMetadata(
            packageName = "com.example.generation",
            originApkPath = oldBase.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            splitApkPaths = listOf(oldSplit.absolutePath),
            splitNames = listOf("feature")
        ).getOrThrow()
        val existingRecord = store.load("com.example.generation")!!
        val existingArtifactNames = artifactDir.listFiles().orEmpty().map { it.name }.sorted()

        val failingStore = object : InstallRecordStore by store {
            override fun save(record: InstallRecord): Result<String> =
                Result.failure(IllegalStateException("injected replacement failure"))
        }
        val failedResult = InstalledPackageImporter(failingStore, artifactDir).importFromMetadata(
            packageName = "com.example.generation",
            originApkPath = newBase.absolutePath,
            versionCode = 2L,
            versionName = "2.0",
            targetSdk = 35,
            minSdk = 28,
            splitApkPaths = listOf(newSplit.absolutePath),
            splitNames = listOf("feature")
        )

        assertTrue(failedResult.isFailure)
        assertEquals(existingRecord, store.load("com.example.generation"))
        assertEquals(existingArtifactNames, artifactDir.listFiles().orEmpty().map { it.name }.sorted())
        assertEquals("old base", File(existingRecord.originApkPath).readText())
        assertEquals("old split", File(existingRecord.splitApkPaths.single()).readText())
        assertNoStagingFiles(artifactDir)
    }

    @Test
    fun `digest collision at an existing split target rolls back earlier artifact commits`() {
        val baseApk = apk("commit-base.apk", "base to roll back")
        val splitApk = apk("commit-split.apk", "expected split")
        val artifactDir = File(tempDir, "commit-artifacts").apply { mkdirs() }
        val packageName = "com.example.commit"
        val splitDigest = sha256(splitApk)
        val conflictingTarget = File(
            artifactDir,
            "$packageName-split-000-config_en-$splitDigest.apk"
        ).apply { writeText("corrupt existing artifact") }
        val store = JsonInstallRecordStore(File(tempDir, "commit-records"))

        val result = InstalledPackageImporter(store, artifactDir).importFromMetadata(
            packageName = packageName,
            originApkPath = baseApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            splitApkPaths = listOf(splitApk.absolutePath),
            splitNames = listOf("config.en")
        )

        assertTrue(result.isFailure)
        assertNull(store.load(packageName))
        assertEquals(listOf(conflictingTarget.name), artifactDir.listFiles().orEmpty().map { it.name })
        assertEquals("corrupt existing artifact", conflictingTarget.readText())
        assertNoStagingFiles(artifactDir)
    }

    @Test
    fun `metadata resolver failure is fail closed before artifact import`() {
        val baseApk = apk("resolver-base.apk", "base")
        val store = JsonInstallRecordStore(File(tempDir, "resolver-records"))
        val artifactDir = File(tempDir, "resolver-artifacts")
        val resolverFailure = IllegalStateException("package identity mismatch")
        val service = ProductionVirtualInstallService(
            installRecordStore = store,
            artifactDir = artifactDir,
            metadataResolver = InstallMetadataResolver { _, _ -> throw resolverFailure }
        )

        val result = service.ensureInstallRecord(
            VirtualApp(
                packageName = "com.example.resolver",
                appName = "Resolver",
                versionName = "1.0",
                versionCode = 1L,
                apkPath = baseApk.absolutePath,
                instanceId = "",
                minSdkVersion = 28,
                targetSdkVersion = 35
            )
        )

        assertTrue(result.isFailure)
        assertSame(resolverFailure, result.exceptionOrNull())
        assertNull(store.load("com.example.resolver"))
        assertTrue(artifactDir.listFiles().orEmpty().isEmpty())
    }

    private fun apk(name: String, content: String): File =
        File(tempDir, name).apply { writeText(content) }

    private fun assertArtifactDigest(path: String, expectedDigest: String) {
        val artifact = File(path)
        assertTrue(artifact.isFile)
        assertEquals(expectedDigest, sha256(artifact))
        assertTrue(artifact.name.endsWith("-$expectedDigest.apk"))
        assertNotEquals(artifact.absolutePath, File(tempDir, artifact.name).absolutePath)
    }

    private fun assertNoStagingFiles(artifactDir: File) {
        assertFalse(artifactDir.listFiles().orEmpty().any { it.name.startsWith(".install-") })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

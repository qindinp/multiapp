package com.multiapp.core.model.installer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallRecordStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save and load roundtrip`() {
        val store = JsonInstallRecordStore(tempDir)
        val record = createRecord(packageName = "com.example.app")

        val result = store.save(record)
        assertTrue(result.isSuccess)

        val loaded = store.load("com.example.app")
        assertNotNull(loaded)
        assertEquals(record.packageName, loaded.packageName)
        assertEquals(record.originApkSha256, loaded.originApkSha256)
        assertEquals(record.versionCode, loaded.versionCode)
    }

    @Test
    fun `load nonexistent returns null`() {
        val store = JsonInstallRecordStore(tempDir)
        val loaded = store.load("com.nonexistent.app")
        assertNull(loaded)
    }

    @Test
    fun `save returns path containing package name`() {
        val store = JsonInstallRecordStore(tempDir)
        val record = createRecord(packageName = "com.example.myapp")

        val result = store.save(record)
        assertTrue(result.isSuccess)
        val path = result.getOrNull()!!
        assertTrue(path.contains("com.example.myapp"))
    }

    @Test
    fun `atomic write - no temp file left behind`() {
        val store = JsonInstallRecordStore(tempDir)
        val record = createRecord(packageName = "com.example.atomic")

        store.save(record)

        val files = tempDir.listFiles() ?: emptyArray()
        val tempFiles = files.filter { it.name.endsWith(".tmp") || it.name.contains("temp") }
        assertTrue(tempFiles.isEmpty(), "No temp files should remain after save")
    }

    @Test
    fun `listAll returns all saved records`() {
        val store = JsonInstallRecordStore(tempDir)

        store.save(createRecord(packageName = "com.example.app1"))
        store.save(createRecord(packageName = "com.example.app2"))
        store.save(createRecord(packageName = "com.example.app3"))

        val all = store.listAll()
        assertEquals(3, all.size)

        val packageNames = all.map { it.packageName }.toSet()
        assertTrue(packageNames.contains("com.example.app1"))
        assertTrue(packageNames.contains("com.example.app2"))
        assertTrue(packageNames.contains("com.example.app3"))
    }

    @Test
    fun `delete removes record`() {
        val store = JsonInstallRecordStore(tempDir)
        store.save(createRecord(packageName = "com.example.toDelete"))

        val deleted = store.delete("com.example.toDelete")
        assertEquals(true, deleted)

        val loaded = store.load("com.example.toDelete")
        assertNull(loaded)
    }

    @Test
    fun `delete nonexistent returns false`() {
        val store = JsonInstallRecordStore(tempDir)
        val deleted = store.delete("com.nonexistent")
        assertEquals(false, deleted)
    }

    @Test
    fun `schemaVersion preserved on load`() {
        val store = JsonInstallRecordStore(tempDir)
        val record = createRecord(packageName = "com.example.versioned")

        store.save(record)
        val loaded = store.load("com.example.versioned")

        assertNotNull(loaded)
        assertEquals(1, loaded.schemaVersion)
    }

    @Test
    fun `save overwrites existing record for same package`() {
        val store = JsonInstallRecordStore(tempDir)

        store.save(createRecord(packageName = "com.example.app", versionCode = 1))
        store.save(createRecord(packageName = "com.example.app", versionCode = 2))

        val loaded = store.load("com.example.app")
        assertNotNull(loaded)
        assertEquals(2L, loaded.versionCode)

        val all = store.listAll()
        assertEquals(1, all.size, "Should have only one record per package")
    }

    @Test
    fun `updatedAt is auto-set on save`() {
        val store = JsonInstallRecordStore(tempDir)
        val beforeSave = System.currentTimeMillis()

        val record = createRecord(packageName = "com.example.timestamp")
        store.save(record)

        val loaded = store.load("com.example.timestamp")
        assertNotNull(loaded)
        assertTrue(loaded.updatedAtMs >= beforeSave, "updatedAt should be set to current time on save")
    }

    @Test
    fun `load rejects unsafe packageName before filesystem access`() {
        val store = JsonInstallRecordStore(File(tempDir, "records"))
        File(tempDir, "evil.json").writeText("not an install record")

        assertFailsWith<IllegalArgumentException> {
            store.load("../evil")
        }
    }

    @Test
    fun `delete rejects unsafe packageName before filesystem access`() {
        val store = JsonInstallRecordStore(File(tempDir, "records"))
        val outsideRecord = File(tempDir, "evil.json").apply { writeText("do not delete") }

        assertFailsWith<IllegalArgumentException> {
            store.delete("../evil")
        }
        assertTrue(outsideRecord.exists())
    }

    private fun createRecord(
        packageName: String = "com.example.app",
        originApkPath: String = "/data/app/com.example.app/base.apk",
        originApkSha256: String = "sha256abc123",
        originCertSha256: String = "cert123",
        versionCode: Long = 1,
        versionName: String = "1.0",
        targetSdk: Int = 33,
        minSdk: Int = 21,
        installTimeMs: Long = 1000L
    ) = InstallRecord(
        packageName = packageName,
        originApkPath = originApkPath,
        originApkSha256 = originApkSha256,
        originCertSha256 = originCertSha256,
        versionCode = versionCode,
        versionName = versionName,
        targetSdk = targetSdk,
        minSdk = minSdk,
        installTimeMs = installTimeMs
    )
}

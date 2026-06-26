package com.multiapp.core.model.installer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstallRecordTest {

    @Test
    fun `default values - schemaVersion is 1`() {
        val record = createRecord()
        assertEquals(1, record.schemaVersion)
    }

    @Test
    fun `default values - empty lists for native libraries`() {
        val record = createRecord()
        assertTrue(record.nativeLibraries.isEmpty())
    }

    @Test
    fun `default values - empty lists for abi list`() {
        val record = createRecord()
        assertTrue(record.abiList.isEmpty())
    }

    @Test
    fun `default values - null application class name`() {
        val record = createRecord()
        assertEquals(null, record.applicationClassName)
    }

    @Test
    fun `default values - null package label`() {
        val record = createRecord()
        assertEquals(null, record.packageLabel)
    }

    @Test
    fun `default values - empty lists for components`() {
        val record = createRecord()
        assertTrue(record.permissions.isEmpty())
        assertTrue(record.activities.isEmpty())
        assertTrue(record.services.isEmpty())
        assertTrue(record.receivers.isEmpty())
        assertTrue(record.providers.isEmpty())
    }

    @Test
    fun `equal instances with same values`() {
        val record1 = createRecord()
        val record2 = createRecord()
        assertEquals(record1, record2)
        assertEquals(record1.hashCode(), record2.hashCode())
    }

    @Test
    fun `not equal when packageName differs`() {
        val record1 = createRecord(packageName = "com.example.app1")
        val record2 = createRecord(packageName = "com.example.app2")
        assertNotEquals(record1, record2)
    }

    @Test
    fun `not equal when originApkSha256 differs`() {
        val record1 = createRecord(originApkSha256 = "abc123")
        val record2 = createRecord(originApkSha256 = "def456")
        assertNotEquals(record1, record2)
    }

    @Test
    fun `copy with version change creates new instance`() {
        val original = createRecord()
        val updated = original.copy(versionCode = 2, versionName = "2.0")

        assertEquals(1L, original.versionCode)
        assertEquals("1.0", original.versionName)
        assertEquals(2L, updated.versionCode)
        assertEquals("2.0", updated.versionName)
    }

    @Test
    fun `updatedAt field preserves value`() {
        val now = System.currentTimeMillis()
        val record = createRecord(updatedAtMs = now)
        assertEquals(now, record.updatedAtMs)
    }

    @Test
    fun `ComponentInfo default exported is false`() {
        val component = ComponentInfo(name = "com.example.MainActivity")
        assertEquals(false, component.exported)
    }

    @Test
    fun `ComponentInfo equality`() {
        val comp1 = ComponentInfo(name = "com.example.MainActivity", exported = true)
        val comp2 = ComponentInfo(name = "com.example.MainActivity", exported = true)
        assertEquals(comp1, comp2)
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
        installTimeMs: Long = 1000L,
        updatedAtMs: Long = 1000L
    ) = InstallRecord(
        packageName = packageName,
        originApkPath = originApkPath,
        originApkSha256 = originApkSha256,
        originCertSha256 = originCertSha256,
        versionCode = versionCode,
        versionName = versionName,
        targetSdk = targetSdk,
        minSdk = minSdk,
        installTimeMs = installTimeMs,
        updatedAtMs = updatedAtMs
    )
}

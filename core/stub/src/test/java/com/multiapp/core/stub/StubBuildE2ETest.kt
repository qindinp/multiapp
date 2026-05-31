package com.multiapp.core.stub

import com.multiapp.core.manifest.DeviceIdentityConfig
import com.multiapp.core.manifest.StubConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stub 生成端到端测试
 */
class StubBuildE2ETest {

    private lateinit var stubBuilder: StubBuilder

    @BeforeEach
    fun setup() {
        stubBuilder = StubBuilder()
    }

    @Test
    fun `createConfigJson produces valid JSON with all fields`() {
        val config = createTestConfig()
        val json = stubBuilder.createConfigJson(config)

        assertTrue(json.contains(config.instanceId)) { "JSON should contain instanceId" }
        assertTrue(json.contains(config.stubPackageName)) { "JSON should contain stubPackageName" }
        assertTrue(json.contains(config.originalPackageName)) { "JSON should contain originalPackageName" }
        assertTrue(json.contains(config.launchActivity)) { "JSON should contain launchActivity" }
        assertTrue(json.contains("deviceIdentity")) { "JSON should contain deviceIdentity" }
        assertTrue(json.contains("buildModel")) { "JSON should contain buildModel" }
    }

    @Test
    fun `createStoredEntry has correct STORED method and CRC`() {
        val data = "Hello, World!".toByteArray()

        val method = StubBuilder::class.java.getDeclaredMethod(
            "createStoredEntry", String::class.java, ByteArray::class.java
        )
        method.isAccessible = true
        val entry = method.invoke(stubBuilder, "test.txt", data) as java.util.zip.ZipEntry

        assertEquals(java.util.zip.ZipEntry.STORED, entry.method) { "Method should be STORED" }
        assertEquals(data.size.toLong(), entry.size) { "Size should match" }
        assertEquals(data.size.toLong(), entry.compressedSize) { "CompressedSize should equal size for STORED" }
        assertTrue(entry.crc != 0L) { "CRC should be non-zero" }
    }

    @Test
    fun `extractLauncherIcon handles missing icon gracefully`() {
        val tempFile = File.createTempFile("test", ".apk")
        tempFile.deleteOnExit()

        val outputDir = File.createTempFile("output", "")
        outputDir.delete()
        outputDir.mkdirs()
        outputDir.deleteOnExit()

        val result = stubBuilder.extractLauncherIcon(tempFile, outputDir)
        assertNull(result) { "Should return null for invalid APK" }
    }

    @Test
    fun `StubConfig data class has all required fields`() {
        val config = createTestConfig()

        assertEquals("test_instance_001", config.instanceId)
        assertEquals("com.test.app.clonestub", config.stubPackageName)
        assertEquals("com.test.app", config.originalPackageName)
        assertEquals("com.test.app.MainActivity", config.launchActivity)
        assertEquals(1, config.originalSignatures.size)
        assertEquals(1, config.authorityMap.size)
        assertNotNull(config.deviceIdentity)
        assertEquals("123456789012345", config.deviceIdentity.imei)
    }

    @Test
    fun `DeviceIdentityConfig has all fields with defaults`() {
        val identity = DeviceIdentityConfig(
            imei = "123456789012345",
            androidId = "abcdef1234567890",
            macAddress = "AA:BB:CC:DD:EE:FF",
            serial = "R5CR12345AB",
            buildModel = "Pixel 9",
            buildManufacturer = "Google",
            buildFingerprint = "google/raven/raven:14/test"
        )

        assertEquals("123456789012345", identity.imei)
        assertEquals("abcdef1234567890", identity.androidId)
        assertEquals("AA:BB:CC:DD:EE:FF", identity.macAddress)
        assertEquals("R5CR12345AB", identity.serial)
        assertEquals("Pixel 9", identity.buildModel)
        assertEquals("Google", identity.buildManufacturer)
        assertEquals("", identity.buildBrand)
        assertEquals("", identity.buildDevice)
        assertEquals("", identity.buildProduct)
        assertEquals("16", identity.versionRelease)
        assertEquals(36, identity.sdkInt)
    }

    private fun createTestConfig(): StubConfig {
        return StubConfig(
            instanceId = "test_instance_001",
            stubPackageName = "com.test.app.clonestub",
            originalPackageName = "com.test.app",
            launchActivity = "com.test.app.MainActivity",
            originalSignatures = listOf("/path/to/original.apk"),
            authorityMap = mapOf("com.test.app.provider" to "com.test.app.clonestub.provider"),
            deviceIdentity = DeviceIdentityConfig(
                imei = "123456789012345",
                androidId = "abcdef1234567890",
                macAddress = "AA:BB:CC:DD:EE:FF",
                serial = "R5CR12345AB",
                buildModel = "Pixel 9",
                buildManufacturer = "Google",
                buildFingerprint = "google/raven/raven:14/UP1A.231005.007/10754064:user/release-keys"
            )
        )
    }
}

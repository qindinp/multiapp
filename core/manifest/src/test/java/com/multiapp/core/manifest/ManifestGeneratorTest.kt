package com.multiapp.core.manifest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class ManifestGeneratorTest {

    private lateinit var generator: ManifestGenerator

    @BeforeEach
    fun setup() {
        generator = ManifestGenerator()
    }

    @Test
    fun `generateBytes should output valid binary XML header`() {
        val manifest = createTestManifest()
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo(
            name = "com.test.MainActivity",
            exported = true,
            process = null
        )

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        // 验证 XML header magic: RES_XML_TYPE = 0x00080003
        assertTrue(bytes.size > 8, "Output should be larger than 8 bytes")
        assertEquals(0x03, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
        assertEquals(0x08, bytes[2].toInt() and 0xFF)
        assertEquals(0x00, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `generateBytes should contain package name in string pool`() {
        val manifest = createTestManifest()
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo(
            name = "com.test.MainActivity",
            exported = true,
            process = null
        )

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)
        val content = String(bytes, Charsets.UTF_8)

        assertTrue(content.contains("com.test.stub"), "Should contain package name")
        assertTrue(content.contains("com.test.MainActivity"), "Should contain launcher activity")
        assertTrue(content.contains("LoaderFactory"), "Should contain appComponentFactory")
    }

    @Test
    fun `generateBytes should handle multiple activities`() {
        val manifest = createTestManifest().copy(
            activities = listOf(
                ManifestParser.ComponentInfo("com.test.MainActivity", true, null),
                ManifestParser.ComponentInfo("com.test.SecondActivity", false, null),
                ManifestParser.ComponentInfo("com.test.ThirdActivity", false, ":remote")
            )
        )
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo("com.test.MainActivity", true, null)

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        assertTrue(bytes.size > 100, "Multi-activity manifest should be larger")
    }

    @Test
    fun `generateBytes should handle providers with authority rewrite`() {
        val manifest = createTestManifest().copy(
            providers = listOf(
                ManifestParser.ComponentInfo("com.test.MyProvider", true, null)
                    .copy(authorities = "com.test.provider")
            )
        )
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo("com.test.MainActivity", true, null)

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        assertTrue(bytes.isNotEmpty(), "Should produce non-empty output")
    }

    @Test
    fun `generateBytes should include permissions`() {
        val manifest = createTestManifest().copy(
            permissions = listOf(
                "android.permission.INTERNET",
                "android.permission.CAMERA",
                "android.permission.READ_CONTACTS"
            )
        )
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo("com.test.MainActivity", true, null)

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)
        val content = String(bytes, Charsets.UTF_8)

        assertTrue(content.contains("INTERNET"), "Should contain permission")
        assertTrue(content.contains("CAMERA"), "Should contain permission")
    }

    @Test
    fun `generate should produce valid text XML`() {
        val manifest = createTestManifest()
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo("com.test.MainActivity", true, null)

        val xml = generator.generate("com.test.stub", manifest, launcherActivity, config)

        assertTrue(xml.contains("<?xml version"), "Should have XML header")
        assertTrue(xml.contains("<manifest"), "Should have manifest tag")
        assertTrue(xml.contains("</manifest>"), "Should close manifest tag")
        assertTrue(xml.contains("com.test.stub"), "Should contain package name")
        assertTrue(xml.contains("LoaderFactory"), "Should contain appComponentFactory")
    }

    private fun createTestManifest() = ManifestParser.ParsedManifest(
        packageName = "com.test.app",
        permissions = emptyList(),
        activities = listOf(
            ManifestParser.ComponentInfo("com.test.MainActivity", true, null)
        ),
        services = emptyList(),
        receivers = emptyList(),
        providers = emptyList()
    )

    private fun createTestConfig() = StubConfig(
        instanceId = "test-001",
        stubPackageName = "com.test.stub",
        originalPackageName = "com.test.app",
        launchActivity = "com.test.MainActivity",
        originalSignatures = listOf("/tmp/test.apk"),
        authorityMap = emptyMap(),
        deviceIdentity = DeviceIdentityConfig(
            imei = "123456789",
            androidId = "abc123",
            macAddress = "00:11:22:33:44:55",
            serial = "TEST123",
            buildModel = "TestPhone",
            buildManufacturer = "TestCorp",
            buildFingerprint = "test/fingerprint",
            buildBrand = "TestBrand",
            buildDevice = "testdevice",
            buildProduct = "testproduct",
            versionRelease = "16",
            sdkInt = 36
        )
    )
}

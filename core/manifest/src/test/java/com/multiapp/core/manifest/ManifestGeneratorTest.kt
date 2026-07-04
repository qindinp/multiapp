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

        // 二进制 XML 的字符串以 UTF-8 编码存储在 StringPool 中
        // 搜索 UTF-8 编码的字符串
        val utf8Bytes = "com.test.stub".toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > utf8Bytes.size, "Output should be larger than string")
        assertTrue(containsBytes(bytes, utf8Bytes), "StringPool should contain package name (UTF-8)")
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
                ManifestParser.ProviderInfo(
                    name = "com.test.MyProvider",
                    authorities = "com.test.provider",
                    exported = true,
                    grantUriPermissions = true,
                    permission = "com.test.permission.PROVIDER"
                )
            )
        )
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo("com.test.MainActivity", true, null)

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        assertTrue(bytes.isNotEmpty(), "Should produce non-empty output")
        assertTrue(containsBytes(bytes, "com.test.permission.PROVIDER".toByteArray(Charsets.UTF_8)), "Should contain provider permission")
        assertTrue(containsBytes(bytes, "grantUriPermissions".toByteArray(Charsets.UTF_8)), "Should contain grantUriPermissions")
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

        // 二进制 XML 中权限以 UTF-8 编码存储
        assertTrue(containsBytes(bytes, "INTERNET".toByteArray(Charsets.UTF_8)), "Should contain INTERNET")
        assertTrue(containsBytes(bytes, "CAMERA".toByteArray(Charsets.UTF_8)), "Should contain CAMERA")
    }

    @Test
    fun `generateBytes should encode theme IDs as reference values`() {
        val applicationThemeId = 0x7f100123
        val activityThemeId = 0x7f100456
        val manifest = createTestManifest().copy(
            applicationTheme = "@style/AppTheme",
            applicationThemeId = applicationThemeId,
            activities = listOf(
                ManifestParser.ComponentInfo(
                    name = "com.test.MainActivity",
                    exported = true,
                    themeId = activityThemeId
                )
            )
        )
        val config = createTestConfig()
        val launcherActivity = manifest.activities.first()

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        assertFalse(
            containsBytes(bytes, "@style/AppTheme".toByteArray(Charsets.UTF_8)),
            "Theme string should not be written into the string pool"
        )
        assertEquals(2, countReferenceThemeValues(bytes, applicationThemeId, activityThemeId))
    }

    /**
     * 在字节数组中搜索子序列
     */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun countReferenceThemeValues(bytes: ByteArray, vararg expectedThemeIds: Int): Int {
        val resourceMapStart = 8 + le32(bytes, 12)
        val resourceMapSize = le32(bytes, resourceMapStart + 4)
        var offset = resourceMapStart + resourceMapSize
        var count = 0

        while (offset + 8 <= bytes.size) {
            val chunkType = le16(bytes, offset)
            val chunkSize = le32(bytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > bytes.size) break

            if (chunkType == 0x0102) {
                val attrCount = le16(bytes, offset + 28)
                val attrsStart = offset + 36
                for (i in 0 until attrCount) {
                    val attrStart = attrsStart + i * 20
                    val dataType = bytes[attrStart + 15].toInt() and 0xFF
                    val data = le32(bytes, attrStart + 16)
                    if (dataType == 0x01 && expectedThemeIds.contains(data)) {
                        val rawValue = le32(bytes, attrStart + 8)
                        assertEquals(-1, rawValue, "Reference theme rawValue should be absent")
                        count++
                    }
                }
            }
            offset += chunkSize
        }
        return count
    }

    private fun le16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    @Test
    fun `generateBytes should encode launchMode and configChanges as typed values`() {
        val manifest = createTestManifest().copy(
            activities = listOf(
                ManifestParser.ComponentInfo(
                    name = "com.test.MainActivity",
                    exported = true,
                    launchMode = "singleTask",
                    configChanges = "orientation|screenSize|keyboardHidden",
                    screenOrientation = "portrait",
                    windowSoftInputMode = "adjustResize"
                )
            )
        )
        val config = createTestConfig()
        val launcherActivity = manifest.activities.first()

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        // 验证字符串池包含属性值
        assertTrue(containsBytes(bytes, "singleTask".toByteArray(Charsets.UTF_8)), "Should contain singleTask")
        assertTrue(containsBytes(bytes, "portrait".toByteArray(Charsets.UTF_8)), "Should contain portrait")
        assertTrue(containsBytes(bytes, "adjustResize".toByteArray(Charsets.UTF_8)), "Should contain adjustResize")

        // 验证 configChanges 管道分隔字符串
        assertTrue(containsBytes(bytes, "orientation".toByteArray(Charsets.UTF_8)), "Should contain orientation")
        assertTrue(containsBytes(bytes, "screenSize".toByteArray(Charsets.UTF_8)), "Should contain screenSize")
        assertTrue(containsBytes(bytes, "keyboardHidden".toByteArray(Charsets.UTF_8)), "Should contain keyboardHidden")
    }

    @Test
    fun `generateBytes should encode boolean component attributes`() {
        val manifest = createTestManifest().copy(
            activities = listOf(
                ManifestParser.ComponentInfo(
                    name = "com.test.MainActivity",
                    exported = true,
                    noHistory = true,
                    clearTaskOnLaunch = true
                )
            )
        )
        val config = createTestConfig()
        val launcherActivity = manifest.activities.first()

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        // 验证字符串池包含属性名
        assertTrue(containsBytes(bytes, "noHistory".toByteArray(Charsets.UTF_8)), "Should contain noHistory attr name")
        assertTrue(containsBytes(bytes, "clearTaskOnLaunch".toByteArray(Charsets.UTF_8)), "Should contain clearTaskOnLaunch attr name")
    }

    @Test
    fun `generateBytes should encode taskAffinity and permission as strings`() {
        val manifest = createTestManifest().copy(
            activities = listOf(
                ManifestParser.ComponentInfo(
                    name = "com.test.MainActivity",
                    exported = true,
                    taskAffinity = "com.tencent.qqreader",
                    permission = "com.test.READ_BOOKS"
                )
            )
        )
        val config = createTestConfig()
        val launcherActivity = manifest.activities.first()

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        assertTrue(containsBytes(bytes, "com.tencent.qqreader".toByteArray(Charsets.UTF_8)), "Should contain taskAffinity")
        assertTrue(containsBytes(bytes, "com.test.READ_BOOKS".toByteArray(Charsets.UTF_8)), "Should contain permission")
    }

    @Test
    fun `generateBytes string pool entries should be 4-byte aligned`() {
        val manifest = createTestManifest()
        val config = createTestConfig()
        val launcherActivity = ManifestParser.ComponentInfo("com.test.MainActivity", true, null)

        val bytes = generator.generateBytes("com.test.stub", manifest, launcherActivity, config)

        // 解析 StringPool header
        // RES_XML_TYPE header: 8 bytes (type=2, headerSize=2, size=4)
        // StringPool starts at offset 8
        val spChunkType = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        assertEquals(0x0001, spChunkType, "Expected RES_STRING_POOL_TYPE at offset 8")

        // StringPool header fields (all little-endian):
        // offset 12: stringCount (4 bytes)
        // offset 24: stringsStart (4 bytes)
        val stringCount = (bytes[16].toInt() and 0xFF) or
            ((bytes[17].toInt() and 0xFF) shl 8) or
            ((bytes[18].toInt() and 0xFF) shl 16) or
            ((bytes[19].toInt() and 0xFF) shl 24)
        assertTrue(stringCount > 0, "StringPool should have strings")

        // 每个字符串偏移量从 offset 28 开始，每个 4 字节
        for (i in 0 until stringCount) {
            val offset = (bytes[28 + i * 4].toInt() and 0xFF) or
                ((bytes[29 + i * 4].toInt() and 0xFF) shl 8) or
                ((bytes[30 + i * 4].toInt() and 0xFF) shl 16) or
                ((bytes[31 + i * 4].toInt() and 0xFF) shl 24)
            assertEquals(0, offset % 4, "String $i offset $offset is not 4-byte aligned")
        }
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
        applicationClass = null,
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

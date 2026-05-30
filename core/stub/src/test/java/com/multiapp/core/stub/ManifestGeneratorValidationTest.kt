package com.multiapp.core.stub

import com.multiapp.core.manifest.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ManifestGenerator 二进制 XML 输出验证
 * 直接对比 aapt 参考格式
 */
class ManifestGeneratorValidationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `ManifestGenerator output matches aapt reference format`() {
        val generator = ManifestGenerator()

        val manifest = ManifestParser.ParsedManifest(
            packageName = "top.hookvip.pro",
            applicationClass = null,
            permissions = listOf("android.permission.INTERNET"),
            activities = listOf(
                ManifestParser.ComponentInfo("top.hookvip.pro.app.activity.SplashActivity", true, null)
            ),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList()
        )

        val launcherActivity = ManifestParser.ComponentInfo(
            "top.hookvip.pro.app.activity.SplashActivity", true, null
        )

        val config = StubConfig(
            instanceId = "test-001",
            stubPackageName = "com.multiapp.stub.hookvip001",
            originalPackageName = "top.hookvip.pro",
            launchActivity = "top.hookvip.pro.app.activity.SplashActivity",
            originalSignatures = listOf("/tmp/test.apk"),
            authorityMap = emptyMap(),
            deviceIdentity = DeviceIdentityConfig(
                imei = "123456789012345",
                androidId = "abcdef1234567890",
                macAddress = "AA:BB:CC:DD:EE:FF",
                serial = "TEST001",
                buildModel = "Pixel 9 Pro",
                buildManufacturer = "Google",
                buildFingerprint = "google/cheetah/cheetah:16/test",
                sdkInt = 28
            )
        )

        val bytes = generator.generateBytes(
            stubPackageName = "com.multiapp.stub.hookvip001",
            manifest = manifest,
            launcherActivity = launcherActivity,
            config = config
        )

        // 解析关键字段
        val magic = le32(bytes, 0)
        val fileSize = le32(bytes, 4)
        val spType = le32(bytes, 8)
        val spSize = le32(bytes, 12)
        val spCount = le32(bytes, 16)
        val spFlags = le32(bytes, 24)
        val spStringsStart = le32(bytes, 28)

        // 验证
        assertEquals(0x00080003, magic, "magic 必须是 RES_XML_TYPE")
        assertEquals(bytes.size, fileSize, "fileSize 必须等于实际大小")
        assertEquals(0x001C0001, spType, "StringPool type")
        assertEquals(0x100, spFlags, "StringPool flags 必须是 UTF-8 (0x100)")
        assertTrue(spCount > 0, "String count 必须 > 0")
        assertEquals(28 + spCount * 4, spStringsStart, "stringsStart = headerSize + count*4")

        // 验证 ResourceMap 存在且包含正确的属性 ID
        val rmStart = 8 + spSize
        assertTrue(rmStart + 8 <= bytes.size, "ResourceMap 必须存在")
        val rmType = le32(bytes, rmStart)
        val rmSize = le32(bytes, rmStart + 4)
        assertEquals(0x0180, rmType and 0xFFFF, "ResourceMap type 必须是 RES_XML_RESOURCE_MAP_TYPE")
        val rmCount = (rmSize - 8) / 4
        assertTrue(rmCount > 0, "ResourceMap 必须包含属性 ID")

        // 验证第一个 XML 节点是 startNamespace
        val treeStart = rmStart + rmSize
        assertTrue(treeStart + 8 <= bytes.size, "XML body 必须存在")
        val treeType = le32(bytes, treeStart) and 0xFFFF
        assertEquals(0x0100, treeType, "第一个 XML 节点必须是 RES_XML_START_NAMESPACE_TYPE")

        // 验证 XML body 不包含 @mipmap 引用（无 resources.arsc）
        val bodyBytes = bytes.sliceArray(treeStart until bytes.size)
        val mipmapRef = "@mipmap".toByteArray(Charsets.UTF_8)
        assertFalse(containsBytes(bodyBytes, mipmapRef), "XML body 不应包含 @mipmap 引用（无 resources.arsc）")
    }

    /**
     * 验证 StringPool 使用 UTF-8 编码且字符串完整
     */
    @Test
    fun `StringPool contains all required strings in UTF-8`() {
        val generator = ManifestGenerator()

        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.example.app",
            applicationClass = null,
            permissions = listOf("android.permission.INTERNET"),
            activities = listOf(
                ManifestParser.ComponentInfo("com.example.app.MainActivity", true, null)
            ),
            services = listOf(
                ManifestParser.ComponentInfo("com.example.app.MyService", false, null)
            ),
            receivers = emptyList(),
            providers = emptyList()
        )

        val launcherActivity = ManifestParser.ComponentInfo(
            "com.example.app.MainActivity", true, null
        )

        val config = StubConfig(
            instanceId = "test-utf8",
            stubPackageName = "com.multiapp.stub.test",
            originalPackageName = "com.example.app",
            launchActivity = "com.example.app.MainActivity",
            originalSignatures = listOf("/tmp/test.apk"),
            authorityMap = emptyMap(),
            deviceIdentity = DeviceIdentityConfig(
                imei = "123456789012345",
                androidId = "abcdef1234567890",
                macAddress = "AA:BB:CC:DD:EE:FF",
                serial = "TEST001",
                buildModel = "Pixel 9",
                buildManufacturer = "Google",
                buildFingerprint = "google/test/test:16/test",
                sdkInt = 28
            )
        )

        val bytes = generator.generateBytes("com.multiapp.stub.test", manifest, launcherActivity, config)

        // 验证关键字符串以 UTF-8 存在于 StringPool 中
        val requiredStrings = listOf(
            "com.multiapp.stub.test",   // package name
            "android.intent.action.MAIN",
            "android.intent.category.LAUNCHER",
            "com.example.app.MainActivity",
            "com.example.app.MyService",
            "LoaderFactory",             // appComponentFactory 值
            "uses-sdk",
            "uses-permission"
        )

        for (s in requiredStrings) {
            val utf8 = s.toByteArray(Charsets.UTF_8)
            assertTrue(containsBytes(bytes, utf8), "StringPool 必须包含: $s")
        }
    }

    /**
     * 验证 ResourceMap 中的属性 ID 与 aapt 标准一致
     */
    @Test
    fun `ResourceMap contains correct Android attribute resource IDs`() {
        val generator = ManifestGenerator()

        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.example.app",
            applicationClass = null,
            permissions = emptyList(),
            activities = listOf(
                ManifestParser.ComponentInfo("com.example.app.MainActivity", true, null)
            ),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList()
        )

        val launcherActivity = ManifestParser.ComponentInfo(
            "com.example.app.MainActivity", true, null
        )

        val config = StubConfig(
            instanceId = "test-resmap",
            stubPackageName = "com.multiapp.stub.test",
            originalPackageName = "com.example.app",
            launchActivity = "com.example.app.MainActivity",
            originalSignatures = listOf("/tmp/test.apk"),
            authorityMap = emptyMap(),
            deviceIdentity = DeviceIdentityConfig(
                imei = "123456789012345",
                androidId = "abcdef1234567890",
                macAddress = "AA:BB:CC:DD:EE:FF",
                serial = "TEST001",
                buildModel = "Pixel 9",
                buildManufacturer = "Google",
                buildFingerprint = "google/test/test:16/test",
                sdkInt = 28
            )
        )

        val bytes = generator.generateBytes("com.multiapp.stub.test", manifest, launcherActivity, config)

        // 找到 ResourceMap chunk
        val spSize = le32(bytes, 12)
        val rmStart = 8 + spSize
        val rmType = le32(bytes, rmStart)
        assertEquals(0x0180, rmType and 0xFFFF, "必须是 ResourceMap chunk")

        val rmSize = le32(bytes, rmStart + 4)
        val rmCount = (rmSize - 8) / 4
        val rmEntries = (0 until rmCount).map { le32(bytes, rmStart + 8 + it * 4) }

        // 已知的 Android 属性 ID（参照 frameworks/base/core/res/res/values/public.xml）
        val expectedIds = setOf(
            0x0101021b, // versionCode
            0x0101021c, // versionName
            0x0101020c, // minSdkVersion
            0x01010270, // targetSdkVersion
            0x01010003, // name
            0x01010001, // label
            0x0101000f, // debuggable
            0x01010010, // exported
            0x01010011, // process
            0x01010018, // authorities
            0x0101057a, // appComponentFactory
            0x01010006, // permission
            0x0101000e, // enabled
            0x01010419, // extractNativeLibs
            0           // 无映射
        )

        // 验证 ResourceMap 中的 ID 都是已知的 Android 属性 ID
        for (id in rmEntries) {
            assertTrue(expectedIds.contains(id),
                "ResourceMap 包含未知 ID: 0x${id.toString(16).padStart(8, '0')}")
        }
    }

    /**
     * 验证二进制 XML 的节点结构完整性
     */
    @Test
    fun `binary XML has correct node structure with namespace and elements`() {
        val generator = ManifestGenerator()

        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.example.app",
            applicationClass = null,
            permissions = emptyList(),
            activities = listOf(
                ManifestParser.ComponentInfo("com.example.app.MainActivity", true, null)
            ),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList()
        )

        val launcherActivity = ManifestParser.ComponentInfo(
            "com.example.app.MainActivity", true, null
        )

        val config = StubConfig(
            instanceId = "test-nodes",
            stubPackageName = "com.multiapp.stub.test",
            originalPackageName = "com.example.app",
            launchActivity = "com.example.app.MainActivity",
            originalSignatures = listOf("/tmp/test.apk"),
            authorityMap = emptyMap(),
            deviceIdentity = DeviceIdentityConfig(
                imei = "123456789012345",
                androidId = "abcdef1234567890",
                macAddress = "AA:BB:CC:DD:EE:FF",
                serial = "TEST001",
                buildModel = "Pixel 9",
                buildManufacturer = "Google",
                buildFingerprint = "google/test/test:16/test",
                sdkInt = 28
            )
        )

        val bytes = generator.generateBytes("com.multiapp.stub.test", manifest, launcherActivity, config)

        // 跳过 StringPool 和 ResourceMap，解析 XML body
        val spSize = le32(bytes, 12)
        val rmStart = 8 + spSize
        val rmSize = le32(bytes, rmStart + 4)
        val bodyStart = rmStart + rmSize

        // 收集所有 XML 节点类型
        val nodeTypes = mutableListOf<Int>()
        var offset = bodyStart
        while (offset + 8 <= bytes.size) {
            val chunkType = le16(bytes, offset)
            val chunkSize = le32(bytes, offset + 4)
            if (chunkSize < 8) break
            nodeTypes.add(chunkType)
            offset += chunkSize
        }

        // 验证节点序列: startNs, startElem(manifest), ... , endElem(manifest), endNs
        assertTrue(nodeTypes.isNotEmpty(), "XML body 必须包含节点")
        assertEquals(0x0100, nodeTypes.first(), "第一个节点必须是 startNamespace")
        assertEquals(0x0101, nodeTypes.last(), "最后一个节点必须是 endNamespace")

        // 验证 startElem 和 endElem 数量匹配
        val startCount = nodeTypes.count { it == 0x0102 }
        val endCount = nodeTypes.count { it == 0x0103 }
        assertEquals(startCount, endCount, "startElement 和 endElement 数量必须匹配")

        // 验证至少有 manifest + application + activity = 3 对
        assertTrue(startCount >= 3, "至少需要 manifest/application/activity 三对元素")
    }

    /**
     * 验证 STORED 条目的数据偏移 4 字节对齐
     */
    @Test
    fun `zipalign produces 4-byte aligned STORED entries`() {
        val stubBuilder = createStubBuilderForTest()

        // 创建一个包含多种文件的 APK
        val entries = mapOf(
            "AndroidManifest.xml" to "manifest-data-here",
            "classes.dex" to "dex-content-here-1234",
            "resources.arsc" to "arsc-data"
        )
        val input = createMinimalZip(File(tempDir, "input.apk"), entries)
        val output = File(tempDir, "aligned.apk")

        stubBuilder.zipalign(input, output)

        // 验证每个 STORED 条目的数据偏移是 4 字节对齐的
        ZipFile(output).use { zip ->
            val raf = java.io.RandomAccessFile(output, "r")
            try {
                val entriesList = zip.entries().toList()
                for (entry in entriesList) {
                    if (entry.method == ZipEntry.STORED) {
                        val headerOffset = findLocalHeaderOffset(raf, entry.name)
                        raf.seek(headerOffset + 26)
                        val nameLen = raf.readUnsignedShort()
                        val extraLen = raf.readUnsignedShort()
                        val dataOffset = headerOffset + 30 + nameLen + extraLen
                        assertEquals(0, dataOffset % 4,
                            "STORED 条目 '${entry.name}' 数据偏移 $dataOffset 不是 4 字节对齐")
                    }
                }
            } finally {
                raf.close()
            }
        }
    }

    /**
     * 验证 zipalign 后文件仍然可以正常读取
     */
    @Test
    fun `zipalign preserves file integrity after alignment`() {
        val stubBuilder = createStubBuilderForTest()

        val entries = mapOf(
            "AndroidManifest.xml" to "<manifest/>",
            "classes.dex" to "dex-data-12345678",
            "assets/config.json" to """{"key":"value"}""",
            "res/layout/main.xml" to "<layout/>"
        )
        val input = createMinimalZip(File(tempDir, "input.apk"), entries)
        val output = File(tempDir, "aligned.apk")

        stubBuilder.zipalign(input, output)

        ZipFile(output).use { zip ->
            for ((name, expectedContent) in entries) {
                val entry = zip.getEntry(name)
                assertNotNull(entry, "缺少条目: $name")
                val actual = String(zip.getInputStream(entry!!).readBytes())
                assertEquals(expectedContent, actual, "条目 '$name' 内容不匹配")
            }
        }
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    private fun findLocalHeaderOffset(raf: java.io.RandomAccessFile, entryName: String): Long {
        raf.seek(0)
        val nameBytes = entryName.toByteArray()

        // 简单扫描：从文件开头逐字节查找 local file header signature
        val buffer = ByteArray(4096)
        var pos = 0L

        while (pos < raf.length() - 30) {
            raf.seek(pos)
            val sig = raf.readInt()
            if (sig == 0x04034b50) {
                // 读取 name length 和 extra length
                raf.seek(pos + 26)
                val nameLen = raf.readUnsignedShort()
                val extraLen = raf.readUnsignedShort()
                if (nameLen == nameBytes.size) {
                    raf.seek(pos + 30)
                    val name = ByteArray(nameLen)
                    raf.readFully(name)
                    if (name.contentEquals(nameBytes)) {
                        return pos
                    }
                }
            }
            pos++
        }
        throw AssertionError("Local file header not found for entry: $entryName")
    }

    private fun createStubBuilderForTest(): StubBuilder {
        val parser = io.mockk.mockk<ManifestParser>(relaxed = true)
        val generator = io.mockk.mockk<ManifestGenerator>(relaxed = true)
        val extractor = io.mockk.mockk<ComponentExtractor>(relaxed = true)
        return StubBuilder(parser = parser, generator = generator, extractor = extractor)
    }

    private fun createMinimalZip(file: File, entries: Map<String, String>): File {
        ZipOutputStream(java.io.FileOutputStream(file)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

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

    private fun le16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}

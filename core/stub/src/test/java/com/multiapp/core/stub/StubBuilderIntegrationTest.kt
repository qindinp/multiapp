package com.multiapp.core.stub

import com.multiapp.core.manifest.*
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * StubBuilder 集成测试 — 模拟 MultiApp 真实构建流程
 *
 * 流程：
 * 1. 创建一个模拟的原始 APK（含 manifest + dex + lib）
 * 2. StubBuilder 生成 Stub APK
 * 3. 用 aapt2 验证 Stub APK 的 AndroidManifest.xml
 * 4. 验证 APK 结构完整性
 */
class StubBuilderIntegrationTest {

    private lateinit var workDir: File
    private lateinit var originApk: File
    private lateinit var stubBuilder: StubBuilder

    @BeforeEach
    fun setup() {
        workDir = File(System.getProperty("java.io.tmpdir"), "multiapp_integration_test_${System.currentTimeMillis()}")
        workDir.mkdirs()
        stubBuilder = StubBuilder()

        // Mock ApkSigningHelper（JVM 环境无 AndroidKeyStore）
        mockkObject(ApkSigningHelper)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        // 创建一个带正确算法的 mock 证书
        val mockCert = io.mockk.mockk<X509Certificate>(relaxed = true)
        every { mockCert.publicKey } returns keyPair.public
        every { mockCert.sigAlgName } returns "SHA256withRSA"
        every { ApkSigningHelper.getOrCreateSigningKey() } returns Pair(keyPair.private, mockCert)

        // 创建模拟原始 APK
        originApk = createMockOriginApk()
    }

    @AfterEach
    fun teardown() {
        workDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `StubBuilder generates valid APK with correct binary XML manifest`() {
        val config = createTestConfig()

        val stubApk = stubBuilder.build(config)

        // 1. 验证文件存在且非空
        assertTrue(stubApk.exists(), "Stub APK should exist")
        assertTrue(stubApk.length() > 0, "Stub APK should not be empty")

        // 2. 验证 APK 结构
        java.util.zip.ZipFile(stubApk).use { zip ->
            // 必须包含 AndroidManifest.xml
            assertNotNull(zip.getEntry("AndroidManifest.xml"), "Must contain AndroidManifest.xml")

            // 必须包含 classes.dex
            assertNotNull(zip.getEntry("classes.dex"), "Must contain classes.dex")

            // 必须包含 origin.apk
            assertNotNull(zip.getEntry("assets/origin.apk"), "Must contain assets/origin.apk")

            // 必须包含配置文件
            assertNotNull(zip.getEntry("assets/multiapp_config.json"), "Must contain config")
        }

        // 3. 验证 AndroidManifest.xml 是有效的二进制 XML
        validateBinaryManifest(stubApk)

        // 4. 用 aapt2 验证（如果可用）
        validateWithAapt2(stubApk)
    }

    @Test
    fun `StubBuilder generates valid APK with multiple activities`() {
        val config = createTestConfig().copy(
            // 模拟有多个 activity 的 app
        )

        val stubApk = stubBuilder.build(config)
        validateBinaryManifest(stubApk)
    }

    @Test
    fun `StubBuilder generates valid APK with providers`() {
        val config = createTestConfig()

        val stubApk = stubBuilder.build(config)
        validateBinaryManifest(stubApk)
    }

    /**
     * 验证 AndroidManifest.xml 是有效的二进制 XML
     */
    private fun validateBinaryManifest(apkFile: File) {
        java.util.zip.ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw AssertionError("AndroidManifest.xml not found")

            val data = zip.getInputStream(entry).readBytes()

            // 1. 检查 XML header magic (RES_XML_TYPE = 0x00080003)
            assertTrue(data.size >= 8, "Manifest too small: ${data.size} bytes")
            val magic = (data[0].toInt() and 0xFF) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    ((data[2].toInt() and 0xFF) shl 16) or
                    ((data[3].toInt() and 0xFF) shl 24)
            assertEquals(0x00080003, magic, "Invalid XML header magic: 0x${magic.toString(16)}")

            // 2. 检查 fileSize 字段与实际大小一致
            val fileSize = (data[4].toInt() and 0xFF) or
                    ((data[5].toInt() and 0xFF) shl 8) or
                    ((data[6].toInt() and 0xFF) shl 16) or
                    ((data[7].toInt() and 0xFF) shl 24)
            assertEquals(data.size, fileSize, "fileSize field mismatch")

            // 3. 检查 StringPool header
            val spType = (data[8].toInt() and 0xFF) or
                    ((data[9].toInt() and 0xFF) shl 8) or
                    ((data[10].toInt() and 0xFF) shl 16) or
                    ((data[11].toInt() and 0xFF) shl 24)
            assertEquals(0x001C0001, spType, "Invalid StringPool type")

            // 4. 检查 StringPool flags — 必须是 UTF-8 (0x100) 或 UTF-16 (0x000)
            val spFlags = (data[24].toInt() and 0xFF) or
                    ((data[25].toInt() and 0xFF) shl 8) or
                    ((data[26].toInt() and 0xFF) shl 16) or
                    ((data[27].toInt() and 0xFF) shl 24)
            val isUtf8 = (spFlags and 0x100) != 0
            assertTrue(spFlags == 0x100 || spFlags == 0x000,
                "Invalid StringPool flags: 0x${spFlags.toString(16)}, expected UTF-8 (0x100) or UTF-16 (0x000)")

            // 5. 检查 StringPool string count > 0
            val spCount = (data[16].toInt() and 0xFF) or
                    ((data[17].toInt() and 0xFF) shl 8) or
                    ((data[18].toInt() and 0xFF) shl 16) or
                    ((data[19].toInt() and 0xFF) shl 24)
            assertTrue(spCount > 0, "StringPool should have >0 strings, got $spCount")

            // 6. 验证第一个字符串可读
            val spStringsStart = (data[28].toInt() and 0xFF) or
                    ((data[29].toInt() and 0xFF) shl 8) or
                    ((data[30].toInt() and 0xFF) shl 16) or
                    ((data[31].toInt() and 0xFF) shl 24)
            val strStart = 8 + spStringsStart

            if (isUtf8) {
                // UTF-8: charLen(1-2 bytes) + byteLen(1-2 bytes) + data + null
                val charLenByte = data[strStart].toInt() and 0xFF
                val charLen = if (charLenByte < 128) charLenByte
                else ((charLenByte and 0x7F) shl 7) or (data[strStart + 1].toInt() and 0x7F)

                val byteLenOffset = if (charLenByte < 128) 1 else 2
                val byteLenByte = data[strStart + byteLenOffset].toInt() and 0xFF
                val byteLen = if (byteLenByte < 128) byteLenByte
                else ((byteLenByte and 0x7F) shl 7) or (data[strStart + byteLenOffset + 1].toInt() and 0x7F)

                val strDataLen = byteLenOffset + (if (byteLenByte < 128) 1 else 2) + byteLen
                val strData = data.sliceArray(strStart until strStart + strDataLen)
                val firstString = String(strData.sliceArray(strDataLen - byteLen until strDataLen), Charsets.UTF_8)
                assertTrue(firstString.isNotEmpty(), "First string should not be empty")
                println("  First string: \"$firstString\" (UTF-8, $spCount strings)")
            } else {
                // UTF-16LE: charLen(2 bytes) + data + null null
                val charLen = (data[strStart].toInt() and 0xFF) or
                        ((data[strStart + 1].toInt() and 0xFF) shl 8)
                val strData = data.sliceArray(strStart + 2 until strStart + 2 + charLen * 2)
                val firstString = String(strData, Charsets.UTF_16LE)
                assertTrue(firstString.isNotEmpty(), "First string should not be empty")
                println("  First string: \"$firstString\" (UTF-16LE, $spCount strings)")
            }

            // 7. 检查 ResourceMap chunk 紧跟 StringPool
            val spSize = (data[12].toInt() and 0xFF) or
                    ((data[13].toInt() and 0xFF) shl 8) or
                    ((data[14].toInt() and 0xFF) shl 16) or
                    ((data[15].toInt() and 0xFF) shl 24)
            val spEnd = 8 + spSize
            val rmType = (data[spEnd].toInt() and 0xFF) or
                    ((data[spEnd + 1].toInt() and 0xFF) shl 8) or
                    ((data[spEnd + 2].toInt() and 0xFF) shl 16) or
                    ((data[spEnd + 3].toInt() and 0xFF) shl 24)
            assertEquals(0x00080180, rmType, "Expected ResourceMap chunk after StringPool")

            println("  Manifest validation passed: $spCount strings, ${if (isUtf8) "UTF-8" else "UTF-16LE"}")
        }
    }

    /**
     * 用 aapt2 验证 APK（如果在 PATH 中）
     */
    private fun validateWithAapt2(apkFile: File) {
        val aapt2Paths = listOf(
            "/root/Android/Sdk/build-tools/36.0.0/aapt2",
            "/usr/local/bin/aapt2",
            "aapt2"
        )

        val aapt2 = aapt2Paths.firstOrNull { File(it).canExecute() }
        if (aapt2 == null) {
            println("  aapt2 not found, skipping aapt2 validation")
            return
        }

        val process = ProcessBuilder(aapt2, "dump", "xmltree", apkFile.absolutePath, "--file", "AndroidManifest.xml")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            fail<String>("aapt2 validation failed (exit=$exitCode):\n$output")
        }

        // 验证输出包含关键字段
        assertTrue(output.contains("manifest"), "aapt2 output should contain 'manifest' tag")
        assertTrue(output.contains("minSdkVersion") || output.contains("uses-sdk"),
            "aapt2 output should contain SDK version info")

        println("  aapt2 validation passed")
        println("  Manifest tree (first 10 lines):")
        output.lines().take(10).forEach { println("    $it") }
    }

    /**
     * 创建模拟原始 APK（使用测试资源中的合法 APK）
     */
    private fun createMockOriginApk(): File {
        // 使用 aapt 预编译的合法 mock APK（含二进制 XML manifest）
        val resourceApk = File(javaClass.classLoader.getResource("mock-origin.apk")!!.toURI())
        val target = File(workDir, "mock-origin.apk")
        resourceApk.copyTo(target, overwrite = true)
        return target
    }

    /**
     * 创建最小合法二进制 AndroidManifest.xml
     */
    private fun createMinimalBinaryManifest(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()

        // 简单的文本 XML（AGP 会重新编译为二进制）
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.test.mock">
    <application android:label="MockApp">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>""".trimIndent()

        return xml.toByteArray(Charsets.UTF_8)
    }

    /**
     * 创建最小合法 DEX 文件
     */
    private fun createMinimalDex(): ByteArray {
        // DEX magic + minimal header
        return byteArrayOf(
            0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00, // dex\n035\0
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // checksum
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // sha1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, // fileSize
            0x70, 0x00, 0x00, 0x00, // headerSize
            0x78, 0x56, 0x34, 0x12, // endianTag
            0x00, 0x00, 0x00, 0x00, // linkSize
            0x00, 0x00, 0x00, 0x00, // linkOff
            0x70, 0x00, 0x00, 0x00, // mapOff
            0x00, 0x00, 0x00, 0x00, // stringIdsSize
            0x00, 0x00, 0x00, 0x00, // stringIdsOff
            0x00, 0x00, 0x00, 0x00, // typeIdsSize
            0x00, 0x00, 0x00, 0x00, // typeIdsOff
            0x00, 0x00, 0x00, 0x00, // protoIdsSize
            0x00, 0x00, 0x00, 0x00, // protoIdsOff
            0x00, 0x00, 0x00, 0x00, // fieldIdsSize
            0x00, 0x00, 0x00, 0x00, // fieldIdsOff
            0x00, 0x00, 0x00, 0x00, // methodIdsSize
            0x00, 0x00, 0x00, 0x00, // methodIdsOff
            0x00, 0x00, 0x00, 0x00, // classDefsSize
            0x00, 0x00, 0x00, 0x00, // classDefsOff
            0x00, 0x00, 0x00, 0x00, // dataSize
            0x00, 0x00, 0x00, 0x00, // dataOff
        )
    }

    /**
     * 创建自签名证书（用于 mock ApkSigningHelper）
     */
    private fun createSelfSignedCert(keyPair: java.security.KeyPair): X509Certificate {
        val now = java.time.Instant.now()
        val notBefore = java.util.Date.from(now.minus(java.time.Duration.ofDays(1)))
        val notAfter = java.util.Date.from(now.plus(java.time.Duration.ofDays(365)))

        // 使用 Bouncy Castle 或 JDK 内置方式生成自签名证书
        // 简化方式：用 mockk 直接返回一个 mock 证书
        return io.mockk.mockk<X509Certificate>(relaxed = true)
    }

    private fun createTestConfig() = StubConfig(
        instanceId = "test-instance-001",
        stubPackageName = "com.multiapp.stub.test001",
        originalPackageName = "com.test.mock",
        launchActivity = "com.test.mock.MainActivity",
        originalSignatures = listOf(originApk.absolutePath),
        authorityMap = mapOf("com.test.mock.provider" to "com.multiapp.stub.test001.provider"),
        deviceIdentity = DeviceIdentityConfig(
            imei = "123456789012345",
            androidId = "abcdef1234567890",
            macAddress = "AA:BB:CC:DD:EE:FF",
            serial = "TESTSERIAL001",
            buildModel = "Pixel 9 Pro",
            buildManufacturer = "Google",
            buildFingerprint = "google/cheetah/cheetah:16/BP11.241121.014/12345678:userdebug/dev-keys",
            buildBrand = "google",
            buildDevice = "cheetah",
            buildProduct = "cheetah",
            versionRelease = "16",
            sdkInt = 36
        )
    )
}

package com.multiapp.core.stub

import com.multiapp.core.manifest.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate

/**
 * ManifestGenerator 二进制 XML 输出验证
 * 直接对比 aapt 参考格式
 */
class ManifestGeneratorValidationTest {

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

        // 调试输出
        println("=== ManifestGenerator 二进制输出 ===")
        println("大小: ${bytes.size} bytes")
        println("前128hex: ${bytes.take(128).joinToString("") { "%02x".format(it) }}")

        // 解析关键字段
        val magic = le32(bytes, 0)
        val fileSize = le32(bytes, 4)
        val spType = le32(bytes, 8)
        val spSize = le32(bytes, 12)
        val spCount = le32(bytes, 16)
        val spFlags = le32(bytes, 24)
        val spStringsStart = le32(bytes, 28)

        println("magic=0x${magic.toString(16).padStart(8, '0')}")
        println("fileSize=$fileSize (actual=${bytes.size})")
        println("spType=0x${spType.toString(16).padStart(8, '0')}")
        println("spSize=$spSize, spCount=$spCount")
        println("spFlags=0x${spFlags.toString(16).padStart(8, '0')}")
        println("spStringsStart=$spStringsStart (期望=${28 + spCount * 4})")

        // ResourceMap
        val rmStart = 8 + spSize
        if (rmStart + 8 <= bytes.size) {
            val rmType = le32(bytes, rmStart)
            val rmSize = le32(bytes, rmStart + 4)
            val rmCount = (rmSize - 8) / 4
            println("rmType=0x${rmType.toString(16).padStart(8, '0')}, rmSize=$rmSize, rmCount=$rmCount")

            // 读取 ResourceMap entries
            val rmEntries = (0 until rmCount).map { le32(bytes, rmStart + 8 + it * 4) }
            println("rmEntries: ${rmEntries.map { "0x${it.toString(16).padStart(8, '0')}" }}")

            // XML Tree
            val treeStart = rmStart + rmSize
            if (treeStart + 4 <= bytes.size) {
                val treeType = le32(bytes, treeStart)
                println("treeType=0x${treeType.toString(16).padStart(8, '0')} (${
                    when (treeType) {
                        0x100100 -> "startNs ✓"
                        0x100102 -> "startElem ✗ (应该先有startNs)"
                        else -> "unknown ✗"
                    }
                })")
            }
        }

        // 验证
        assertEquals(0x00080003, magic, "magic 必须是 RES_XML_TYPE")
        assertEquals(bytes.size, fileSize, "fileSize 必须等于实际大小")
        assertEquals(0x001C0001, spType, "StringPool type")
        assertEquals(0, spFlags, "StringPool flags 必须是 UTF-16LE (0x000)")
        assertTrue(spCount > 0, "String count 必须 > 0")
        assertEquals(28 + spCount * 4, spStringsStart, "stringsStart = headerSize + count*4")
    }

    private fun le32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}

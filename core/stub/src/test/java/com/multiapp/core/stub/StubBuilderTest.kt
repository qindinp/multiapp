package com.multiapp.core.stub

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.ManifestGenerator
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.StubConfig
import com.multiapp.core.manifest.DeviceIdentityConfig
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

class StubBuilderTest {

    private lateinit var stubBuilder: StubBuilder
    private lateinit var parser: ManifestParser
    private lateinit var extractor: ComponentExtractor
    private lateinit var generator: ManifestGenerator
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @TempDir
    lateinit var tempDir: File

    private val defaultDeviceIdentity = DeviceIdentityConfig(
        imei = "123456789012345",
        androidId = "abcdef123456",
        macAddress = "AA:BB:CC:DD:EE:FF",
        serial = "SERIAL123",
        buildModel = "Pixel 9",
        buildManufacturer = "Google",
        buildFingerprint = "google/panther/panther:14/UP1A.231005.007/10814064:userdebug/dev-keys",
        buildBrand = "google",
        buildDevice = "panther",
        buildProduct = "panther",
        versionRelease = "16",
        sdkInt = 36
    )

    private val defaultConfig = StubConfig(
        instanceId = "test-instance-001",
        stubPackageName = "com.multiapp.stub.test001",
        originalPackageName = "com.example.app",
        launchActivity = "com.example.app.MainActivity",
        originalSignatures = listOf("/path/to/origin.apk"),
        authorityMap = mapOf(
            "com.example.app.provider" to "com.multiapp.stub.test001.provider"
        ),
        deviceIdentity = defaultDeviceIdentity
    )

    @BeforeEach
    fun setUp() {
        parser = mockk(relaxed = true)
        extractor = mockk(relaxed = true)
        generator = mockk(relaxed = true)
        stubBuilder = StubBuilder(parser = parser, generator = generator, extractor = extractor)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // =====================================================================
    // 1. StubConfig JSON serialization / deserialization
    // =====================================================================

    @Nested
    @DisplayName("StubConfig JSON serialization")
    inner class ConfigJsonSerialization {

        @Test
        fun `createConfigJson produces valid JSON with all required fields`() {
            val json = stubBuilder.createConfigJson(defaultConfig)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            assertEquals("test-instance-001", map["instanceId"])
            assertEquals("com.multiapp.stub.test001", map["stubPackageName"])
            assertEquals("com.example.app", map["originalPackageName"])
            assertEquals("com.example.app.MainActivity", map["launchActivity"])
        }

        @Test
        fun `createConfigJson includes authorityMap`() {
            val json = stubBuilder.createConfigJson(defaultConfig)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            @Suppress("UNCHECKED_CAST")
            val authorityMap = map["authorityMap"] as Map<String, Any>
            assertEquals(
                "com.multiapp.stub.test001.provider",
                authorityMap["com.example.app.provider"]
            )
        }

        @Test
        fun `createConfigJson includes all deviceIdentity fields`() {
            val json = stubBuilder.createConfigJson(defaultConfig)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            @Suppress("UNCHECKED_CAST")
            val identity = map["deviceIdentity"] as Map<String, Any>
            assertEquals("123456789012345", identity["imei"])
            assertEquals("abcdef123456", identity["androidId"])
            assertEquals("AA:BB:CC:DD:EE:FF", identity["macAddress"])
            assertEquals("SERIAL123", identity["serial"])
            assertEquals("Pixel 9", identity["buildModel"])
            assertEquals("Google", identity["buildManufacturer"])
            assertEquals(
                "google/panther/panther:14/UP1A.231005.007/10814064:userdebug/dev-keys",
                identity["buildFingerprint"]
            )
            assertEquals("google", identity["buildBrand"])
            assertEquals("panther", identity["buildDevice"])
            assertEquals("panther", identity["buildProduct"])
            assertEquals("16", identity["versionRelease"])
            // Gson deserializes integers as doubles by default
            assertEquals(36.0, identity["sdkInt"])
        }

        @Test
        fun `createConfigJson with empty authorityMap produces valid JSON`() {
            val config = defaultConfig.copy(authorityMap = emptyMap())
            val json = stubBuilder.createConfigJson(config)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            @Suppress("UNCHECKED_CAST")
            val authorityMap = map["authorityMap"] as Map<String, Any>
            assertTrue(authorityMap.isEmpty())
        }

        @Test
        fun `createConfigJson with special characters in fields produces valid JSON`() {
            val config = defaultConfig.copy(
                instanceId = """id-with-special/chars@#$%""",
                originalPackageName = "com.example.app-v2",
                launchActivity = "com.example.ui.LauncherActivity\$Inner"
            )
            val json = stubBuilder.createConfigJson(config)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            assertEquals("id-with-special/chars@#$%", map["instanceId"])
            assertEquals("com.example.app-v2", map["originalPackageName"])
            assertEquals(
                "com.example.ui.LauncherActivity\$Inner",
                map["launchActivity"]
            )
        }

        @Test
        fun `createConfigJson output is round-trippable`() {
            val json = stubBuilder.createConfigJson(defaultConfig)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            assertEquals(defaultConfig.instanceId, map["instanceId"])
            assertEquals(defaultConfig.stubPackageName, map["stubPackageName"])
            assertEquals(defaultConfig.originalPackageName, map["originalPackageName"])
            assertEquals(defaultConfig.launchActivity, map["launchActivity"])
        }
    }

    // =====================================================================
    // 2. assembleApk - APK assembly
    // =====================================================================

    @Nested
    @DisplayName("assembleApk APK assembly")
    inner class AssembleApkTests {

        @Test
        fun `assembleApk creates valid zip with manifest entry`() {
            val outputFile = File(tempDir, "test.apk")
            val manifestBytes = "<manifest/>".toByteArray()
            val loaderDex = "fake-dex".toByteArray()
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, manifestBytes, loaderDex, originApk, configFile, null
            )

            assertTrue(outputFile.exists())
            ZipFile(outputFile).use { zip ->
                assertNotNull(zip.getEntry("AndroidManifest.xml"))
                val content = zip.getInputStream(
                    zip.getEntry("AndroidManifest.xml")
                ).readBytes()
                assertContentEquals(manifestBytes, content)
            }
        }

        @Test
        fun `assembleApk creates valid zip with classes dex entry`() {
            val outputFile = File(tempDir, "test.apk")
            val loaderDex = byteArrayOf(0x64, 0x65, 0x78, 0x0A)
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), loaderDex, originApk, configFile, null
            )

            ZipFile(outputFile).use { zip ->
                val entry = zip.getEntry("classes.dex")
                assertNotNull(entry)
                assertContentEquals(loaderDex, zip.getInputStream(entry!!).readBytes())
            }
        }

        @Test
        fun `assembleApk embeds origin APK in assets`() {
            val outputFile = File(tempDir, "test.apk")
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("file.txt" to "origin-content-data")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, null
            )

            ZipFile(outputFile).use { zip ->
                val entry = zip.getEntry("assets/origin.apk")
                assertNotNull(entry)
                assertTrue(zip.getInputStream(entry!!).readBytes().isNotEmpty())
            }
        }

        @Test
        fun `assembleApk embeds config JSON in assets`() {
            val outputFile = File(tempDir, "test.apk")
            val configContent = """{"key":"value"}"""
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText(configContent) }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, null
            )

            ZipFile(outputFile).use { zip ->
                val entry = zip.getEntry("assets/multiapp_config.json")
                assertNotNull(entry)
                assertEquals(
                    configContent,
                    String(zip.getInputStream(entry!!).readBytes())
                )
            }
        }

        @Test
        fun `assembleApk includes icon when iconFile is not null`() {
            val outputFile = File(tempDir, "test.apk")
            val iconFile = File(tempDir, "ic_launcher.png").apply {
                writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            }
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, iconFile
            )

            ZipFile(outputFile).use { zip ->
                assertNotNull(zip.getEntry("res/mipmap-xxhdpi/ic_launcher.png"))
            }
        }

        @Test
        fun `assembleApk omits icon when iconFile is null`() {
            val outputFile = File(tempDir, "test.apk")
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, null
            )

            ZipFile(outputFile).use { zip ->
                assertNull(zip.getEntry("res/mipmap-xxhdpi/ic_launcher.png"))
            }
        }

        @Test
        fun `assembleApk includes patched dex files`() {
            val outputFile = File(tempDir, "test.apk")
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }
            val patchedDex1 = File(tempDir, "classes2.dex").apply {
                writeBytes("dex2".toByteArray())
            }
            val patchedDex2 = File(tempDir, "classes3.dex").apply {
                writeBytes("dex3".toByteArray())
            }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, null,
                patchedDexFiles = listOf(patchedDex1, patchedDex2)
            )

            ZipFile(outputFile).use { zip ->
                val entry2 = zip.getEntry("assets/patched/classes2.dex")
                assertNotNull(entry2)
                assertEquals("dex2", String(zip.getInputStream(entry2!!).readBytes()))

                val entry3 = zip.getEntry("assets/patched/classes3.dex")
                assertNotNull(entry3)
                assertEquals("dex3", String(zip.getInputStream(entry3!!).readBytes()))
            }
        }

        @Test
        fun `assembleApk with no patched dex files produces correct structure`() {
            val outputFile = File(tempDir, "test.apk")
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, null
            )

            ZipFile(outputFile).use { zip ->
                assertNull(zip.getEntry("assets/patched/classes2.dex"))
                // manifest + dex + origin + config = 4
                assertEquals(4, zip.size())
            }
        }

        @Test
        fun `assembleApk produces file with all expected entries`() {
            val outputFile = File(tempDir, "test.apk")
            val iconFile = File(tempDir, "ic_launcher.webp").apply {
                writeBytes("webp".toByteArray())
            }
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }
            val patchedDex = File(tempDir, "classes2.dex").apply {
                writeBytes("dex".toByteArray())
            }

            stubBuilder.assembleApk(
                outputFile,
                "manifest".toByteArray(),
                "loader".toByteArray(),
                originApk,
                configFile,
                iconFile,
                patchedDexFiles = listOf(patchedDex)
            )

            ZipFile(outputFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(entries.contains("AndroidManifest.xml"))
                assertTrue(entries.contains("classes.dex"))
                assertTrue(entries.contains("assets/origin.apk"))
                assertTrue(entries.contains("assets/multiapp_config.json"))
                assertTrue(entries.contains("res/mipmap-xxhdpi/ic_launcher.webp"))
                assertTrue(entries.contains("assets/patched/classes2.dex"))
                assertEquals(6, entries.size)
            }
        }

        @Test
        fun `copyHookNativeLibsFromApk packages hook libs embedded in host APK`() {
            val hostApk = createMinimalZip(
                File(tempDir, "host.apk"),
                mapOf(
                    "lib/arm64-v8a/libmultiapp-native.so" to "native-hook",
                    "lib/arm64-v8a/libshadowhook.so" to "shadowhook",
                    "lib/arm64-v8a/libc++_shared.so" to "cxx-runtime"
                )
            )
            val outputFile = File(tempDir, "hook-libs.zip")
            val writtenEntries = mutableSetOf<String>()

            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                val count = stubBuilder.copyHookNativeLibsFromApk(
                    hostApk = hostApk,
                    abi = "arm64-v8a",
                    zos = zos,
                    writtenEntries = writtenEntries
                )
                assertEquals(3, count)
            }

            ZipFile(outputFile).use { zip ->
                val nativeEntry = zip.getEntry("lib/arm64-v8a/libmultiapp-native.so")
                val shadowHookEntry = zip.getEntry("lib/arm64-v8a/libshadowhook.so")
                val cxxEntry = zip.getEntry("lib/arm64-v8a/libc++_shared.so")

                assertNotNull(nativeEntry)
                assertNotNull(shadowHookEntry)
                assertNotNull(cxxEntry)
                assertEquals("native-hook", String(zip.getInputStream(nativeEntry!!).readBytes()))
                assertEquals("shadowhook", String(zip.getInputStream(shadowHookEntry!!).readBytes()))
                assertEquals("cxx-runtime", String(zip.getInputStream(cxxEntry!!).readBytes()))
            }
        }
    }

    // =====================================================================
    // 3. zipalign
    // =====================================================================

    @Nested
    @DisplayName("zipalign alignment")
    inner class ZipalignTests {

        @Test
        fun `zipalign produces valid zip with same entry count`() {
            val input = createMinimalZip(
                File(tempDir, "input.apk"),
                mapOf("a.txt" to "aaa", "b.txt" to "bbb")
            )
            val output = File(tempDir, "aligned.apk")

            stubBuilder.zipalign(input, output)

            assertTrue(output.exists())
            ZipFile(input).use { src ->
                ZipFile(output).use { dst ->
                    assertEquals(src.size(), dst.size())
                }
            }
        }

        @Test
        fun `zipalign preserves entry content`() {
            val entries = mapOf(
                "AndroidManifest.xml" to "manifest-data",
                "classes.dex" to "dex-data"
            )
            val input = createMinimalZip(File(tempDir, "input.apk"), entries)
            val output = File(tempDir, "aligned.apk")

            stubBuilder.zipalign(input, output)

            ZipFile(output).use { zip ->
                assertEquals(
                    "manifest-data",
                    String(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
                )
                assertEquals(
                    "dex-data",
                    String(zip.getInputStream(zip.getEntry("classes.dex")).readBytes())
                )
            }
        }

        @Test
        fun `zipalign preserves entry names exactly`() {
            val entries = mapOf(
                "res/layout/main.xml" to "<layout/>",
                "assets/data.json" to "{}",
                "classes.dex" to "dex"
            )
            val input = createMinimalZip(File(tempDir, "input.apk"), entries)
            val output = File(tempDir, "aligned.apk")

            stubBuilder.zipalign(input, output)

            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(entries.keys, names)
            }
        }

        @Test
        fun `zipalign with single entry produces valid output`() {
            val input = createMinimalZip(
                File(tempDir, "input.apk"), mapOf("only.txt" to "data")
            )
            val output = File(tempDir, "aligned.apk")

            stubBuilder.zipalign(input, output)

            ZipFile(output).use { zip ->
                assertEquals(1, zip.size())
                assertEquals(
                    "data",
                    String(zip.getInputStream(zip.getEntry("only.txt")).readBytes())
                )
            }
        }
    }

    // =====================================================================
    // 4. build() flow (mocked file operations)
    // =====================================================================

    @Nested
    @DisplayName("build() flow")
    inner class BuildFlowTests {

        @Test
        fun `build returns output file with correct name pattern`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val result = mockBuildDependencies(config) {
                it.build(config)
            }

            assertTrue(result.name.startsWith("stub-"))
            assertTrue(result.name.endsWith(".apk"))
            assertTrue(result.name.contains(config.instanceId))
        }

        @Test
        fun `build output file exists after successful build`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val result = mockBuildDependencies(config) {
                it.build(config)
            }

            assertTrue(result.exists())
            assertTrue(result.length() > 0)
        }

        @Test
        fun `build produces APK with expected zip entries`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val result = mockBuildDependencies(config) {
                it.build(config)
            }

            ZipFile(result).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(entries.contains("AndroidManifest.xml"))
                assertTrue(entries.contains("classes.dex"))
                assertTrue(entries.contains("assets/origin.apk"))
                assertTrue(entries.contains("assets/multiapp_config.json"))
            }
        }

        @Test
        fun `build embeds correct config JSON in output APK`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val result = mockBuildDependencies(config) {
                it.build(config)
            }

            ZipFile(result).use { zip ->
                val entry = zip.getEntry("assets/multiapp_config.json")
                assertNotNull(entry)
                val json = String(zip.getInputStream(entry!!).readBytes())
                val map: Map<String, Any> = gson.fromJson(
                    json, object : TypeToken<Map<String, Any>>() {}.type
                )
                assertEquals(config.instanceId, map["instanceId"])
                assertEquals(config.stubPackageName, map["stubPackageName"])
                assertEquals(config.originalPackageName, map["originalPackageName"])
            }
        }
    }

    // =====================================================================
    // 5. Temporary file cleanup
    // =====================================================================

    @Nested
    @DisplayName("Temporary file cleanup")
    inner class TempFileCleanupTests {

        @Test
        fun `build cleans up work directory after completion`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val workDir = File(
                System.getProperty("java.io.tmpdir"),
                "multiapp_stub_${config.instanceId}"
            )
            assertFalse(workDir.exists(), "Work dir should not exist before build")

            mockBuildDependencies(config) {
                it.build(config)
            }

            assertFalse(workDir.exists(), "Work dir should be deleted after build")
        }

        @Test
        fun `build cleans up work directory even when build fails mid-way`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val workDir = File(
                System.getProperty("java.io.tmpdir"),
                "multiapp_stub_${config.instanceId}"
            )

            every { parser.parse(any()) } returns ManifestParser.ParsedManifest(
                packageName = "com.example",
                applicationClass = null,
                activities = emptyList(),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every { extractor.extractLauncherActivity(any()) } returns null

            assertFailsWith<IllegalStateException> {
                stubBuilder.build(config)
            }

            assertFalse(
                workDir.exists(),
                "Work dir should be deleted even after build failure"
            )
        }
    }

    // =====================================================================
    // 6. Error handling
    // =====================================================================

    @Nested
    @DisplayName("Error handling")
    inner class ErrorHandlingTests {

        @Test
        fun `build throws when origin APK does not exist`() {
            val config = defaultConfig.copy(
                originalSignatures = listOf("/nonexistent/path/to/origin.apk")
            )

            val error = assertFailsWith<IllegalArgumentException> {
                stubBuilder.build(config)
            }
            assertTrue(error.message!!.contains("Origin APK not found"))
        }

        @Test
        fun `build throws when originalSignatures is empty`() {
            val config = defaultConfig.copy(originalSignatures = emptyList())

            val error = assertFailsWith<IllegalStateException> {
                stubBuilder.build(config)
            }
            assertTrue(
                error.message!!.contains("originalSignatures must contain the origin APK path")
            )
        }

        @Test
        fun `build throws when no launcher activity found in origin APK`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            every { parser.parse(any()) } returns ManifestParser.ParsedManifest(
                packageName = "com.example",
                applicationClass = null,
                activities = emptyList(),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every { extractor.extractLauncherActivity(any()) } returns null

            val error = assertFailsWith<IllegalStateException> {
                stubBuilder.build(config)
            }
            assertTrue(error.message!!.contains("No launcher activity found"))
        }

        @Test
        fun `build throws when activities have no launcher intent filter`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            every { parser.parse(any()) } returns ManifestParser.ParsedManifest(
                packageName = "com.example",
                applicationClass = null,
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = "com.example.NonLauncher",
                        exported = false,
                        intentFilters = emptyList()
                    )
                ),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every { extractor.extractLauncherActivity(any()) } returns null

            val error = assertFailsWith<IllegalStateException> {
                stubBuilder.build(config)
            }
            assertTrue(error.message!!.contains("No launcher activity found"))
        }
    }

    // =====================================================================
    // 7. Edge cases: empty APK, corrupted APK
    // =====================================================================

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCaseTests {

        @Test
        fun `build with empty origin APK file throws`() {
            val emptyApk = File(tempDir, "empty.apk").apply { createNewFile() }
            val config = defaultConfig.copy(
                originalSignatures = listOf(emptyApk.absolutePath)
            )

            assertFails {
                stubBuilder.build(config)
            }
        }

        @Test
        fun `build with corrupted APK throws`() {
            val corruptedApk = File(tempDir, "corrupted.apk").apply {
                writeBytes(ByteArray(100) { (it % 256).toByte() })
            }
            val config = defaultConfig.copy(
                originalSignatures = listOf(corruptedApk.absolutePath)
            )

            assertFails {
                stubBuilder.build(config)
            }
        }

        @Test
        fun `build with origin APK in nested subdirectory`() {
            val subDir = File(tempDir, "sub1/sub2").apply { mkdirs() }
            val originApk = createMinimalZip(
                File(subDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            val result = mockBuildDependencies(config) {
                it.build(config)
            }

            assertTrue(result.exists())
        }

        @Test
        fun `assembleApk with empty manifest bytes produces valid zip`() {
            val outputFile = File(tempDir, "empty-manifest.apk")
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, ByteArray(0), ByteArray(0), originApk, configFile, null
            )

            assertTrue(outputFile.exists())
            ZipFile(outputFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml")
                assertNotNull(entry)
                assertEquals(0, zip.getInputStream(entry!!).readBytes().size)
            }
        }

        @Test
        fun `assembleApk with empty loader dex produces valid zip`() {
            val outputFile = File(tempDir, "empty-dex.apk")
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val configFile = File(tempDir, "config.json").apply { writeText("{}") }

            stubBuilder.assembleApk(
                outputFile, "manifest".toByteArray(), ByteArray(0), originApk, configFile, null
            )

            ZipFile(outputFile).use { zip ->
                val entry = zip.getEntry("classes.dex")
                assertNotNull(entry)
                assertEquals(0, zip.getInputStream(entry!!).readBytes().size)
            }
        }

        @Test
        fun `config with unicode characters in fields produces valid JSON`() {
            val config = defaultConfig.copy(
                instanceId = "test-unicode-éè",
                originalPackageName = "com.example.üö"
            )
            val json = stubBuilder.createConfigJson(config)
            val map: Map<String, Any> = gson.fromJson(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )
            assertEquals(config.instanceId, map["instanceId"])
            assertEquals(config.originalPackageName, map["originalPackageName"])
        }

        @Test
        fun `config with empty patchedDexPaths produces valid build`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath),
                patchedDexPaths = emptyList()
            )

            val result = mockBuildDependencies(config) {
                it.build(config)
            }

            assertTrue(result.exists())
        }
    }

    // =====================================================================
    // 8. getLoaderDex() behavior
    // =====================================================================

    @Nested
    @DisplayName("getLoaderDex() behavior")
    inner class GetLoaderDexTests {

        @Test
        fun `getLoaderDex via reflection returns content when dex file exists`() {
            val method: Method = StubBuilder::class.java.getDeclaredMethod("getLoaderDex")
            method.isAccessible = true

            // loader.dex exists at core/stub/src/main/assets/loader.dex
            // so getLoaderDex() should return non-empty content
            val result = method.invoke(stubBuilder) as ByteArray
            assertTrue(result.isNotEmpty(), "getLoaderDex should return content when dex exists")
        }

        @Test
        fun `build propagates getLoaderDex error when dex is absent`() {
            val originApk = createMinimalZip(
                File(tempDir, "origin.apk"), mapOf("dummy" to "content")
            )
            val config = defaultConfig.copy(
                originalSignatures = listOf(originApk.absolutePath)
            )

            every { parser.parse(any()) } returns ManifestParser.ParsedManifest(
                packageName = "com.example",
                applicationClass = null,
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = config.launchActivity,
                        exported = true,
                        intentFilters = listOf(
                            ManifestParser.IntentFilterInfo(
                                actions = listOf("android.intent.action.MAIN"),
                                categories = listOf("android.intent.category.LAUNCHER")
                            )
                        )
                    )
                ),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every { extractor.extractLauncherActivity(any()) } returns ManifestParser.ComponentInfo(
                name = config.launchActivity,
                exported = true
            )

            every { generator.generateBytes(any(), any(), any(), any()) } returns "manifest".toByteArray()

            // Use spyk to force getLoaderDex to throw (since loader.dex actually exists on disk)
            val spyBuilder = spyk(stubBuilder)
            every { spyBuilder["getLoaderDex"]() } throws IllegalStateException("loader.dex not found")
            every { spyBuilder.signApk(any(), any()) } answers {
            val output = secondArg<java.io.File>()
            output.parentFile?.mkdirs()
            output.createNewFile()
        }

            val error = assertFailsWith<IllegalStateException> {
                spyBuilder.build(config)
            }
            assertTrue(error.message!!.contains("loader.dex not found"))
        }
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    /**
     * Creates a minimal valid ZIP file with the given entries.
     */
    private fun createMinimalZip(file: File, entries: Map<String, String>): File {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    /**
     * Mocks all external dependencies for the build flow:
     * - ManifestParser.parse()
     * - ComponentExtractor.extractLauncherActivity()
     * - ManifestGenerator.generateBytes()
     * - getLoaderDex (via spyk)
     * - signApk (via spyk)
     *
     * assembleApk and zipalign use real implementations.
     */
    private fun <T> mockBuildDependencies(config: StubConfig, block: (StubBuilder) -> T): T {
        val spyBuilder = spyk(stubBuilder)

        every { parser.parse(any()) } returns ManifestParser.ParsedManifest(
            packageName = config.originalPackageName,
            applicationClass = null,
            activities = listOf(
                ManifestParser.ComponentInfo(
                    name = config.launchActivity,
                    exported = true,
                    intentFilters = listOf(
                        ManifestParser.IntentFilterInfo(
                            actions = listOf("android.intent.action.MAIN"),
                            categories = listOf("android.intent.category.LAUNCHER")
                        )
                    )
                ),
                ManifestParser.ComponentInfo(
                    name = "${config.originalPackageName}.SecondActivity",
                    exported = false
                )
            ),
            services = listOf(
                ManifestParser.ComponentInfo(
                    name = "${config.originalPackageName}.MyService"
                )
            ),
            receivers = listOf(
                ManifestParser.ComponentInfo(
                    name = "${config.originalPackageName}.BootReceiver"
                )
            ),
            providers = listOf(
                ManifestParser.ProviderInfo(
                    name = "${config.originalPackageName}.ContentProvider",
                    authorities = "${config.originalPackageName}.provider"
                )
            ),
            permissions = listOf("android.permission.INTERNET")
        )

        every { extractor.extractLauncherActivity(any()) } returns ManifestParser.ComponentInfo(
            name = config.launchActivity,
            exported = true
        )

        every { generator.generateBytes(any(), any(), any(), any()) } returns buildMockManifestXml(config).toByteArray()

        val loaderDexContent = "mock-loader-dex-content".toByteArray()
        every { spyBuilder["getLoaderDex"]() } returns loaderDexContent

        every { spyBuilder.signApk(any(), any()) } answers {
            val output = secondArg<java.io.File>()
            output.parentFile?.mkdirs()
            output.createNewFile()
        }

        return block(spyBuilder)
    }

    /**
     * Generates a mock AndroidManifest.xml for testing.
     */
    private fun buildMockManifestXml(config: StubConfig): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="${config.stubPackageName}">
            <uses-permission android:name="android.permission.INTERNET" />
            <application
                android:appComponentFactory="com.multiapp.core.loader.LoaderFactory"
                android:label="${config.stubPackageName}"
                android:icon="@mipmap/ic_launcher">
                <activity
                    android:name="${config.launchActivity}"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()
}

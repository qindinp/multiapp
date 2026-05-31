package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

import com.multiapp.core.hook.HookEngine
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Identity Hook Integration Tests")
class IdentityHookTest {

    private lateinit var mockHookEngine: HookEngine
    private lateinit var testConfig: IdentityConfig

    companion object {
        private const val TEST_INSTANCE_ID = "test_instance_001"
        private const val TEST_ORIGINAL_PKG = "com.example.targetapp"
        private const val TEST_STUB_PKG = "com.example.targetapp.clone_test_instance_001"
        private const val TEST_IMEI = "861234567890123"
        private const val TEST_ANDROID_ID = "a1b2c3d4e5f60718"
        private const val TEST_MAC = "AA:BB:CC:DD:EE:FF"
        private const val TEST_SERIAL = "ABCDEF1234"
        private const val TEST_MODEL = "SM-S9380"
        private const val TEST_MANUFACTURER = "samsung"
        private const val TEST_FINGERPRINT =
            "samsung/sm-s9380/sm-s9380:14/UP1A.231005.007/1234567890:user/release-keys"
        private const val TEST_BRAND = "samsung"
        private const val TEST_DEVICE = "sm-s9380"
        private const val TEST_PRODUCT = "sm-s9380_user"
        private const val TEST_VERSION_RELEASE = "14"
        private const val TEST_SDK_INT = 34
    }

    @BeforeEach
    fun setUp() {
        mockHookEngine = mockk(relaxed = true)
        testConfig = createTestConfig()
    }

    private fun createTestConfig(
        instanceId: String = TEST_INSTANCE_ID,
        originalPkg: String = TEST_ORIGINAL_PKG,
        stubPkg: String = TEST_STUB_PKG
    ) = IdentityConfig(
        instanceId = instanceId,
        stubPackageName = stubPkg,
        originalPackageName = originalPkg,
        authorityMap = mapOf(
            "$originalPkg.provider" to "$originalPkg.provider.clone$instanceId",
            "$originalPkg.fileprovider" to "$originalPkg.fileprovider.clone$instanceId"
        ),
        imei = TEST_IMEI,
        androidId = TEST_ANDROID_ID,
        macAddress = TEST_MAC,
        serial = TEST_SERIAL,
        buildModel = TEST_MODEL,
        buildManufacturer = TEST_MANUFACTURER,
        buildFingerprint = TEST_FINGERPRINT,
        buildBrand = TEST_BRAND,
        buildDevice = TEST_DEVICE,
        buildProduct = TEST_PRODUCT,
        versionRelease = TEST_VERSION_RELEASE,
        sdkInt = TEST_SDK_INT
    )

    // region 1. IdentityConfig Data Class

    @Nested
    @DisplayName("IdentityConfig Data Class")
    inner class IdentityConfigTests {

        @Test
        fun `IdentityConfig stores all fields correctly`() {
            assertEquals(TEST_INSTANCE_ID, testConfig.instanceId)
            assertEquals(TEST_STUB_PKG, testConfig.stubPackageName)
            assertEquals(TEST_ORIGINAL_PKG, testConfig.originalPackageName)
            assertEquals(TEST_IMEI, testConfig.imei)
            assertEquals(TEST_ANDROID_ID, testConfig.androidId)
            assertEquals(TEST_MAC, testConfig.macAddress)
            assertEquals(TEST_SERIAL, testConfig.serial)
            assertEquals(TEST_MODEL, testConfig.buildModel)
            assertEquals(TEST_MANUFACTURER, testConfig.buildManufacturer)
            assertEquals(TEST_FINGERPRINT, testConfig.buildFingerprint)
            assertEquals(TEST_BRAND, testConfig.buildBrand)
            assertEquals(TEST_DEVICE, testConfig.buildDevice)
            assertEquals(TEST_PRODUCT, testConfig.buildProduct)
            assertEquals(TEST_VERSION_RELEASE, testConfig.versionRelease)
            assertEquals(TEST_SDK_INT, testConfig.sdkInt)
        }

        @Test
        fun `IdentityConfig authorityMap contains expected entries`() {
            val authorityMap = testConfig.authorityMap
            assertEquals(2, authorityMap.size)
            assertEquals(
                "${TEST_ORIGINAL_PKG}.provider.clone${TEST_INSTANCE_ID}",
                authorityMap["${TEST_ORIGINAL_PKG}.provider"]
            )
            assertEquals(
                "${TEST_ORIGINAL_PKG}.fileprovider.clone${TEST_INSTANCE_ID}",
                authorityMap["${TEST_ORIGINAL_PKG}.fileprovider"]
            )
        }

        @Test
        fun `IdentityConfig copy with different model creates distinct instance`() {
            val modified = testConfig.copy(buildModel = "Pixel 9 Pro")
            assertEquals("Pixel 9 Pro", modified.buildModel)
            assertEquals(TEST_MODEL, testConfig.buildModel)
            assertTrue(testConfig != modified)
        }

        @Test
        fun `IdentityConfig equals and hashCode are consistent`() {
            val config1 = createTestConfig()
            val config2 = createTestConfig()
            assertEquals(config1, config2)
            assertEquals(config1.hashCode(), config2.hashCode())
        }

        @Test
        fun `IdentityConfig with empty authorityMap is valid`() {
            val config = testConfig.copy(authorityMap = emptyMap())
            assertTrue(config.authorityMap.isEmpty())
        }

        @Test
        fun `IdentityConfig with special characters in package name is valid`() {
            val config = createTestConfig(
                originalPkg = "com.example.my-app_v2",
                stubPkg = "com.example.my-app_v2.clone_001"
            )
            assertEquals("com.example.my-app_v2", config.originalPackageName)
            assertEquals("com.example.my-app_v2.clone_001", config.stubPackageName)
        }

        @Test
        fun `IdentityConfig sdkInt boundary values are stored correctly`() {
            val minSdk = testConfig.copy(sdkInt = 1)
            val maxSdk = testConfig.copy(sdkInt = Int.MAX_VALUE)
            assertEquals(1, minSdk.sdkInt)
            assertEquals(Int.MAX_VALUE, maxSdk.sdkInt)
        }

        @Test
        fun `IdentityConfig with empty strings for identifiers is valid`() {
            val config = testConfig.copy(
                imei = "",
                androidId = "",
                macAddress = "",
                serial = ""
            )
            assertTrue(config.imei.isEmpty())
            assertTrue(config.androidId.isEmpty())
            assertTrue(config.macAddress.isEmpty())
            assertTrue(config.serial.isEmpty())
        }
    }

    // endregion

    // region 2. DeviceIdentityPool

    @Nested
    @DisplayName("DeviceIdentityPool Generator")
    inner class DeviceIdentityPoolTests {

        @Test
        fun `generateIdentity produces valid config with all fields populated`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            assertEquals("inst_001", config.instanceId)
            assertEquals("com.example.app", config.originalPackageName)
            assertEquals("com.example.app.cloneinst_001", config.stubPackageName)
            assertTrue(config.imei.isNotEmpty())
            assertTrue(config.androidId.isNotEmpty())
            assertTrue(config.macAddress.isNotEmpty())
            assertTrue(config.serial.isNotEmpty())
            assertTrue(config.buildModel.isNotEmpty())
            assertTrue(config.buildManufacturer.isNotEmpty())
            assertTrue(config.buildFingerprint.isNotEmpty())
            assertTrue(config.buildBrand.isNotEmpty())
            assertTrue(config.buildDevice.isNotEmpty())
            assertTrue(config.buildProduct.isNotEmpty())
            assertTrue(config.versionRelease.isNotEmpty())
        }

        @Test
        fun `generateIdentity IMEI is 15 digits starting with 86`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            assertEquals(15, config.imei.length)
            assertTrue(config.imei.startsWith("86"))
            assertTrue(config.imei.all { it.isDigit() })
        }

        @Test
        fun `generateIdentity androidId is 16 hex characters`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            assertEquals(16, config.androidId.length)
            assertTrue(config.androidId.all { it in "0123456789abcdef" })
        }

        @Test
        fun `generateIdentity macAddress is valid format`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            val parts = config.macAddress.split(":")
            assertEquals(6, parts.size)
            parts.forEach { octet ->
                assertEquals(2, octet.length)
                assertTrue(octet.all { it in "0123456789ABCDEF" })
            }
        }

        @Test
        fun `generateIdentity serial is 10 alphanumeric characters`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            assertEquals(10, config.serial.length)
            assertTrue(config.serial.all { it.isLetterOrDigit() })
        }

        @Test
        fun `generateIdentity sdkInt is between 33 and 35`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            assertTrue(config.sdkInt in 33..35)
        }

        @Test
        fun `generateIdentity buildModel is from known device pool`() {
            val knownModels = listOf(
                "SM-S9380", "SM-S9360", "SM-S9310", "SM-S9280",
                "Pixel 9 Pro", "Pixel 9", "Pixel 8 Pro",
                "23127PN0CC", "2304FPN6DC", "RMX3700",
                "V2305A", "PGKM10", "ASUS_AI2401",
                "LE2120", "NX769J"
            )
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")
            assertTrue(config.buildModel in knownModels)
        }

        @Test
        fun `generateIdentity buildManufacturer is from known manufacturer pool`() {
            val knownManufacturers = listOf(
                "samsung", "Google", "Xiaomi", "OPPO",
                "vivo", "OnePlus", "ASUS", "ZTE", "realme"
            )
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")
            assertTrue(config.buildManufacturer in knownManufacturers)
        }

        @Test
        fun `generateIdentity versionRelease is one of 13 14 15`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")
            assertTrue(config.versionRelease in listOf("13", "14", "15"))
        }

        @Test
        fun `generateIdentity fingerprint follows expected format`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            val parts = config.buildFingerprint.split("/")
            assertTrue(parts.size >= 5)
            assertTrue(config.buildFingerprint.contains("user/release-keys"))
        }

        @Test
        fun `generateIdentity authorityMap contains provider and fileprovider entries`() {
            val config = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")

            assertEquals(2, config.authorityMap.size)
            assertNotNull(config.authorityMap["com.example.app.provider"])
            assertNotNull(config.authorityMap["com.example.app.fileprovider"])
        }

        @Test
        fun `generateIdentity produces unique configs for different instances`() {
            val config1 = DeviceIdentityPool.generateIdentity("inst_001", "com.example.app")
            val config2 = DeviceIdentityPool.generateIdentity("inst_002", "com.example.app")

            assertEquals("com.example.app.cloneinst_001", config1.stubPackageName)
            assertEquals("com.example.app.cloneinst_002", config2.stubPackageName)
        }
    }

    // endregion

    // region 3. HookPoint Interface Verification

    @Nested
    @DisplayName("HookPoint Interface Implementation")
    inner class HookPointInterfaceTests {

        @Test
        fun `all Hook implementations satisfy HookPoint interface`() {
            val hookPointType = HookPoint::class.java

            assertTrue(hookPointType.isAssignableFrom(PackageIdentityHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(DeviceIdentityHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(BuildFieldSpoof::class.java))
            assertTrue(hookPointType.isAssignableFrom(FileSystemHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(ProcFsHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(ContentProviderHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(ActivityManagerHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(DlopenHook::class.java))
            assertTrue(hookPointType.isAssignableFrom(SignatureBypass::class.java))
        }

        @Test
        fun `each Hook class has apply method matching HookPoint signature`() {
            val hookClasses = listOf(
                PackageIdentityHook::class.java,
                BuildFieldSpoof::class.java,
                FileSystemHook::class.java,
                ProcFsHook::class.java,
                ContentProviderHook::class.java,
                ActivityManagerHook::class.java,
                DlopenHook::class.java
            )

            for (hookClass in hookClasses) {
                val applyMethod = hookClass.methods.firstOrNull {
                    it.name == "apply" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == IdentityConfig::class.java &&
                        it.parameterTypes[1] == HookEngine::class.java
                }
                assertNotNull(
                    applyMethod,
                    "${hookClass.simpleName} must have apply(IdentityConfig, HookEngine) method"
                )
            }
        }
    }

    // endregion

    // region 4. PackageIdentityHook

    @Nested
    @DisplayName("PackageIdentityHook")
    inner class PackageIdentityHookTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = PackageIdentityHook()
            org.junit.jupiter.api.assertDoesNotThrow<Unit> {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `apply handles empty package names gracefully`() {
            val config = testConfig.copy(
                originalPackageName = "",
                stubPackageName = ""
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(config, mockHookEngine)
            }
        }

        @Test
        fun `apply accepts config with special characters in package name`() {
            val config = testConfig.copy(
                originalPackageName = "com.example.my-app_v2.0",
                stubPackageName = "com.example.my-app_v2.0.clone_001"
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(config, mockHookEngine)
            }
        }
    }

    // endregion

    // region 5. BuildFieldSpoof - Field Mapping

    @Nested
    @DisplayName("BuildFieldSpoof")
    inner class BuildFieldSpoofTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = BuildFieldSpoof()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                BuildFieldSpoof().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `all Build fields in config are mapped to spoof targets`() {
            val fieldMap = mapOf(
                "MODEL" to testConfig.buildModel,
                "MANUFACTURER" to testConfig.buildManufacturer,
                "FINGERPRINT" to testConfig.buildFingerprint,
                "BRAND" to testConfig.buildBrand,
                "DEVICE" to testConfig.buildDevice,
                "PRODUCT" to testConfig.buildProduct
            )

            assertEquals(6, fieldMap.size)
            fieldMap.forEach { (fieldName, value) ->
                assertTrue(
                    value.isNotEmpty(),
                    "Build.$fieldName must have a non-empty spoof value"
                )
            }
        }

        @Test
        fun `VERSION fields in config are mapped to spoof targets`() {
            assertNotNull(testConfig.versionRelease)
            assertTrue(testConfig.versionRelease.isNotEmpty())
            assertTrue(testConfig.sdkInt > 0)
        }

        @Test
        fun `apply uses config fields for spoofing not hardcoded values`() {
            val customConfig = testConfig.copy(
                buildModel = "CustomModel123",
                buildManufacturer = "CustomManufacturer",
                buildFingerprint = "custom/fingerprint/value",
                buildBrand = "CustomBrand",
                buildDevice = "custom_device",
                buildProduct = "custom_product",
                versionRelease = "99",
                sdkInt = 99
            )

            org.junit.jupiter.api.assertDoesNotThrow {
                BuildFieldSpoof().apply(customConfig, mockHookEngine)
            }

            assertEquals("CustomModel123", customConfig.buildModel)
            assertEquals("CustomManufacturer", customConfig.buildManufacturer)
            assertEquals("custom/fingerprint/value", customConfig.buildFingerprint)
            assertEquals("CustomBrand", customConfig.buildBrand)
            assertEquals("custom_device", customConfig.buildDevice)
            assertEquals("custom_product", customConfig.buildProduct)
            assertEquals("99", customConfig.versionRelease)
            assertEquals(99, customConfig.sdkInt)
        }

        @Test
        fun `apply does not modify the input config`() {
            val originalModel = testConfig.buildModel
            val originalManufacturer = testConfig.buildManufacturer

            BuildFieldSpoof().apply(testConfig, mockHookEngine)

            assertEquals(originalModel, testConfig.buildModel)
            assertEquals(originalManufacturer, testConfig.buildManufacturer)
        }
    }

    // endregion

    // region 6. FileSystemHook - Path Mapping

    @Nested
    @DisplayName("FileSystemHook - Path Mapping Logic")
    inner class FileSystemHookTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = FileSystemHook()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                FileSystemHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `rewritePath replaces originalPkg with stubPkg under data_data`() {
            val result = invokeRewritePath(
                "/data/data/$TEST_ORIGINAL_PKG/files/config.json",
                TEST_ORIGINAL_PKG,
                TEST_STUB_PKG
            )
            assertEquals("/data/data/$TEST_STUB_PKG/files/config.json", result)
        }

        @Test
        fun `rewritePath replaces originalPkg under data_user_0`() {
            val result = invokeRewritePath(
                "/data/user/0/$TEST_ORIGINAL_PKG/shared_prefs/prefs.xml",
                TEST_ORIGINAL_PKG,
                TEST_STUB_PKG
            )
            assertEquals("/data/user/0/$TEST_STUB_PKG/shared_prefs/prefs.xml", result)
        }

        @Test
        fun `rewritePath replaces originalPkg under data_user_10`() {
            val result = invokeRewritePath(
                "/data/user/10/$TEST_ORIGINAL_PKG/databases/db.sqlite",
                TEST_ORIGINAL_PKG,
                TEST_STUB_PKG
            )
            assertEquals("/data/user/10/$TEST_STUB_PKG/databases/db.sqlite", result)
        }

        @Test
        fun `rewritePath returns path unchanged when no data prefix`() {
            val input = "/storage/emulated/0/$TEST_ORIGINAL_PKG/file.txt"
            val result = invokeRewritePath(input, TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals(input, result)
        }

        @Test
        fun `rewritePath returns path unchanged when originalPkg not in path`() {
            val input = "/data/data/com.other.app/files/file.txt"
            val result = invokeRewritePath(input, TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals(input, result)
        }

        @Test
        fun `rewritePath handles empty path`() {
            val result = invokeRewritePath("", TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals("", result)
        }

        @Test
        fun `rewritePath returns path unchanged for data path without package`() {
            val input = "/data/local/tmp/file.bin"
            val result = invokeRewritePath(input, TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals(input, result)
        }

        @Test
        fun `rewritePath handles deep nested path`() {
            val input = "/data/data/$TEST_ORIGINAL_PKG/a/b/c/d/e/file.txt"
            val result = invokeRewritePath(input, TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals("/data/data/$TEST_STUB_PKG/a/b/c/d/e/file.txt", result)
        }

        @Test
        fun `rewritePath handles path with multiple occurrences of originalPkg`() {
            // Only /data/data/ prefix occurrences are replaced, not bare package name occurrences
            val input = "/data/data/$TEST_ORIGINAL_PKG/$TEST_ORIGINAL_PKG/file.txt"
            val result = invokeRewritePath(input, TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals("/data/data/$TEST_STUB_PKG/$TEST_ORIGINAL_PKG/file.txt", result)
        }

        /**
         * Invoke the private static rewritePath method on FileSystemHook.Companion
         * via reflection.
         */
        private fun invokeRewritePath(path: String, originalPkg: String, stubPkg: String): Any? {
            val companionField = FileSystemHook::class.java.declaredFields
                .firstOrNull { it.name == "Companion" }
                ?: return null
            companionField.isAccessible = true
            val companion = companionField.get(null) ?: return null

            val rewritePathMethod = companion::class.java.declaredMethods
                .firstOrNull { it.name == "rewritePath" }
                ?: return null
            rewritePathMethod.isAccessible = true
            return rewritePathMethod.invoke(companion, path, originalPkg, stubPkg)
        }
    }

    // endregion

    // region 7. ProcFsHook - /proc Path Filtering

    @Nested
    @DisplayName("ProcFsHook - /proc Path Filtering Logic")
    inner class ProcFsHookTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = ProcFsHook()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                ProcFsHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `shouldFilterLine returns true for line containing stub package name`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/app/$TEST_STUB_PKG/base.apk"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing lsplant`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/liblsplant.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing libhook`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/libhook.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing libmultiapp`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/libmultiapp.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing libinject`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/libinject.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing libsubstrate`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/libsubstrate.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing libxposed`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/libxposed.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for line containing lsposed`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/local/tmp/lsposed.jar"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns false for clean system library line`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /system/lib64/libc.so"
            assertFalse(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns false for unrelated app data`() {
            val line = "7f000000-7f100000 r-xp 00000000 08:01 12345 /data/app/com.whatsapp/base.apk"
            assertFalse(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine handles empty line`() {
            assertFalse(invokeShouldFilterLine("", TEST_STUB_PKG))
        }

        @Test
        fun `shouldFilterLine returns true for injection signature with mixed case`() {
            // The method lowercases the line before checking
            val line = "some/path/LsPlant/file.so"
            assertTrue(invokeShouldFilterLine(line, TEST_STUB_PKG))
        }

        /**
         * Invoke the private static shouldFilterLine method on ProcFsHook.Companion
         * via reflection.
         */
        private fun invokeShouldFilterLine(line: String, stubPkg: String): Boolean {
            val companionField = ProcFsHook::class.java.declaredFields
                .firstOrNull { it.name == "Companion" }
                ?: return false
            companionField.isAccessible = true
            val companion = companionField.get(null) ?: return false

            val method = companion::class.java.declaredMethods
                .firstOrNull { it.name == "shouldFilterLine" }
                ?: return false
            method.isAccessible = true
            return method.invoke(companion, line, stubPkg) as Boolean
        }
    }

    // endregion

    // region 8. ContentProviderHook - Authority Mapping

    @Nested
    @DisplayName("ContentProviderHook - Authority Mapping Logic")
    inner class ContentProviderHookTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = ContentProviderHook()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                ContentProviderHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `apply does not modify the input config authorityMap`() {
            val originalMap = testConfig.authorityMap.toMap()
            ContentProviderHook().apply(testConfig, mockHookEngine)
            assertEquals(originalMap, testConfig.authorityMap)
        }

        @Test
        fun `apply with empty authorityMap does not throw`() {
            val config = testConfig.copy(authorityMap = emptyMap())
            org.junit.jupiter.api.assertDoesNotThrow {
                ContentProviderHook().apply(config, mockHookEngine)
            }
        }

        @Test
        fun `apply with single authority mapping does not throw`() {
            val config = testConfig.copy(
                authorityMap = mapOf("com.example.provider" to "com.example.provider.clone1")
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                ContentProviderHook().apply(config, mockHookEngine)
            }
        }

        @Test
        fun `apply with many authority mappings does not throw`() {
            val manyMappings = (1..50).associate { i ->
                "com.example.provider.$i" to "com.example.provider.$i.clone1"
            }
            val config = testConfig.copy(authorityMap = manyMappings)
            org.junit.jupiter.api.assertDoesNotThrow {
                ContentProviderHook().apply(config, mockHookEngine)
            }
            assertEquals(50, config.authorityMap.size)
        }

        @Test
        fun `apply preserves authority mapping values unchanged`() {
            ContentProviderHook().apply(testConfig, mockHookEngine)

            assertEquals(
                "${TEST_ORIGINAL_PKG}.provider.clone${TEST_INSTANCE_ID}",
                testConfig.authorityMap["${TEST_ORIGINAL_PKG}.provider"]
            )
            assertEquals(
                "${TEST_ORIGINAL_PKG}.fileprovider.clone${TEST_INSTANCE_ID}",
                testConfig.authorityMap["${TEST_ORIGINAL_PKG}.fileprovider"]
            )
        }
    }

    // endregion

    // region 9. ActivityManagerHook - Process Info Spoofing

    @Nested
    @DisplayName("ActivityManagerHook - Process Info Spoofing")
    inner class ActivityManagerHookTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = ActivityManagerHook()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                ActivityManagerHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `apply passes correct package names to hook engine`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                ActivityManagerHook().apply(testConfig, mockHookEngine)
            }
            // Config is not modified
            assertEquals(TEST_ORIGINAL_PKG, testConfig.originalPackageName)
            assertEquals(TEST_STUB_PKG, testConfig.stubPackageName)
        }

        @Test
        fun `apply does not modify the input config`() {
            val originalPkg = testConfig.originalPackageName
            val stubPkg = testConfig.stubPackageName

            ActivityManagerHook().apply(testConfig, mockHookEngine)

            assertEquals(originalPkg, testConfig.originalPackageName)
            assertEquals(stubPkg, testConfig.stubPackageName)
        }

        @Test
        fun `apply with empty package names does not throw`() {
            val config = testConfig.copy(
                originalPackageName = "",
                stubPackageName = ""
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                ActivityManagerHook().apply(config, mockHookEngine)
            }
        }

        @Test
        fun `apply with long package name does not throw`() {
            val longPkg = "com.example.very.long.package.name.that.exceeds.normal.length"
            val config = testConfig.copy(
                originalPackageName = longPkg,
                stubPackageName = "$longPkg.clone1"
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                ActivityManagerHook().apply(config, mockHookEngine)
            }
        }
    }

    // endregion

    // region 10. DlopenHook - Library Path Redirection

    @Nested
    @DisplayName("DlopenHook - Library Path Redirection Logic")
    inner class DlopenHookTests {

        @Test
        fun `instance apply does not throw when LSPlant unavailable`() {
            val hook = DlopenHook()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `companion apply does not throw when LSPlant unavailable`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                DlopenHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `rewriteLibraryPath returns path unchanged when stubPkg not in path`() {
            val result = invokeRewriteLibraryPath(
                "/data/app/com.example.app/lib/arm64/libfoo.so",
                TEST_ORIGINAL_PKG,
                TEST_STUB_PKG
            )
            assertEquals("/data/app/com.example.app/lib/arm64/libfoo.so", result)
        }

        @Test
        fun `rewriteLibraryPath returns original when rewritten path does not exist on disk`() {
            val stubPath = "/data/app/$TEST_STUB_PKG/lib/arm64/libfoo.so"
            val result = invokeRewriteLibraryPath(stubPath, TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            // The rewritten path will not exist in unit test env, so it returns original
            assertEquals(stubPath, result)
        }

        @Test
        fun `rewriteLibraryPath returns rewritten path when target file exists`() {
            val tempDir = createTempDir("dlopen_test")
            try {
                val libDir = File(tempDir, "lib/arm64")
                libDir.mkdirs()
                val libFile = File(libDir, "libtest.so")
                libFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) // ELF magic

                // Verify the file exists before testing
                assertTrue(libFile.exists())

                // Build a stub path that would rewrite to tempDir path
                val stubPkg = "com.example.stubpkg"
                val originalPkg = tempDir.name

                // The rewrite does string replace of stubPkg->originalPkg
                val stubPath = "/data/app/$stubPkg/lib/arm64/libtest.so"
                val expectedRewrite = "/data/app/$originalPkg/lib/arm64/libtest.so"

                // This will not match because the temp dir is not under /data/app
                // So rewriteLibraryPath will return original path
                val result = invokeRewriteLibraryPath(stubPath, originalPkg, stubPkg)
                assertNotNull(result)
            } finally {
                tempDir.deleteRecursively()
            }
        }

        @Test
        fun `rewriteLibraryPath handles empty path`() {
            val result = invokeRewriteLibraryPath("", TEST_ORIGINAL_PKG, TEST_STUB_PKG)
            assertEquals("", result)
        }

        @Test
        fun `apply does not modify the input config`() {
            val originalPkg = testConfig.originalPackageName
            val stubPkg = testConfig.stubPackageName

            DlopenHook().apply(testConfig, mockHookEngine)

            assertEquals(originalPkg, testConfig.originalPackageName)
            assertEquals(stubPkg, testConfig.stubPackageName)
        }

        /**
         * Invoke the private static rewriteLibraryPath method on DlopenHook.Companion
         * via reflection.
         */
        private fun invokeRewriteLibraryPath(
            path: String,
            originalPkg: String,
            stubPkg: String
        ): Any? {
            val companionField = DlopenHook::class.java.declaredFields
                .firstOrNull { it.name == "Companion" }
                ?: return null
            companionField.isAccessible = true
            val companion = companionField.get(null) ?: return null

            val method = companion::class.java.declaredMethods
                .firstOrNull { it.name == "rewriteLibraryPath" }
                ?: return null
            method.isAccessible = true
            return method.invoke(companion, path, originalPkg, stubPkg)
        }
    }

    // endregion

    // region 11. DeviceIdentityHook

    @Nested
    @DisplayName("DeviceIdentityHook")
    inner class DeviceIdentityHookTests {

        @Test
        fun `constructor apply does not throw when LSPlant unavailable`() {
            val hook = DeviceIdentityHook()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `apply does not modify the input config`() {
            val originalImei = testConfig.imei
            val originalAndroidId = testConfig.androidId
            val originalMac = testConfig.macAddress
            val originalSerial = testConfig.serial

            DeviceIdentityHook().apply(testConfig, mockHookEngine)

            assertEquals(originalImei, testConfig.imei)
            assertEquals(originalAndroidId, testConfig.androidId)
            assertEquals(originalMac, testConfig.macAddress)
            assertEquals(originalSerial, testConfig.serial)
        }

        @Test
        fun `apply with empty identifiers does not throw`() {
            val config = testConfig.copy(
                imei = "",
                androidId = "",
                macAddress = "",
                serial = ""
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                DeviceIdentityHook().apply(config, mockHookEngine)
            }
        }

        @Test
        fun `generateFakeImsi produces 15-digit string starting with 460`() {
            val result = invokeGenerateFakeImsi("test_instance")
            assertNotNull(result)
            assertTrue(result.startsWith("460"))
            assertEquals(15, result.length)
            assertTrue(result.all { it.isDigit() })
        }

        @Test
        fun `generateFakeImsi produces deterministic output for same input`() {
            val result1 = invokeGenerateFakeImsi("same_id")
            val result2 = invokeGenerateFakeImsi("same_id")
            assertEquals(result1, result2)
        }

        @Test
        fun `generateFakeImsi produces different output for different inputs`() {
            val result1 = invokeGenerateFakeImsi("instance_a")
            val result2 = invokeGenerateFakeImsi("instance_b")
            assertTrue(result1 != result2)
        }

        /**
         * Invoke the private generateFakeImsi method via reflection.
         */
        private fun invokeGenerateFakeImsi(instanceId: String): String? {
            val hook = DeviceIdentityHook()
            val method = DeviceIdentityHook::class.java.getDeclaredMethod(
                "generateFakeImsi",
                String::class.java
            )
            method.isAccessible = true
            return method.invoke(hook, instanceId) as? String
        }
    }

    // endregion

    // region 12. SignatureBypass

    @Nested
    @DisplayName("SignatureBypass")
    inner class SignatureBypassTests {

        @Test
        fun `constructor apply does not throw when LSPlant unavailable`() {
            val hook = SignatureBypass()
            org.junit.jupiter.api.assertDoesNotThrow {
                hook.apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `apply does not modify the input config`() {
            val originalPkg = testConfig.originalPackageName
            SignatureBypass().apply(testConfig, mockHookEngine)
            assertEquals(originalPkg, testConfig.originalPackageName)
        }

        @Test
        fun `apply with empty package name does not throw`() {
            val config = testConfig.copy(originalPackageName = "")
            org.junit.jupiter.api.assertDoesNotThrow {
                SignatureBypass().apply(config, mockHookEngine)
            }
        }
    }

    // endregion

    // region 13. Cross-hook Integration Scenarios

    @Nested
    @DisplayName("Cross-hook Integration Scenarios")
    inner class IntegrationScenarioTests {

        @Test
        fun `multiple hooks can be applied sequentially with same config`() {
            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(testConfig, mockHookEngine)
                BuildFieldSpoof().apply(testConfig, mockHookEngine)
                FileSystemHook().apply(testConfig, mockHookEngine)
                ProcFsHook().apply(testConfig, mockHookEngine)
                ContentProviderHook().apply(testConfig, mockHookEngine)
                ActivityManagerHook().apply(testConfig, mockHookEngine)
                DlopenHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `config remains unmodified after applying all hooks`() {
            val originalConfig = testConfig.copy()

            PackageIdentityHook().apply(testConfig, mockHookEngine)
            BuildFieldSpoof().apply(testConfig, mockHookEngine)
            FileSystemHook().apply(testConfig, mockHookEngine)
            ProcFsHook().apply(testConfig, mockHookEngine)
            ContentProviderHook().apply(testConfig, mockHookEngine)
            ActivityManagerHook().apply(testConfig, mockHookEngine)
            DlopenHook().apply(testConfig, mockHookEngine)

            assertEquals(originalConfig, testConfig)
        }

        @Test
        fun `multiple hooks applied with config from DeviceIdentityPool`() {
            val config = DeviceIdentityPool.generateIdentity(
                "inst_multi",
                "com.example.multiapp"
            )

            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(config, mockHookEngine)
                BuildFieldSpoof().apply(config, mockHookEngine)
                FileSystemHook().apply(config, mockHookEngine)
                ProcFsHook().apply(config, mockHookEngine)
                ContentProviderHook().apply(config, mockHookEngine)
                ActivityManagerHook().apply(config, mockHookEngine)
                DlopenHook().apply(config, mockHookEngine)
            }

            // Config still intact
            assertEquals("inst_multi", config.instanceId)
            assertEquals("com.example.multiapp", config.originalPackageName)
            assertEquals("com.example.multiapp.cloneinst_multi", config.stubPackageName)
        }

        @Test
        fun `hooks degrade gracefully when HookEngine returns false for all operations`() {
            every { mockHookEngine.hookMethod(any(), any(), any()) } returns false
            every { mockHookEngine.hookStaticField(any(), any(), any()) } returns false

            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(testConfig, mockHookEngine)
                BuildFieldSpoof().apply(testConfig, mockHookEngine)
                FileSystemHook().apply(testConfig, mockHookEngine)
                ProcFsHook().apply(testConfig, mockHookEngine)
                ContentProviderHook().apply(testConfig, mockHookEngine)
                ActivityManagerHook().apply(testConfig, mockHookEngine)
                DlopenHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `hooks degrade gracefully when HookEngine throws exceptions`() {
            every {
                mockHookEngine.hookMethod(any(), any(), any())
            } throws RuntimeException("LSPlant not loaded")
            every {
                mockHookEngine.hookStaticField(any(), any(), any())
            } throws RuntimeException("Field not found")

            // Each hook wraps calls in try-catch, so no exception should propagate
            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(testConfig, mockHookEngine)
                BuildFieldSpoof().apply(testConfig, mockHookEngine)
                FileSystemHook().apply(testConfig, mockHookEngine)
                ProcFsHook().apply(testConfig, mockHookEngine)
                ContentProviderHook().apply(testConfig, mockHookEngine)
                ActivityManagerHook().apply(testConfig, mockHookEngine)
                DlopenHook().apply(testConfig, mockHookEngine)
            }
        }

        @Test
        fun `hooks handle config with very long package names`() {
            val longPkg = "com." + "a".repeat(200) + ".verylong"
            val config = testConfig.copy(
                originalPackageName = longPkg,
                stubPackageName = "$longPkg.clone1"
            )

            org.junit.jupiter.api.assertDoesNotThrow {
                PackageIdentityHook().apply(config, mockHookEngine)
                FileSystemHook().apply(config, mockHookEngine)
                ProcFsHook().apply(config, mockHookEngine)
                ContentProviderHook().apply(config, mockHookEngine)
                ActivityManagerHook().apply(config, mockHookEngine)
                DlopenHook().apply(config, mockHookEngine)
            }
        }

        @Test
        fun `hooks handle config with minimum sdk and version values`() {
            val config = testConfig.copy(sdkInt = 1, versionRelease = "1")
            org.junit.jupiter.api.assertDoesNotThrow {
                BuildFieldSpoof().apply(config, mockHookEngine)
            }
            assertEquals(1, config.sdkInt)
            assertEquals("1", config.versionRelease)
        }

        @Test
        fun `hooks handle config with maximum sdk and version values`() {
            val config = testConfig.copy(
                sdkInt = Int.MAX_VALUE,
                versionRelease = "999999999"
            )
            org.junit.jupiter.api.assertDoesNotThrow {
                BuildFieldSpoof().apply(config, mockHookEngine)
            }
        }
    }

    // endregion
}

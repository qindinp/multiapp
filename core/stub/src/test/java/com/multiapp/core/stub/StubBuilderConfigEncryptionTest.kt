package com.multiapp.core.stub

import com.multiapp.core.common.ConfigEncryptor
import com.multiapp.core.manifest.StubConfig
import com.multiapp.core.manifest.DeviceIdentityConfig
import com.multiapp.core.model.CloneProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * StubBuilder 配置加密测试
 *
 * 验证 S0-1 修复：ConfigEncryptor 失败时应抛出 IllegalStateException，
 * 而非降级为明文存储。同时验证敏感信息不会以明文存储。
 */
class StubBuilderConfigEncryptionTest {

    private fun createTestConfig(
        imei: String = "860123456789012",
        androidId: String = "a1b2c3d4e5f6g7h8",
        macAddress: String = "AA:BB:CC:DD:EE:FF",
        serial: String = "R5CR12345AB"
    ): StubConfig {
        return StubConfig(
            instanceId = "test_001",
            stubPackageName = "com.test.clonestub",
            originalPackageName = "com.test.app",
            appLabel = "TestApp",
            launchActivity = "com.test.app.MainActivity",
            authorityMap = mapOf("com.test.provider" to "com.test.clonestub.provider"),
            originalSignatures = listOf("/fake/origin.apk"),
            cloneProfile = CloneProfile.NORMAL,
            deviceIdentity = DeviceIdentityConfig(
                imei = imei,
                androidId = androidId,
                macAddress = macAddress,
                serial = serial,
                buildModel = "Pixel 9",
                buildManufacturer = "Google",
                buildFingerprint = "google/raven/raven:14/test",
                buildBrand = "google",
                buildDevice = "raven",
                buildProduct = "raven",
                versionRelease = "14",
                sdkInt = 34
            )
        )
    }

    @Test
    fun `createConfigJson produces encrypted sensitive fields`() {
        val builder = StubBuilder()
        val config = createTestConfig()

        val configJson = builder.createConfigJson(config)

        // 验证 JSON 包含 ENC: 前缀（加密后的敏感字段）
        assertTrue(configJson.contains("ENC:")) { "Config JSON should contain encrypted fields with ENC: prefix" }

        // 验证敏感信息不会以明文出现
        assertFalse(configJson.contains("860123456789012")) { "IMEI should not appear in plaintext" }
        assertFalse(configJson.contains("a1b2c3d4e5f6g7h8")) { "AndroidId should not appear in plaintext" }
        assertFalse(configJson.contains("AA:BB:CC:DD:EE:FF")) { "MAC address should not appear in plaintext" }
        assertFalse(configJson.contains("R5CR12345AB")) { "Serial should not appear in plaintext" }

        // 验证非敏感字段仍然明文存在
        assertTrue(configJson.contains("test_001")) { "instanceId should be in plaintext" }
        assertTrue(configJson.contains("com.test.clonestub")) { "stubPackageName should be in plaintext" }
        assertTrue(configJson.contains("Pixel 9")) { "buildModel should be in plaintext" }
    }

    @Test
    fun `createConfigJson can be decrypted back to original values`() {
        val builder = StubBuilder()
        val config = createTestConfig()

        val configJson = builder.createConfigJson(config)

        // 解析 JSON 并解密验证
        val gson = com.google.gson.Gson()
        @Suppress("UNCHECKED_CAST")
        val configMap = gson.fromJson(configJson, Map::class.java) as Map<String, Any?>

        assertTrue(ConfigEncryptor.hasEncryptedFields(configMap)) { "Parsed config should have encrypted fields" }

        val decrypted = ConfigEncryptor.decryptSensitiveFields(
            configMap, config.stubPackageName, config.instanceId
        )

        @Suppress("UNCHECKED_CAST")
        val identity = decrypted["deviceIdentity"] as Map<String, Any?>

        assertEquals("860123456789012", identity["imei"])
        assertEquals("a1b2c3d4e5f6g7h8", identity["androidId"])
        assertEquals("AA:BB:CC:DD:EE:FF", identity["macAddress"])
        assertEquals("R5CR12345AB", identity["serial"])
        assertEquals("Pixel 9", identity["buildModel"])
    }

    @Test
    fun `createConfigJson with empty sensitive fields does not encrypt them`() {
        val builder = StubBuilder()
        val config = createTestConfig(imei = "", macAddress = "")

        val configJson = builder.createConfigJson(config)

        // 空字符串不应被加密（不以 ENC: 开头）
        val gson = com.google.gson.Gson()
        @Suppress("UNCHECKED_CAST")
        val configMap = gson.fromJson(configJson, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val identity = configMap["deviceIdentity"] as Map<String, Any?>

        assertEquals("", identity["imei"]) { "Empty IMEI should remain empty" }
        assertEquals("", identity["macAddress"]) { "Empty MAC should remain empty" }

        // 非空敏感字段仍应加密
        assertTrue((identity["androidId"] as String).startsWith("ENC:")) { "Non-empty androidId should be encrypted" }
    }

    @Test
    fun `different instances produce different ciphertext for same values`() {
        val builder = StubBuilder()
        val config1 = createTestConfig()
        val config2 = StubConfig(
            instanceId = "test_002",  // 不同的 instanceId
            stubPackageName = "com.test.clonestub",
            originalPackageName = "com.test.app",
            appLabel = "TestApp",
            launchActivity = "com.test.app.MainActivity",
            authorityMap = emptyMap(),
            originalSignatures = listOf("/fake/origin.apk"),
            cloneProfile = CloneProfile.NORMAL,
            deviceIdentity = config1.deviceIdentity
        )

        val json1 = builder.createConfigJson(config1)
        val json2 = builder.createConfigJson(config2)

        // 同一设备信息在不同 instanceId 下应产生不同的密文
        val gson = com.google.gson.Gson()
        @Suppress("UNCHECKED_CAST")
        val map1 = gson.fromJson(json1, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val map2 = gson.fromJson(json2, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val id1 = map1["deviceIdentity"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val id2 = map2["deviceIdentity"] as Map<String, Any?>

        assertNotEquals(id1["imei"], id2["imei"]) {
            "Same IMEI encrypted with different instanceId should produce different ciphertext"
        }
    }

    @Test
    fun `createConfigJson preserves device build info as plaintext`() {
        val builder = StubBuilder()
        val config = createTestConfig()

        val configJson = builder.createConfigJson(config)

        assertTrue(configJson.contains("Google"))
        assertTrue(configJson.contains("google/raven/raven:14/test"))
        assertTrue(configJson.contains("raven"))
        assertTrue(configJson.contains("14"))
        assertTrue(configJson.contains("34"))
    }
}

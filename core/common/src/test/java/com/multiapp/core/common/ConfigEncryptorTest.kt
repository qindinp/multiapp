package com.multiapp.core.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * ConfigEncryptor 单元测试
 */
class ConfigEncryptorTest {

    private val testConfigMap = mapOf<String, Any?>(
        "instanceId" to "test_001",
        "stubPackageName" to "com.test.clonestub",
        "originalPackageName" to "com.test.app",
        "launchActivity" to "com.test.app.MainActivity",
        "authorityMap" to mapOf("com.test.provider" to "com.test.clonestub.provider"),
        "deviceIdentity" to mapOf(
            "imei" to "860123456789012",
            "androidId" to "a1b2c3d4e5f6g7h8",
            "macAddress" to "AA:BB:CC:DD:EE:FF",
            "serial" to "R5CR12345AB",
            "buildModel" to "Pixel 9",
            "buildManufacturer" to "Google",
            "buildFingerprint" to "google/raven/raven:14/test",
            "buildBrand" to "google",
            "buildDevice" to "raven",
            "buildProduct" to "raven",
            "versionRelease" to "14",
            "sdkInt" to "34"
        )
    )

    @Test
    fun `encryptSensitiveFields encrypts imei androidId mac serial`() {
        val encrypted = ConfigEncryptor.encryptSensitiveFields(
            testConfigMap, "com.test.clonestub", "test_001"
        )

        @Suppress("UNCHECKED_CAST")
        val identity = encrypted["deviceIdentity"] as Map<String, Any?>

        assertTrue((identity["imei"] as String).startsWith("ENC:")) { "imei should be encrypted" }
        assertTrue((identity["androidId"] as String).startsWith("ENC:")) { "androidId should be encrypted" }
        assertTrue((identity["macAddress"] as String).startsWith("ENC:")) { "macAddress should be encrypted" }
        assertTrue((identity["serial"] as String).startsWith("ENC:")) { "serial should be encrypted" }

        assertEquals("Pixel 9", identity["buildModel"])
        assertEquals("Google", identity["buildManufacturer"])
        assertEquals("test_001", encrypted["instanceId"])
        assertEquals("com.test.clonestub", encrypted["stubPackageName"])
    }

    @Test
    fun `decryptSensitiveFields recovers original values`() {
        val encrypted = ConfigEncryptor.encryptSensitiveFields(
            testConfigMap, "com.test.clonestub", "test_001"
        )
        val decrypted = ConfigEncryptor.decryptSensitiveFields(
            encrypted, "com.test.clonestub", "test_001"
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
    fun `hasEncryptedFields detects encrypted config`() {
        val encrypted = ConfigEncryptor.encryptSensitiveFields(
            testConfigMap, "com.test.clonestub", "test_001"
        )
        assertTrue(ConfigEncryptor.hasEncryptedFields(encrypted)) { "Should detect encrypted fields" }
        assertFalse(ConfigEncryptor.hasEncryptedFields(testConfigMap)) { "Plain config should not have encrypted fields" }
    }

    @Test
    fun `decrypt with wrong key keeps encrypted values`() {
        val encrypted = ConfigEncryptor.encryptSensitiveFields(
            testConfigMap, "com.test.clonestub", "test_001"
        )
        val decrypted = ConfigEncryptor.decryptSensitiveFields(
            encrypted, "com.wrong.package", "wrong_id"
        )

        @Suppress("UNCHECKED_CAST")
        val identity = decrypted["deviceIdentity"] as Map<String, Any?>

        val imei = identity["imei"] as String
        assertTrue(imei.startsWith("ENC:")) { "Should keep encrypted value on decrypt failure" }
    }

    @Test
    fun `encrypt uses random IV so same input produces different ciphertext`() {
        val encrypted1 = ConfigEncryptor.encryptSensitiveFields(
            testConfigMap, "com.test.clonestub", "test_001"
        )
        val encrypted2 = ConfigEncryptor.encryptSensitiveFields(
            testConfigMap, "com.test.clonestub", "test_001"
        )

        @Suppress("UNCHECKED_CAST")
        val id1 = encrypted1["deviceIdentity"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val id2 = encrypted2["deviceIdentity"] as Map<String, Any?>

        assertNotEquals(id1["imei"], id2["imei"]) { "Same input should produce different ciphertext (random IV)" }

        val dec1 = ConfigEncryptor.decryptSensitiveFields(encrypted1, "com.test.clonestub", "test_001")
        val dec2 = ConfigEncryptor.decryptSensitiveFields(encrypted2, "com.test.clonestub", "test_001")

        @Suppress("UNCHECKED_CAST")
        val identity1 = dec1["deviceIdentity"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val identity2 = dec2["deviceIdentity"] as Map<String, Any?>

        assertEquals("860123456789012", identity1["imei"])
        assertEquals("860123456789012", identity2["imei"])
    }

    @Test
    fun `empty sensitive fields are not encrypted`() {
        val configWithEmpty = testConfigMap.toMutableMap()
        configWithEmpty["deviceIdentity"] = mapOf(
            "imei" to "",
            "androidId" to "test123",
            "macAddress" to "",
            "serial" to "ABC"
        )

        val encrypted = ConfigEncryptor.encryptSensitiveFields(
            configWithEmpty, "com.test.clonestub", "test_001"
        )

        @Suppress("UNCHECKED_CAST")
        val identity = encrypted["deviceIdentity"] as Map<String, Any?>

        assertEquals("", identity["imei"]) { "Empty string should not be encrypted" }
        assertEquals("", identity["macAddress"]) { "Empty string should not be encrypted" }
        assertTrue((identity["androidId"] as String).startsWith("ENC:")) { "Non-empty should be encrypted" }
        assertTrue((identity["serial"] as String).startsWith("ENC:")) { "Non-empty should be encrypted" }
    }
}

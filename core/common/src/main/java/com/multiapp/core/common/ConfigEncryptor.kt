package com.multiapp.core.common

import timber.log.Timber
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Config JSON 敏感字段加密/解密工具
 *
 * 使用 AES-256-GCM 加密设备标识 (IMEI, MAC, AndroidId, Serial 等)
 * 密钥从 stubPackageName + instanceId 派生 (PBKDF2)
 *
 * 安全性: 攻击者即使提取 APK 也无法读取明文设备标识,
 * 因为密钥依赖于运行时才确定的 stub 包名
 */
object ConfigEncryptor {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH = 256
    private const val ITERATION_COUNT = 10000
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val SALT = "MultiApp:ConfigEncrypt:v1:"

    /**
     * 敏感字段名列表
     */
    private val SENSITIVE_FIELDS = setOf(
        "imei", "androidId", "macAddress", "serial"
    )

    /**
     * 加密 config map 中的敏感字段
     *
     * @param configMap 原始配置 map
     * @param stubPackageName Stub 包名 (用于派生密钥)
     * @param instanceId 实例 ID (用于派生密钥)
     * @return 加密后的 map (敏感字段替换为 Base64 编码的密文)
     */
    fun encryptSensitiveFields(
        configMap: Map<String, Any?>,
        stubPackageName: String,
        instanceId: String
    ): Map<String, Any?> {
        val key = deriveKey(stubPackageName, instanceId)
        val result = configMap.toMutableMap()

        val deviceIdentity = result["deviceIdentity"]
        if (deviceIdentity is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val mutableIdentity = (deviceIdentity as Map<String, Any?>).toMutableMap()
            for (field in SENSITIVE_FIELDS) {
                val value = mutableIdentity[field]
                if (value is String && value.isNotEmpty()) {
                    mutableIdentity[field] = encrypt(value, key)
                }
            }
            result["deviceIdentity"] = mutableIdentity
        }

        return result
    }

    /**
     * 解密 config map 中的敏感字段
     *
     * @param configMap 加密后的配置 map
     * @param stubPackageName Stub 包名
     * @param instanceId 实例 ID
     * @return 解密后的 map
     */
    fun decryptSensitiveFields(
        configMap: Map<String, Any?>,
        stubPackageName: String,
        instanceId: String
    ): Map<String, Any?> {
        val key = deriveKey(stubPackageName, instanceId)
        val result = configMap.toMutableMap()

        val deviceIdentity = result["deviceIdentity"]
        if (deviceIdentity is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val mutableIdentity = (deviceIdentity as Map<String, Any?>).toMutableMap()
            for (field in SENSITIVE_FIELDS) {
                val value = mutableIdentity[field]
                if (value is String && value.startsWith("ENC:")) {
                    try {
                        mutableIdentity[field] = decrypt(value.removePrefix("ENC:"), key)
                    } catch (e: Exception) {
                        Timber.w(e, "ConfigEncryptor: failed to decrypt $field, keeping raw")
                    }
                }
            }
            result["deviceIdentity"] = mutableIdentity
        }

        return result
    }

    /**
     * 检查 config map 中是否有加密字段
     */
    fun hasEncryptedFields(configMap: Map<String, Any?>): Boolean {
        val deviceIdentity = configMap["deviceIdentity"]
        if (deviceIdentity is Map<*, *>) {
            return SENSITIVE_FIELDS.any { field ->
                val value = deviceIdentity[field]
                value is String && value.startsWith("ENC:")
            }
        }
        return false
    }

    private fun deriveKey(stubPackageName: String, instanceId: String): SecretKeySpec {
        val password = "$stubPackageName:$instanceId".toCharArray()
        val salt = (SALT + stubPackageName).toByteArray()
        val spec = PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(plaintext: String, key: SecretKeySpec): String {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // IV + ciphertext -> Base64, 加 "ENC:" 前缀标识
        val combined = iv + ciphertext
        return "ENC:" + Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encoded: String, key: SecretKeySpec): String {
        val combined = Base64.getDecoder().decode(encoded)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
}

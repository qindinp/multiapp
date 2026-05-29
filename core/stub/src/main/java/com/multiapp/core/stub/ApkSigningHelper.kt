package com.multiapp.core.stub

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * APK 签名工具
 *
 * 使用 AndroidKeyStore 生成 RSA 密钥对和自签名证书。
 * 首次调用时生成密钥，后续复用。
 */
object ApkSigningHelper {

    private const val KEY_ALIAS = "multiapp_stub_signing"

    /**
     * 获取或创建签名密钥对和证书
     *
     * @return Pair(PrivateKey, X509Certificate)
     */
    fun getOrCreateSigningKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)

        if (!ks.containsAlias(KEY_ALIAS)) {
            Timber.d("ApkSigningHelper: generating new RSA 2048 key pair")
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            kpg.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN
                )
                    .setKeySize(2048)
                    .setDigests(
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA512
                    )
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
            )
            kpg.generateKeyPair()
        }

        val privateKey = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return Pair(privateKey, cert)
    }
}

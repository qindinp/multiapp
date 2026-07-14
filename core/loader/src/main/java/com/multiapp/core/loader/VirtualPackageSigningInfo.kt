package com.multiapp.core.loader

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.security.MessageDigest

data class VirtualPackageSigningInfo(
    val legacySignatures: Array<Signature>,
    val signingInfo: SigningInfo?,
    val signerSha256Digests: List<String>
) {
    fun matches(other: VirtualPackageSigningInfo?): Boolean {
        if (other == null) return false
        val ownDigests = normalizedDigests()
        val otherDigests = other.normalizedDigests()
        return ownDigests.isNotEmpty() && ownDigests == otherDigests
    }

    fun hasCertificate(certificate: ByteArray, type: Int): Boolean {
        val digest = when (type) {
            PackageManager.CERT_INPUT_RAW_X509 -> certificate.sha256()
            PackageManager.CERT_INPUT_SHA256 -> certificate.toHex()
            else -> return false
        }
        return normalizedDigests().any { it == digest }
    }

    private fun normalizedDigests(): List<String> = signerSha256Digests
        .filter { it.isNotBlank() }
        .map { it.lowercase() }
        .distinct()
        .sorted()
}

internal object VirtualPackageArchiveSigningResolver {
    fun resolve(packageManager: PackageManager?, snapshot: VirtualPackageSnapshot): VirtualPackageSigningInfo? {
        val pm = packageManager ?: return null
        val packageInfo = runCatching {
            val flags = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(snapshot.sourceDir, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(snapshot.sourceDir, flags)
            }
        }.getOrNull() ?: return null

        val resolved = fromPackageInfo(packageInfo) ?: return null
        val signingInfo = resolved.signingInfo
        val actualDigests = resolved.signerSha256Digests
        if (!snapshot.matchesSignerIdentity(actualDigests, signingInfo?.hasMultipleSigners() == true)) return null
        return resolved
    }

    @Suppress("DEPRECATION")
    fun fromPackageInfo(packageInfo: PackageInfo): VirtualPackageSigningInfo? {
        val signingInfo = packageInfo.signingInfo?.let(::SigningInfo)
        val identitySignatures = signingInfo?.identitySignatures()
            ?: packageInfo.signatures?.toList().orEmpty()
        if (identitySignatures.isEmpty()) return null
        return VirtualPackageSigningInfo(
            legacySignatures = packageInfo.signatures?.copyOf()
                ?: identitySignatures.take(1).toTypedArray(),
            signingInfo = signingInfo,
            signerSha256Digests = identitySignatures.map { signature -> signature.sha256() }
        )
    }

    private fun SigningInfo.identitySignatures(): List<Signature> =
        if (hasMultipleSigners()) {
            apkContentsSigners?.toList().orEmpty()
        } else {
            signingCertificateHistory?.toList().orEmpty()
        }

    private fun VirtualPackageSnapshot.matchesSignerIdentity(
        actualDigests: List<String>,
        actualMultipleSigners: Boolean
    ): Boolean {
        if (signerSha256Digests.isNotEmpty()) {
            if (hasMultipleSigners != actualMultipleSigners) return false
            return if (actualMultipleSigners) {
                signerSha256Digests.sorted() == actualDigests.sorted()
            } else {
                signerSha256Digests == actualDigests
            }
        }
        val expectedCurrent = originCertSha256?.takeIf { it.isNotBlank() } ?: return false
        return actualDigests.lastOrNull()?.equals(expectedCurrent, ignoreCase = true) == true
    }

    private fun Signature.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

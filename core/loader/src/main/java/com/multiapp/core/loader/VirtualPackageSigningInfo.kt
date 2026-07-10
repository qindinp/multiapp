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
)

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

        val signingInfo = packageInfo.signingInfo?.let(::SigningInfo)
        val identitySignatures = signingInfo?.identitySignatures()
            ?: packageInfo.signatures?.toList().orEmpty()
        if (identitySignatures.isEmpty()) return null

        val actualDigests = identitySignatures.map { signature -> signature.sha256() }
        if (!snapshot.matchesSignerIdentity(actualDigests, signingInfo?.hasMultipleSigners() == true)) return null

        @Suppress("DEPRECATION")
        val legacySignatures = packageInfo.signatures?.copyOf()
            ?: identitySignatures.take(1).toTypedArray()
        return VirtualPackageSigningInfo(
            legacySignatures = legacySignatures,
            signingInfo = signingInfo,
            signerSha256Digests = actualDigests
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
        val expectedCurrent = originCertSha256?.takeIf { it.isNotBlank() } ?: return true
        return actualDigests.lastOrNull()?.equals(expectedCurrent, ignoreCase = true) == true
    }

    private fun Signature.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

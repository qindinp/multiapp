package com.multiapp.core.loader

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VirtualPackageSigningInfoTest {

    @Test
    fun `archive signing resolver accepts signer identity owned by snapshot`() {
        val bytes = byteArrayOf(1, 3, 5, 7)
        val signature = mockk<Signature> {
            every { toByteArray() } returns bytes
        }
        val digest = bytes.sha256()
        val packageManager = archivePackageManager(arrayOf(signature))

        val resolved = VirtualPackageArchiveSigningResolver.resolve(
            packageManager,
            snapshot(signerSha256Digests = listOf(digest), originCertSha256 = digest)
        )

        val signingInfo = assertNotNull(resolved)
        assertEquals(listOf(digest), signingInfo.signerSha256Digests)
        assertEquals(signature, signingInfo.legacySignatures.single())
    }

    @Test
    fun `archive signing resolver rejects artifact with mismatched signer identity`() {
        val signature = mockk<Signature> {
            every { toByteArray() } returns byteArrayOf(2, 4, 6, 8)
        }
        val packageManager = archivePackageManager(arrayOf(signature))

        val resolved = VirtualPackageArchiveSigningResolver.resolve(
            packageManager,
            snapshot(signerSha256Digests = listOf("different-signer"), originCertSha256 = "different-signer")
        )

        assertNull(resolved)
    }

    @Suppress("DEPRECATION")
    private fun archivePackageManager(signatures: Array<Signature>): PackageManager {
        val packageInfo = PackageInfo().apply { this.signatures = signatures }
        return mockk<PackageManager>().also { packageManager ->
            if (Build.VERSION.SDK_INT >= 33) {
                every {
                    packageManager.getPackageArchiveInfo(
                        any(),
                        any<PackageManager.PackageInfoFlags>()
                    )
                } returns packageInfo
            } else {
                every { packageManager.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
            }
        }
    }

    private fun snapshot(
        signerSha256Digests: List<String>,
        originCertSha256: String
    ) = VirtualPackageSnapshot(
        instanceId = "instance-signing",
        originPackageName = "com.test.signing",
        virtualPackageName = "com.multiapp.virtual.signing",
        applicationLabel = "Signing",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/app/com.test.signing/base.apk",
        dataDir = "/data/user/0/com.multiapp.app/instances/signing",
        originCertSha256 = originCertSha256,
        signerSha256Digests = signerSha256Digests
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}

package com.multiapp.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ApkPackageIdentityTest {
    @Test
    fun `matching archive and manifest identity is accepted`() {
        assertEquals(
            "com.example.app",
            requireMatchingApkPackageIdentity(
                expectedPackageName = "com.example.app",
                archivePackageName = "com.example.app",
                manifestPackageName = "com.example.app"
            )
        )
    }

    @Test
    fun `archive identity mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireMatchingApkPackageIdentity(
                expectedPackageName = "com.example.app",
                archivePackageName = "com.other.app",
                manifestPackageName = "com.other.app"
            )
        }
    }

    @Test
    fun `manifest identity mismatch is rejected even when archive matches`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireMatchingApkPackageIdentity(
                expectedPackageName = "com.example.app",
                archivePackageName = "com.example.app",
                manifestPackageName = "com.other.app"
            )
        }
    }
}

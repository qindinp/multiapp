package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManifestVirtualPackageResolverTest {

    @Test
    fun `normalizeComponentName resolves relative component names`() {
        assertEquals(
            "com.example.app.MainActivity",
            ManifestVirtualPackageResolver.normalizeComponentName("com.example.app", ".MainActivity")
        )
        assertEquals(
            "com.example.app.MainActivity",
            ManifestVirtualPackageResolver.normalizeComponentName("com.example.app", "MainActivity")
        )
        assertEquals(
            "com.other.MainActivity",
            ManifestVirtualPackageResolver.normalizeComponentName("com.example.app", "com.other.MainActivity")
        )
    }

    @Test
    fun `normalizeComponentName returns null for missing names`() {
        assertNull(ManifestVirtualPackageResolver.normalizeComponentName("com.example.app", null))
        assertNull(ManifestVirtualPackageResolver.normalizeComponentName("com.example.app", ""))
        assertNull(ManifestVirtualPackageResolver.normalizeComponentName("com.example.app", "   "))
    }
}
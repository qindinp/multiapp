package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.manifest.ManifestParser
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `resolver preserves service and receiver intent filters`(@TempDir tempDir: File) {
        val apk = File(tempDir, "guest.apk").apply { writeText("stub") }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val parser = mockk<ManifestParser>()
        every { parser.parse(apk) } returns ManifestParser.ParsedManifest(
            packageName = "com.example.app",
            applicationClass = null,
            activities = emptyList(),
            services = listOf(
                ManifestParser.ComponentInfo(
                    name = ".SyncService",
                    intentFilters = listOf(
                        ManifestParser.IntentFilterInfo(
                            actions = listOf("com.example.SYNC"),
                            categories = listOf("android.intent.category.DEFAULT"),
                            dataSchemes = listOf("content")
                        )
                    )
                )
            ),
            receivers = listOf(
                ManifestParser.ComponentInfo(
                    name = ".BootReceiver",
                    intentFilters = listOf(
                        ManifestParser.IntentFilterInfo(actions = listOf("com.example.BOOT"))
                    )
                )
            ),
            providers = emptyList(),
            permissions = emptyList()
        )
        val resolver = ManifestVirtualPackageResolver(context = context, parser = parser)

        val resolved = resolver.resolve(apk.absolutePath)!!

        val serviceFilter = resolved.services.single().resolvedIntentFilters.single()
        assertEquals("com.example.app.SyncService", resolved.services.single().name)
        assertEquals(listOf("com.example.SYNC"), serviceFilter.actions)
        assertEquals(listOf("android.intent.category.DEFAULT"), serviceFilter.categories)
        assertEquals(listOf("content"), serviceFilter.dataSchemes)
        val receiverFilter = resolved.receivers.single().resolvedIntentFilters.single()
        assertEquals("com.example.app.BootReceiver", resolved.receivers.single().name)
        assertEquals(listOf("com.example.BOOT"), receiverFilter.actions)
    }
}

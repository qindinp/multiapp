package com.multiapp.core.model.virtual

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JVM unit tests for [DefaultVirtualActivityController].
 *
 * Tests launcher activity resolution logic. Launch tests require Android
 * framework (Robolectric/instrumented) and are not covered here.
 */
class DefaultVirtualActivityControllerTest {

    private val controller = DefaultVirtualActivityController()

    // ── resolveLauncherActivity ─────────────────────────────────────────

    @Test
    fun `resolveLauncherActivity returns launcherActivityName when present`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = "com.example.app.MainActivity"
        )

        assertEquals("com.example.app.MainActivity", controller.resolveLauncherActivity(pkg))
    }

    @Test
    fun `resolveLauncherActivity returns null when launcherActivityName is null and no activities`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = null,
            activities = emptyList()
        )

        assertNull(controller.resolveLauncherActivity(pkg))
    }

    @Test
    fun `resolveLauncherActivity finds MAIN LAUNCHER from activities when launcherActivityName is null`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = null,
            activities = listOf(
                ResolvedComponent(
                    name = "com.example.app.SplashActivity",
                    exported = true,
                    intentFilters = listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER")
                ),
                ResolvedComponent(
                    name = "com.example.app.OtherActivity",
                    exported = false
                )
            )
        )

        assertEquals("com.example.app.SplashActivity", controller.resolveLauncherActivity(pkg))
    }

    @Test
    fun `resolveLauncherActivity returns first activity when no MAIN LAUNCHER found`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = null,
            activities = listOf(
                ResolvedComponent(
                    name = "com.example.app.DeepLinkActivity",
                    exported = true,
                    intentFilters = listOf("android.intent.action.VIEW")
                ),
                ResolvedComponent(
                    name = "com.example.app.OtherActivity",
                    exported = false
                )
            )
        )

        assertEquals("com.example.app.DeepLinkActivity", controller.resolveLauncherActivity(pkg))
    }

    @Test
    fun `resolveLauncherActivity prefers MAIN LAUNCHER over first activity`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = null,
            activities = listOf(
                ResolvedComponent(
                    name = "com.example.app.DeepLinkActivity",
                    exported = true,
                    intentFilters = listOf("android.intent.action.VIEW")
                ),
                ResolvedComponent(
                    name = "com.example.app.MainActivity",
                    exported = true,
                    intentFilters = listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER")
                )
            )
        )

        assertEquals("com.example.app.MainActivity", controller.resolveLauncherActivity(pkg))
    }

    @Test
    fun `resolveLauncherActivity prefers launcherActivityName over MAIN LAUNCHER`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = "com.example.app.DeclaredLauncher",
            activities = listOf(
                ResolvedComponent(
                    name = "com.example.app.MainActivity",
                    exported = true,
                    intentFilters = listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER")
                )
            )
        )

        assertEquals("com.example.app.DeclaredLauncher", controller.resolveLauncherActivity(pkg))
    }
}

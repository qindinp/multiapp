package com.multiapp.core.model.virtual

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualActivityControllerTest {

    @Test
    fun `GuestActivityLaunchResult success result`() {
        val result = GuestActivityLaunchResult(
            success = true,
            activityClassName = "com.example.MainActivity"
        )

        assertTrue(result.success)
        assertEquals("com.example.MainActivity", result.activityClassName)
        assertNull(result.errorMessage)
    }

    @Test
    fun `GuestActivityLaunchResult failure result`() {
        val result = GuestActivityLaunchResult(
            success = false,
            activityClassName = null,
            errorMessage = "Activity not found"
        )

        assertFalse(result.success)
        assertNull(result.activityClassName)
        assertEquals("Activity not found", result.errorMessage)
    }

    @Test
    fun `GuestActivityLaunchResult default error message is null`() {
        val result = GuestActivityLaunchResult(
            success = true,
            activityClassName = "com.example.Activity"
        )

        assertNull(result.errorMessage)
    }

    @Test
    fun `GuestActivityLaunchResult immutable copy creates new instance`() {
        val original = GuestActivityLaunchResult(
            success = true,
            activityClassName = "com.example.Activity"
        )
        val copied = original.copy(success = false, errorMessage = "failed")

        assertTrue(original.success)
        assertNull(original.errorMessage)
        assertFalse(copied.success)
        assertEquals("failed", copied.errorMessage)
    }

    @Test
    fun `GuestActivityLaunchResult equality works correctly`() {
        val result1 = GuestActivityLaunchResult(
            success = true,
            activityClassName = "com.example.Activity",
            errorMessage = null
        )
        val result2 = GuestActivityLaunchResult(
            success = true,
            activityClassName = "com.example.Activity",
            errorMessage = null
        )

        assertEquals(result1, result2)
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `GuestActivityLaunchResult destructuring works`() {
        val result = GuestActivityLaunchResult(
            success = true,
            activityClassName = "com.example.Activity",
            errorMessage = null
        )

        val (success, activityClassName, errorMessage) = result

        assertTrue(success)
        assertEquals("com.example.Activity", activityClassName)
        assertNull(errorMessage)
    }

    @Test
    fun `VirtualActivityController interface exists and can be implemented`() {
        val controller = object : VirtualActivityController {
            override fun resolveLauncherActivity(resolvedPackage: ResolvedPackage): String? {
                return resolvedPackage.launcherActivityName
            }

            override fun launchGuestActivity(
                hostActivity: android.app.Activity,
                activityClassName: String,
                classLoader: ClassLoader,
                config: VirtualContextConfig
            ): GuestActivityLaunchResult {
                return GuestActivityLaunchResult(
                    success = true,
                    activityClassName = activityClassName
                )
            }
        }

        assertNotNull(controller)
    }

    @Test
    fun `resolveLauncherActivity returns null when no launcher activity`() {
        val controller = object : VirtualActivityController {
            override fun resolveLauncherActivity(resolvedPackage: ResolvedPackage): String? {
                return resolvedPackage.launcherActivityName
            }

            override fun launchGuestActivity(
                hostActivity: android.app.Activity,
                activityClassName: String,
                classLoader: ClassLoader,
                config: VirtualContextConfig
            ): GuestActivityLaunchResult {
                return GuestActivityLaunchResult(success = false, activityClassName = null)
            }
        }

        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = null
        )

        assertNull(controller.resolveLauncherActivity(pkg))
    }

    @Test
    fun `resolveLauncherActivity returns launcher when present`() {
        val controller = object : VirtualActivityController {
            override fun resolveLauncherActivity(resolvedPackage: ResolvedPackage): String? {
                return resolvedPackage.launcherActivityName
            }

            override fun launchGuestActivity(
                hostActivity: android.app.Activity,
                activityClassName: String,
                classLoader: ClassLoader,
                config: VirtualContextConfig
            ): GuestActivityLaunchResult {
                return GuestActivityLaunchResult(success = true, activityClassName = activityClassName)
            }
        }

        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            launcherActivityName = "com.example.MainActivity"
        )

        assertEquals("com.example.MainActivity", controller.resolveLauncherActivity(pkg))
    }
}

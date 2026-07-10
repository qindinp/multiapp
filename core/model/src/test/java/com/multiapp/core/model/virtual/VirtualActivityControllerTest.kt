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

            override fun planGuestActivityLaunch(request: GuestActivityLaunchRequest): GuestActivityLaunchResult {
                return GuestActivityLaunchResult(
                    success = true,
                    activityClassName = request.activityClassName
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

            override fun planGuestActivityLaunch(request: GuestActivityLaunchRequest): GuestActivityLaunchResult {
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

            override fun planGuestActivityLaunch(request: GuestActivityLaunchRequest): GuestActivityLaunchResult {
                return GuestActivityLaunchResult(success = true, activityClassName = request.activityClassName)
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

    @Test
    fun `GuestActivityLaunchRequest carries pure model launch data`() {
        val config = VirtualContextConfig(
            instanceId = "instance-1",
            originPackageName = "com.example",
            virtualPackageName = "com.multiapp.instance.example",
            dataDir = "/data/user/0/com.multiapp.app/files/instances/instance-1",
            sourceDir = "/data/app/com.example/base.apk",
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader(),
            processSlot = ":v0"
        )

        val request = GuestActivityLaunchRequest(
            activityClassName = "com.example.MainActivity",
            classLoader = config.classLoader,
            config = config,
            launchFlags = 0x10000000,
            taskAffinity = "com.example"
        )

        assertEquals("com.example.MainActivity", request.activityClassName)
        assertEquals(":v0", request.config.processSlot)
        assertEquals(0x10000000, request.launchFlags)
        assertEquals("com.example", request.taskAffinity)
    }
}

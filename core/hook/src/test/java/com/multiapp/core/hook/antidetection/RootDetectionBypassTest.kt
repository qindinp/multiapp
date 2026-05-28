package com.multiapp.core.hook.antidetection

import com.multiapp.core.hook.NativeHookBridge
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootDetectionBypassTest {

    private lateinit var nativeHookBridge: NativeHookBridge
    private lateinit var bypass: RootDetectionBypass

    @BeforeEach
    fun setUp() {
        nativeHookBridge = mockk(relaxed = true)
        bypass = RootDetectionBypass(nativeHookBridge)
    }

    // ===== ROOT_PACKAGES tests =====

    @Test
    fun `ROOT_PACKAGES contains Magisk package names`() {
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.topjohnwu.magisk"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.topjohnwu.magisk.lite"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("io.github.vvb2060.magisk"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("io.github.huskydg.magisk"))
    }

    @Test
    fun `ROOT_PACKAGES contains SuperSU and Superuser packages`() {
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("eu.chainfire.supersu"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.koushikdutta.superuser"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.noshufou.android.su"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.thirdparty.superuser"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.yellowes.su"))
    }

    @Test
    fun `ROOT_PACKAGES contains Xposed and KernelSU packages`() {
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("de.robv.android.xposed.installer"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("me.weishu.kernelsu"))
    }

    @Test
    fun `ROOT_PACKAGES contains one-click root packages`() {
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.kingroot.kinguser"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.kingo.root"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.smedialink.oneclickroot"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.zhiqupk.root.global"))
        assertTrue(RootDetectionBypass.ROOT_PACKAGES.contains("com.alephzain.framaroot"))
    }

    @Test
    fun `ROOT_PACKAGES does not contain normal app packages`() {
        assertFalse(RootDetectionBypass.ROOT_PACKAGES.contains("com.google.android.gms"))
        assertFalse(RootDetectionBypass.ROOT_PACKAGES.contains("com.android.chrome"))
        assertFalse(RootDetectionBypass.ROOT_PACKAGES.contains(""))
    }

    @Test
    fun `ROOT_PACKAGES has expected size`() {
        assertEquals(16, RootDetectionBypass.ROOT_PACKAGES.size)
    }

    // ===== ROOT_BINARIES tests =====

    @Test
    fun `ROOT_BINARIES contains su binary paths`() {
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/system/xbin/su"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/system/bin/su"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/sbin/su"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/local/xbin/su"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/local/bin/su"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/su/bin/su"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/local/su"))
    }

    @Test
    fun `ROOT_BINARIES contains Magisk-related paths`() {
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/sbin/magisk"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/sbin/.magisk"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/adb/magisk"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/adb/modules"))
    }

    @Test
    fun `ROOT_BINARIES contains KernelSU paths`() {
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/adb/ksu"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/data/adb/ksud"))
    }

    @Test
    fun `ROOT_BINARIES contains Superuser APK and daemon paths`() {
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/system/app/Superuser.apk"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/system/etc/init.d/99SuperSUDaemon"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/dev/com.koushikdutta.superuser.daemon/"))
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/system/xbin/daemonsu"))
    }

    @Test
    fun `ROOT_BINARIES contains busybox path`() {
        assertTrue(RootDetectionBypass.ROOT_BINARIES.contains("/system/xbin/busybox"))
    }

    @Test
    fun `ROOT_BINARIES does not contain normal system paths`() {
        assertFalse(RootDetectionBypass.ROOT_BINARIES.contains("/system/bin/sh"))
        assertFalse(RootDetectionBypass.ROOT_BINARIES.contains("/system/lib/libc.so"))
    }

    @Test
    fun `ROOT_BINARIES has expected size`() {
        assertEquals(20, RootDetectionBypass.ROOT_BINARIES.size)
    }

    // ===== hookRootChecks tests =====

    @Test
    fun `hookRootChecks hides all root binary paths via NativeHookBridge`() {
        bypass.hookRootChecks()

        for (path in RootDetectionBypass.ROOT_BINARIES) {
            verify { nativeHookBridge.hidePath(path) }
        }
    }

    @Test
    fun `hookRootChecks hides Magisk-specific paths`() {
        bypass.hookRootChecks()

        verify {
            nativeHookBridge.hidePath("/sbin/.magisk")
            nativeHookBridge.hidePath("/data/adb/magisk")
            nativeHookBridge.hidePath("/data/adb/modules")
            nativeHookBridge.hidePath("/cache/.disable_magisk")
        }
    }

    @Test
    fun `hookRootChecks spoofs root-related system properties`() {
        bypass.hookRootChecks()

        verify {
            nativeHookBridge.spoofSystemProperty("ro.debuggable", "0")
            nativeHookBridge.spoofSystemProperty("ro.secure", "1")
            nativeHookBridge.spoofSystemProperty("ro.build.selinux", "1")
            nativeHookBridge.spoofSystemProperty("ro.build.tags", "release-keys")
            nativeHookBridge.spoofSystemProperty("ro.build.type", "user")
            nativeHookBridge.spoofSystemProperty("service.bootanim.exit", "1")
        }
    }

    @Test
    fun `hookRootChecks hides which binary`() {
        bypass.hookRootChecks()

        verify { nativeHookBridge.hidePath("/system/xbin/which") }
    }

    @Test
    fun `hookRootChecks sets fake mount info content`() {
        bypass.hookRootChecks()

        verify { nativeHookBridge.setFakeFileContent(eq("/proc/self/mounts"), ofType(String::class)) }
    }

    @Test
    fun `hookRootChecks does not crash on multiple invocations`() {
        bypass.hookRootChecks()
        bypass.hookRootChecks()

        verify(exactly = 2) { nativeHookBridge.hidePath("/system/xbin/su") }
    }

    // ===== isRootPackage tests =====

    @Test
    fun `isRootPackage returns true for Magisk`() {
        assertTrue(bypass.isRootPackage("com.topjohnwu.magisk"))
    }

    @Test
    fun `isRootPackage returns true for KernelSU`() {
        assertTrue(bypass.isRootPackage("me.weishu.kernelsu"))
    }

    @Test
    fun `isRootPackage returns false for normal app`() {
        assertFalse(bypass.isRootPackage("com.example.normalapp"))
    }

    @Test
    fun `isRootPackage returns false for empty string`() {
        assertFalse(bypass.isRootPackage(""))
    }

    // ===== filterRootPackages tests =====

    @Test
    fun `filterRootPackages removes root packages from list`() {
        val packages = listOf(
            "com.example.app",
            "com.topjohnwu.magisk",
            "com.android.chrome",
            "eu.chainfire.supersu"
        )

        val filtered = bypass.filterRootPackages(packages)

        assertEquals(listOf("com.example.app", "com.android.chrome"), filtered)
    }

    @Test
    fun `filterRootPackages returns empty list when all are root packages`() {
        val packages = listOf("com.topjohnwu.magisk", "me.weishu.kernelsu")

        val filtered = bypass.filterRootPackages(packages)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterRootPackages returns all when none are root packages`() {
        val packages = listOf("com.example.app", "com.android.chrome")

        val filtered = bypass.filterRootPackages(packages)

        assertEquals(packages, filtered)
    }

    @Test
    fun `filterRootPackages handles empty input list`() {
        val filtered = bypass.filterRootPackages(emptyList())

        assertTrue(filtered.isEmpty())
    }
}

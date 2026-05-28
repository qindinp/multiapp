package com.multiapp.core.hook.antidetection

import com.multiapp.core.hook.NativeHookBridge
import timber.log.Timber

/**
 * Root detection bypass -- hides su, Magisk, root packages,
 * and spoofs root-related system properties.
 */
class RootDetectionBypass(
    private val nativeHookBridge: NativeHookBridge
) {
    companion object {
        private const val TAG = "AntiDetect"

        // Root-related package names to hide
        internal val ROOT_PACKAGES = setOf(
            "com.topjohnwu.magisk",
            "com.topjohnwu.magisk.lite",
            "de.robv.android.xposed.installer",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
            "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk",
            "me.weishu.kernelsu"
        )

        // Root-related binary paths
        internal val ROOT_BINARIES = setOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
            "/system/etc/init.d/99SuperSUDaemon",
            "/dev/com.koushikdutta.superuser.daemon/",
            "/system/xbin/daemonsu",
            "/system/xbin/busybox",
            "/sbin/magisk",
            "/sbin/.magisk",
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/adb/ksu",
            "/data/adb/ksud"
        )
    }

    /**
     * Hide all root-related indicators:
     * - su binary paths -> hidden via NativeHookBridge
     * - Root packages -> filtered from PackageManager results
     * - Root-related system properties -> spoofed
     */
    fun hookRootChecks() {
        Timber.tag(TAG).d("Installing root detection bypass...")

        // 1. Hide root binary paths
        for (path in ROOT_BINARIES) {
            nativeHookBridge.hidePath(path)
        }

        // 2. Hide Magisk-specific paths
        nativeHookBridge.hidePath("/sbin/.magisk")
        nativeHookBridge.hidePath("/data/adb/magisk")
        nativeHookBridge.hidePath("/data/adb/modules")
        nativeHookBridge.hidePath("/cache/.disable_magisk")

        // 3. Spoof root-related system properties
        nativeHookBridge.spoofSystemProperty("ro.debuggable", "0")
        nativeHookBridge.spoofSystemProperty("ro.secure", "1")
        nativeHookBridge.spoofSystemProperty("ro.build.selinux", "1")
        nativeHookBridge.spoofSystemProperty("ro.build.tags", "release-keys")
        nativeHookBridge.spoofSystemProperty("ro.build.type", "user")
        nativeHookBridge.spoofSystemProperty("service.bootanim.exit", "1")

        // 4. Hide which binary
        nativeHookBridge.hidePath("/system/xbin/which")

        // 5. Hide /proc entries that leak root status
        nativeHookBridge.setFakeFileContent("/proc/self/mounts", buildCleanMountInfo())

        Timber.tag(TAG).d("Root detection bypass installed: ${ROOT_BINARIES.size} paths hidden")
    }

    /** Check if a package is a known root/hook package. */
    fun isRootPackage(packageName: String): Boolean = ROOT_PACKAGES.contains(packageName)

    /** Filter a list of package names to remove root/hook packages. */
    fun filterRootPackages(packages: List<String>): List<String> {
        return packages.filter { !ROOT_PACKAGES.contains(it) }
    }

    private fun buildCleanMountInfo(): String {
        return """
            |/dev/block/bootdevice/by-name/system /system ext4 ro,seclabel,relatime,discard 0 0
            |/dev/block/bootdevice/by-name/vendor /vendor ext4 ro,seclabel,relatime,discard 0 0
            |/dev/block/bootdevice/by-name/userdata /data f2fs rw,seclabel,nosuid,nodev,discard 0 0
            |/dev/block/bootdevice/by-name/cache /cache ext4 rw,seclabel,nosuid,nodev,discard 0 0
            |tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
            |devpts /dev/pts devpts rw,seclabel,relatime,mode=600 0 0
            |proc /proc proc rw,relatime 0 0
            |sysfs /sys sysfs rw,seclabel,relatime 0 0
            |selinuxfs /sys/fs/selinux selinuxfs rw,relatime 0 0
            |none /acct cgroup rw,relatime 0 0
            |tmpfs /mnt tmpfs rw,seclabel,relatime,mode=755,gid=1000 0 0
        """.trimMargin()
    }
}

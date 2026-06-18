package com.multiapp.core.model

import android.graphics.drawable.Drawable

/**
 * Represents a virtual app installed inside MultiApp.
 * One VirtualApp = one guest app instance running in the sandbox.
 *
 * Fields sourced from Android 16 frameworks-base:
 * - ApkLite.java: minSdkVersion, targetSdkVersion, isMultiArch, use32bitAbi,
 *   extractNativeLibs, useEmbeddedDex, isDebuggable, installLocation, pageSizeCompat
 * - PackageLite.java: splitApkPaths, splitNames, isolatedSplits
 * - PackageInfo: applicationClassName, requestedPermissions
 */
data class VirtualApp(
    /** Package name of the guest app, e.g., "com.whatsapp" */
    val packageName: String,
    /** Display name, e.g., "WhatsApp" */
    val appName: String,
    /** Version string from manifest */
    val versionName: String = "",
    /** Version code from manifest */
    val versionCode: Long = 0,
    /** App icon loaded from APK (transient, not serialized) */
    @Transient val icon: Drawable? = null,
    /** Path to the copied APK in virtual storage */
    val apkPath: String,
    /** Unique instance ID: "{packageName}_{timestamp}" */
    val instanceId: String,
    /** Assigned process slot (:p0, :p1, :p2...) */
    val processSlot: Int = -1,
    /** Whether the app is currently running */
    val isRunning: Boolean = false,
    /** Epoch millis when app was installed */
    val installedAt: Long = System.currentTimeMillis(),
    /** Epoch millis when app was last launched */
    val lastLaunchedAt: Long = 0,
    /** Whether app has arm64-v8a native libs */
    val is64Bit: Boolean = true,
    /** Device spoofing profile (null = use host device identity) */
    val deviceProfile: DeviceProfile? = null,
    /** Per-permission overrides: permission -> granted */
    val permissionOverrides: Map<String, Boolean> = emptyMap(),
    /** Network access policy for this app */
    val networkPolicy: NetworkPolicy = NetworkPolicy.FULL_ACCESS,
    /** Whether this app's storage is encrypted */
    val storageEncrypted: Boolean = false,
    /** Main activity class name */
    val mainActivity: String? = null,
    /** Whether this app is installed as a system app on the host device */
    val isSystemApp: Boolean = false,
    /** Product route used when building a clone */
    val cloneProfile: CloneProfile = CloneProfile.NORMAL,
    /** Human-readable risk label for the app picker */
    val riskLabel: String = "普通",
    /** All declared activities */
    val activities: List<String> = emptyList(),
    /** All declared services */
    val services: List<String> = emptyList(),
    /** All declared content providers */
    val providers: List<String> = emptyList(),
    /** All declared broadcast receivers */
    val receivers: List<String> = emptyList(),

    // === Android 16 framework fields (from ApkLite / PackageLite) ===

    /** Minimum SDK version required by this app */
    val minSdkVersion: Int = 1,
    /** Target SDK version declared by this app */
    val targetSdkVersion: Int = 35,
    /** Whether the app supports multiple architectures */
    val isMultiArch: Boolean = false,
    /** Whether to force 32-bit mode on 64-bit devices */
    val use32bitAbi: Boolean = false,
    /** Whether to extract native libraries from APK (vs direct APK access) */
    val extractNativeLibs: Boolean = true,
    /** Whether DEX files are stored uncompressed and page-aligned in APK */
    val useEmbeddedDex: Boolean = false,
    /** Whether the app is debuggable */
    val isDebuggable: Boolean = false,
    /** Install location preference: auto=0, internalOnly=1, preferExternal=2 */
    val installLocation: Int = 0,
    /** Page size compatibility mode (Android 15+) */
    val pageSizeCompat: Int = 0,

    // === Split APK support (from PackageLite) ===

    /** Paths to split APK files (if any) */
    val splitApkPaths: List<String> = emptyList(),
    /** Names of each split */
    val splitNames: List<String> = emptyList(),
    /** Whether this app uses split APK architecture */
    val hasSplitApks: Boolean = false,
    /** Whether each split has its own ClassLoader */
    val isolatedSplits: Boolean = false,

    // === Application metadata ===

    /** Fully qualified Application class name (null = android.app.Application) */
    val applicationClassName: String? = null,
    /** Permissions requested by this app */
    val requestedPermissions: List<String> = emptyList(),
    /** Native ABI architectures found in the APK */
    val nativeAbis: List<String> = emptyList(),
    /** Meta-data key-value pairs from manifest */
    val metaData: Map<String, String> = emptyMap(),

    /** Activity-alias mappings: alias class name -> real target activity class name */
    val activityAliases: Map<String, String> = emptyMap(),

    // === Sandbox paths (populated during installation) ===

    /** Isolated data directory for this instance */
    val dataDir: String = "",
    /** Extracted native library directory */
    val libDir: String = "",
    /** Full library search path for ClassLoader and linker (dir:apk!/lib/abi/:...) */
    val librarySearchPath: String = "",
    /** Primary ABI selected during native lib extraction */
    val primaryAbi: String = "",
    /** Cache directory for this instance */
    val cacheDir: String = ""
)

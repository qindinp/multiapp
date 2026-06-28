package com.multiapp.core.model.virtual

/**
 * Configuration for creating a virtual (guest) Context.
 *
 * Each guest instance gets its own identity: a unique [instanceId], a spoofed
 * [virtualPackageName], and isolated storage rooted at [dataDir].
 */
data class VirtualContextConfig(
    /** Unique identifier for this guest instance */
    val instanceId: String,
    /** Original package name of the APK being wrapped */
    val originPackageName: String,
    /** Virtual package name presented to the guest app */
    val virtualPackageName: String,
    /** Isolated data directory for this instance */
    val dataDir: String,
    /** Path to the source APK */
    val sourceDir: String,
    /** Directory containing extracted native libraries (null if none) */
    val nativeLibraryDir: String?,
    /** ClassLoader configured with the guest APK's DEX files */
    val classLoader: ClassLoader,
    /** Human-readable label resolved from the origin APK manifest, if available. */
    val applicationLabel: String? = null,
    /** Runtime package snapshot used by virtual PMS/AMS query layers. */
    val packageSnapshot: VirtualPackageSnapshot? = null
)

/**
 * Factory for creating virtual Context objects that wrap the host Context
 * but report the guest app's identity (package name, ApplicationInfo, etc.).
 */
interface VirtualContextFactory {
    /**
     * Create a guest Context that wraps [hostContext] and overrides identity
     * fields according to [config].
     *
     * The returned Context should report:
     * - `getPackageName()` -> [VirtualContextConfig.virtualPackageName]
     * - `getApplicationInfo()` -> guest's ApplicationInfo with spoofed paths
     * - `getClassLoader()` -> [VirtualContextConfig.classLoader]
     * - `getFilesDir()` / `getCacheDir()` -> under [VirtualContextConfig.dataDir]
     *
     * @param hostContext the real Android Context to wrap
     * @param config guest instance configuration
     * @return a Context with overridden identity
     */
    fun createGuestContext(
        hostContext: android.content.Context,
        config: VirtualContextConfig
    ): android.content.Context
}

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
    val packageSnapshot: VirtualPackageSnapshot? = null,
    /** Split APK code paths available to this guest context. */
    val splitSourceDirs: List<String> = packageSnapshot?.splitSourceDirs.orEmpty(),
    /** Split APK resource paths available to this guest context. */
    val splitPublicSourceDirs: List<String> = packageSnapshot?.splitPublicSourceDirs.orEmpty().ifEmpty {
        splitSourceDirs
    },
    /** Android split names corresponding to split APK paths. */
    val splitNames: List<String> = packageSnapshot?.splitNames.orEmpty(),
    /** Whether the package requests isolated split loading. */
    val isolatedSplits: Boolean = packageSnapshot?.isolatedSplits ?: false
) {
    init {
        require(splitSourceDirs.none { it.isBlank() }) { "splitSourceDirs must not contain blank entries" }
        require(splitPublicSourceDirs.none { it.isBlank() }) {
            "splitPublicSourceDirs must not contain blank entries"
        }
        require(splitNames.none { it.isBlank() }) { "splitNames must not contain blank entries" }
        require(splitPublicSourceDirs.isEmpty() || splitPublicSourceDirs.size == splitSourceDirs.size) {
            "splitPublicSourceDirs size must match splitSourceDirs size"
        }
        require(splitNames.isEmpty() || splitNames.size == splitSourceDirs.size) {
            "splitNames size must match splitSourceDirs size"
        }
    }
}

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

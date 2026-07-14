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
    /** Public/resource path to the base APK. */
    val publicSourceDir: String = packageSnapshot?.publicSourceDir ?: sourceDir,
    /** Split APK code paths available to this guest context. */
    val splitSourceDirs: List<String> = packageSnapshot?.splitSourceDirs.orEmpty(),
    /** Split APK resource paths available to this guest context. */
    val splitPublicSourceDirs: List<String> = packageSnapshot?.splitPublicSourceDirs.orEmpty().ifEmpty {
        splitSourceDirs
    },
    /** Android split names corresponding to split APK paths. */
    val splitNames: List<String> = packageSnapshot?.splitNames.orEmpty(),
    /** Whether the package requests isolated split loading. */
    val isolatedSplits: Boolean = packageSnapshot?.isolatedSplits ?: false,
    /** Real host Android process slot currently owning this virtual runtime. */
    val processSlot: String? = null,
    /** Manifest guest process hosted inside [processSlot]. Never contains the host :vN identity. */
    val effectiveGuestProcessName: String = resolveEffectiveGuestProcessName(
        originPackageName = originPackageName,
        declaredProcessName = packageSnapshot?.processName
    )
) {
    /** Code paths for class loading: base APK first, then split APKs. */
    val codeSourceDirs: List<String>
        get() = listOf(sourceDir) + splitSourceDirs

    /** Public/resource paths for Resources/AssetManager: base APK first, then split resource paths. */
    val publicResourceDirs: List<String>
        get() = listOf(publicSourceDir) + splitPublicSourceDirs

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
        require(effectiveGuestProcessName.isNotBlank()) {
            "effectiveGuestProcessName must not be blank"
        }
    }
}

/**
 * Factory for creating virtual Context objects that wrap the host Context
 * but report the guest app's identity (package name, ApplicationInfo, etc.).
 */
interface VirtualContextFactory {
    /**
     * Create an Android-free description of a guest Context identity.
     *
     * @param config guest instance configuration
     * @return guest identity and storage paths that Android-facing adapters can wrap
     */
    fun createGuestContext(config: VirtualContextConfig): VirtualContextSpec
}

data class VirtualContextSpec(
    val packageName: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val dataDir: String,
    val filesDir: String,
    val cacheDir: String,
    val sourceDir: String,
    val publicSourceDir: String,
    val splitSourceDirs: List<String>,
    val splitPublicSourceDirs: List<String>,
    val splitNames: List<String>,
    val nativeLibraryDir: String?,
    val processSlot: String?,
    val effectiveGuestProcessName: String
) {
    companion object {
        fun from(config: VirtualContextConfig): VirtualContextSpec =
            VirtualContextSpec(
                packageName = config.virtualPackageName,
                originPackageName = config.originPackageName,
                virtualPackageName = config.virtualPackageName,
                dataDir = config.dataDir,
                filesDir = "${config.dataDir}/files",
                cacheDir = "${config.dataDir}/cache",
                sourceDir = config.sourceDir,
                publicSourceDir = config.publicSourceDir,
                splitSourceDirs = config.splitSourceDirs,
                splitPublicSourceDirs = config.splitPublicSourceDirs,
                splitNames = config.splitNames,
                nativeLibraryDir = config.nativeLibraryDir,
                processSlot = config.processSlot,
                effectiveGuestProcessName = config.effectiveGuestProcessName
            )
    }
}

private fun resolveEffectiveGuestProcessName(
    originPackageName: String,
    declaredProcessName: String?
): String {
    val normalized = declaredProcessName?.trim()?.takeIf(String::isNotEmpty)
        ?: return originPackageName
    return if (normalized.startsWith(':')) originPackageName + normalized else normalized
}

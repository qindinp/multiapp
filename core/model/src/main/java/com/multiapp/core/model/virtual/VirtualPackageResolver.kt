package com.multiapp.core.model.virtual

/**
 * Resolved metadata for an installed APK.
 * Used by the container runtime to bootstrap a guest app without querying the system PackageManager.
 */
data class ResolvedPackage(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val targetSdk: Int,
    val minSdk: Int,
    val applicationClassName: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val themeId: Int = 0,
    val metaData: Map<String, String> = emptyMap(),
    val typedMetaData: Map<String, VirtualMetaDataValue> = emptyMap(),
    val launcherActivityName: String? = null,
    val activities: List<ResolvedComponent> = emptyList(),
    val services: List<ResolvedComponent> = emptyList(),
    val receivers: List<ResolvedComponent> = emptyList(),
    val providers: List<ResolvedComponent> = emptyList(),
    val permissions: List<String> = emptyList(),
    val nativeLibDir: String? = null,
    val applicationLabel: String? = null,
    val splitSourceDirs: List<String> = emptyList(),
    val splitPublicSourceDirs: List<String> = emptyList(),
    val splitNames: List<String> = emptyList(),
    val isolatedSplits: Boolean = false
)

/**
 * Structured intent-filter metadata used by the hosted container resolver.
 *
 * Only pure manifest facts are represented here. Android-facing parsers may
 * populate more fields over time, while engine-side VPMS matching remains
 * independent from framework IntentFilter objects.
 */
data class ResolvedIntentFilter(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList(),
    val dataMimeTypes: List<String> = emptyList(),
    val dataAuthorities: List<String> = emptyList(),
    val dataPaths: List<String> = emptyList(),
    val priority: Int = 0,
    val authorityEntries: List<ResolvedIntentAuthority> = emptyList(),
    val pathPatterns: List<ResolvedIntentPathPattern> = emptyList()
) {
    /** Structured authorities, falling back to legacy host-only values. */
    val resolvedAuthorities: List<ResolvedIntentAuthority>
        get() = authorityEntries.ifEmpty {
            dataAuthorities.map { host -> ResolvedIntentAuthority(host = host) }
        }

    /** Structured paths, treating legacy values as literal paths. */
    val resolvedPathPatterns: List<ResolvedIntentPathPattern>
        get() = pathPatterns.ifEmpty {
            dataPaths.map { path ->
                ResolvedIntentPathPattern(path, ResolvedIntentPathPatternType.LITERAL)
            }
        }

    /** Host-only compatibility view for consumers that still use dataAuthorities. */
    val legacyDataAuthorities: List<String>
        get() = dataAuthorities.ifEmpty { authorityEntries.map { entry -> entry.host } }

    /** Pattern-text compatibility view for consumers that still use dataPaths. */
    val legacyDataPaths: List<String>
        get() = dataPaths.ifEmpty { pathPatterns.map { pattern -> pattern.path } }
}

data class ResolvedIntentAuthority(
    val host: String,
    val port: Int? = null
) {
    init {
        require(host.isNotBlank()) { "intent-filter authority host must not be blank" }
        require(port == null || port >= 0) { "intent-filter authority port must not be negative" }
    }
}

enum class ResolvedIntentPathPatternType {
    LITERAL,
    PREFIX,
    SIMPLE_GLOB,
    ADVANCED_GLOB,
    SUFFIX
}

data class ResolvedIntentPathPattern(
    val path: String,
    val type: ResolvedIntentPathPatternType
) {
    init {
        require(path.isNotEmpty()) { "intent-filter path pattern must not be empty" }
    }
}

/**
 * A single component (activity, service, receiver, provider) declared in the manifest.
 */
data class ResolvedComponent(
    val name: String,
    val exported: Boolean = false,
    val intentFilters: List<String> = emptyList(),
    val resolvedIntentFilters: List<ResolvedIntentFilter> = emptyList(),
    val authorities: List<String> = emptyList(),
    val launchMode: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val themeId: Int = 0,
    val screenOrientation: String? = null,
    val configChanges: String? = null,
    val permission: String? = null,
    val readPermission: String? = null,
    val writePermission: String? = null,
    val grantUriPermissions: Boolean = false,
    val pathPermissions: List<VirtualProviderPathPermission> = emptyList(),
    val uriPermissionPatterns: List<VirtualProviderPathPattern> = emptyList(),
    val metaData: Map<String, String> = emptyMap(),
    val typedMetaData: Map<String, VirtualMetaDataValue> = emptyMap(),
    val targetActivityName: String? = null
)

/**
 * Resolves package metadata from an APK path.
 *
 * Implementations may use aapt2 dumps, PackageManager APIs, or cached records
 * to produce a [ResolvedPackage] for container startup.
 */
interface VirtualPackageResolver {
    /**
     * Resolve package metadata for the given APK file.
     *
     * @param apkPath absolute path to the APK on disk
     * @return resolved metadata, or null if the APK cannot be parsed
     */
    fun resolve(apkPath: String): ResolvedPackage?
}

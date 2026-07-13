package com.multiapp.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PatternMatcher
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.app.container.ContentProviderEngineProcessBootstrapper
import com.multiapp.app.container.EngineReadyActivityLauncher
import com.multiapp.core.engine.DefaultHostedRuntimeEngine
import com.multiapp.core.engine.EngineActivityLauncher
import com.multiapp.core.engine.EngineProcessBootstrapper
import com.multiapp.core.engine.EngineRuntimeSlotStore
import com.multiapp.core.engine.FileBackedEngineRuntimeSlotStore
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.engine.IpcVirtualizationEngine
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.toVirtualMetaDataMap
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceRecordStore
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallMetadata
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.installer.InstallMetadataResolver
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.installer.ProductionVirtualInstallService
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualProviderPathPermission
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVirtualizationEngine(engine: IpcVirtualizationEngine): VirtualizationEngine = engine

    @Provides
    @Singleton
    fun provideHostedRuntimeEngine(engine: DefaultHostedRuntimeEngine): HostedRuntimeEngine = engine

    @Provides
    @Singleton
    fun provideEngineActivityLauncher(@ApplicationContext context: Context): EngineActivityLauncher {
        return EngineReadyActivityLauncher(context)
    }

    @Provides
    @Singleton
    fun provideEngineProcessBootstrapper(@ApplicationContext context: Context): EngineProcessBootstrapper =
        ContentProviderEngineProcessBootstrapper(context)

    @Provides
    @Singleton
    fun provideEngineRuntimeSlotStore(@ApplicationContext context: Context): EngineRuntimeSlotStore {
        return FileBackedEngineRuntimeSlotStore(ContainerRuntimePaths.engineRuntimeSlotsFile(context))
    }

    @Provides
    @Singleton
    fun provideInstanceRecordStore(@ApplicationContext context: Context): InstanceRecordStore {
        return JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(context))
    }

    @Provides
    @Singleton
    fun provideInstallRecordStore(@ApplicationContext context: Context): InstallRecordStore {
        return JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(context))
    }

    @Provides
    @Singleton
    fun provideVirtualInstallService(
        installRecordStore: InstallRecordStore,
        @ApplicationContext context: Context
    ): VirtualInstallService {
        return ProductionVirtualInstallService(
            installRecordStore,
            ContainerRuntimePaths.artifactDir(context),
            metadataResolver = packageManagerInstallMetadataResolver(context)
        )
    }

    @Provides
    @Singleton
    fun provideInstanceManager(
        instanceRecordStore: InstanceRecordStore,
        installRecordStore: InstallRecordStore,
        @ApplicationContext context: Context
    ): InstanceManager {
        return DefaultInstanceManager(
            store = instanceRecordStore,
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(context),
            installRecordStore = installRecordStore
        )
    }

    private fun packageManagerInstallMetadataResolver(context: Context): InstallMetadataResolver {
        val appContext = context.applicationContext
        val manifestParser = ManifestParser(appContext)
        return InstallMetadataResolver { packageName, originApkPath ->
            parseInstallMetadataFromApk(
                packageName,
                originApkPath,
                manifestParser,
                appContext.packageManager
            )
        }
    }

    private fun parseInstallMetadataFromApk(
        packageName: String,
        originApkPath: String,
        manifestParser: ManifestParser,
        packageManager: PackageManager
    ): InstallMetadata {
        val originApk = File(originApkPath)
        require(originApk.isFile) { "APK file not found: $originApkPath" }
        val archiveInfo = requireNotNull(packageManager.getArchivePackageInfoWithComponents(originApkPath)) {
            "APK package metadata unavailable: $originApkPath"
        }
        val signingIdentity = archiveInfo.signingIdentity()
        val manifest = runCatching { manifestParser.parse(originApk) }.getOrNull()
        val archivePackageName = requireMatchingApkPackageIdentity(
            expectedPackageName = packageName,
            archivePackageName = archiveInfo.packageName,
            manifestPackageName = manifest?.packageName
        )
        if (manifest != null) {
            val manifestPackageName = manifest.packageName.ifBlank { archivePackageName }
            return InstallMetadata(
                permissions = manifest.permissions,
                activities = manifest.activities.toInstallComponentInfos(manifestPackageName),
                services = manifest.services.toInstallComponentInfos(manifestPackageName),
                receivers = manifest.receivers.toInstallComponentInfos(manifestPackageName),
                providers = manifest.providers.mapNotNull { provider ->
                    normalizeManifestComponentName(manifestPackageName, provider.name)?.let { name ->
                        val providerMetaData = manifest.providerMetaData[provider.name].toVirtualMetaDataMap()
                        ComponentInfo(
                            name = name,
                            exported = provider.exported,
                            permission = provider.permission,
                            readPermission = provider.readPermission,
                            writePermission = provider.writePermission,
                            grantUriPermissions = provider.grantUriPermissions,
                            pathPermissions = provider.pathPermissions,
                            uriPermissionPatterns = provider.uriPermissionPatterns,
                            metaData = providerMetaData
                        )
                    }
                },
                applicationMetaData = manifest.applicationMetaData.toVirtualMetaDataMap(),
                signerSha256Digests = signingIdentity.digests,
                hasMultipleSigners = signingIdentity.hasMultipleSigners
            )
        }
        return InstallMetadata(
            permissions = archiveInfo.requestedPermissions?.toList().orEmpty(),
            activities = archiveInfo.activities.toComponentInfos(),
            services = archiveInfo.services.toComponentInfos(),
            receivers = archiveInfo.receivers.toComponentInfos(),
            providers = archiveInfo.providers.toComponentInfos(),
            applicationMetaData = archiveInfo.applicationInfo?.metaData.toModelMetaData(),
            signerSha256Digests = signingIdentity.digests,
            hasMultipleSigners = signingIdentity.hasMultipleSigners,
            splitApkPaths = archiveInfo.applicationInfo?.splitSourceDirs?.filterNotBlank().orEmpty(),
            splitPublicSourceDirs = archiveInfo.applicationInfo?.splitPublicSourceDirs?.filterNotBlank().orEmpty(),
            splitNames = archiveInfo.applicationInfo?.splitNames?.filterNotBlank().orEmpty(),
            isolatedSplits = archiveInfo.applicationInfo?.safeRequestsIsolatedSplitLoading() ?: false
        )
    }

    private fun Array<out android.content.pm.ComponentInfo>?.toComponentInfos(): List<ComponentInfo> {
        return this?.mapNotNull { component ->
            component.name?.takeIf { it.isNotBlank() }?.let { name ->
                val provider = component as? ProviderInfo
                val uriPermissionPatterns = provider?.uriPermissionPatterns.orEmpty()
                    .mapNotNull { it.toVirtualProviderPathPattern() }
                ComponentInfo(
                    name = name,
                    exported = component.exported,
                    permission = component.componentPermission(),
                    readPermission = provider?.readPermission?.takeIf { it.isNotBlank() },
                    writePermission = provider?.writePermission?.takeIf { it.isNotBlank() },
                    grantUriPermissions = provider?.grantUriPermissions == true && uriPermissionPatterns.isEmpty(),
                    pathPermissions = provider?.pathPermissions.orEmpty().mapNotNull { permission ->
                        val pattern = permission.toVirtualProviderPathPattern() ?: return@mapNotNull null
                        val readPermission = permission.readPermission?.takeIf { it.isNotBlank() }
                        val writePermission = permission.writePermission?.takeIf { it.isNotBlank() }
                        if (readPermission == null && writePermission == null) return@mapNotNull null
                        VirtualProviderPathPermission(pattern, readPermission, writePermission)
                    },
                    uriPermissionPatterns = uriPermissionPatterns,
                    launchMode = (component as? ActivityInfo)?.launchModeString(),
                    processName = component.processName?.takeIf { it.isNotBlank() },
                    taskAffinity = (component as? ActivityInfo)?.taskAffinity?.takeIf { it.isNotBlank() },
                    themeId = (component as? ActivityInfo)?.theme ?: 0,
                    metaData = component.metaData.toModelMetaData(),
                    targetActivityName = (component as? ActivityInfo)?.targetActivity?.takeIf { it.isNotBlank() }
                )
            }
        }.orEmpty()
    }

    private fun Array<String>?.filterNotBlank(): List<String> =
        this?.filter { it.isNotBlank() }.orEmpty()

    private fun android.content.pm.ApplicationInfo.safeRequestsIsolatedSplitLoading(): Boolean =
        runCatching {
            javaClass.getMethod("requestsIsolatedSplitLoading").invoke(this) as? Boolean ?: false
        }.getOrDefault(false)

    private fun List<ManifestParser.ComponentInfo>.toInstallComponentInfos(packageName: String): List<ComponentInfo> {
        return mapNotNull { component ->
            normalizeManifestComponentName(packageName, component.name)?.let { name ->
                ComponentInfo(
                    name = name,
                    exported = component.exported,
                    permission = component.permission,
                    launchMode = component.launchMode,
                    processName = component.process,
                    taskAffinity = component.taskAffinity,
                    themeId = component.themeId,
                    metaData = component.metaData.toVirtualMetaDataMap(),
                    targetActivityName = normalizeManifestComponentName(packageName, component.targetActivityName)
                )
    }
}

    }

    private fun normalizeManifestComponentName(packageName: String, name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()
        return when {
            trimmed.startsWith(".") -> packageName + trimmed
            '.' !in trimmed -> "$packageName.$trimmed"
            else -> trimmed
        }
    }

    private fun android.content.pm.ComponentInfo.componentPermission(): String? = when (this) {
        is ActivityInfo -> permission
        is ServiceInfo -> permission
        is ProviderInfo -> providerPermission()
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun ActivityInfo.launchModeString(): String? = when (launchMode) {
        ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
        ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
        ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
        4 -> "singleInstancePerTask"
        else -> null
    }

    private fun PackageManager.getArchivePackageInfoWithComponents(apkPath: String): PackageInfo? {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_URI_PERMISSION_PATTERNS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= 33) {
            getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(apkPath, flags)
        }
    }

    private fun android.os.Bundle?.toModelMetaData() =
        this?.keySet()?.mapNotNull { key ->
            com.multiapp.core.model.virtual.VirtualMetaDataValue.fromAny(get(key))?.let { key to it }
        }?.toMap().orEmpty()

    private fun PatternMatcher.toVirtualProviderPathPattern(): VirtualProviderPathPattern? {
        val patternType = when (type) {
            PatternMatcher.PATTERN_LITERAL -> VirtualProviderPathPatternType.LITERAL
            PatternMatcher.PATTERN_PREFIX -> VirtualProviderPathPatternType.PREFIX
            PatternMatcher.PATTERN_SIMPLE_GLOB -> VirtualProviderPathPatternType.SIMPLE_GLOB
            PatternMatcher.PATTERN_ADVANCED_GLOB -> VirtualProviderPathPatternType.ADVANCED_GLOB
            PatternMatcher.PATTERN_SUFFIX -> VirtualProviderPathPatternType.SUFFIX
            else -> return null
        }
        return path?.takeIf { it.isNotEmpty() }?.let { VirtualProviderPathPattern(it, patternType) }
    }

    private fun PackageInfo.signingIdentity(): PackageSigningIdentity {
        val signing = signingInfo
        val hasMultiple = signing?.hasMultipleSigners() == true
        val signatures = when {
            signing == null -> {
                @Suppress("DEPRECATION")
                this.signatures?.toList().orEmpty()
            }
            hasMultiple -> signing.apkContentsSigners?.toList().orEmpty()
            else -> signing.signingCertificateHistory?.toList().orEmpty()
        }
        val digests = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.let { values -> if (hasMultiple) values.sorted() else values }
        return PackageSigningIdentity(digests, hasMultiple)
    }

    private data class PackageSigningIdentity(
        val digests: List<String>,
        val hasMultipleSigners: Boolean
    )

    private fun ProviderInfo.providerPermission(): String? {
        val readPermission = readPermission?.takeIf { it.isNotBlank() }
        val writePermission = writePermission?.takeIf { it.isNotBlank() }
        return readPermission.takeIf { it != null && it == writePermission }
    }

}

internal fun requireMatchingApkPackageIdentity(
    expectedPackageName: String,
    archivePackageName: String?,
    manifestPackageName: String?
): String {
    val archiveIdentity = requireNotNull(archivePackageName?.takeIf { it.isNotBlank() }) {
        "APK package metadata has no package name"
    }
    require(archiveIdentity == expectedPackageName) {
        "APK package mismatch: expected=$expectedPackageName, actual=$archiveIdentity"
    }
    manifestPackageName?.takeIf { it.isNotBlank() }?.let { manifestIdentity ->
        require(manifestIdentity == expectedPackageName) {
            "APK manifest package mismatch: expected=$expectedPackageName, actual=$manifestIdentity"
        }
    }
    return archiveIdentity
}

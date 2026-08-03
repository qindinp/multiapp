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
import com.multiapp.core.engine.EngineRuntimeIpcContract
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
        requireEngineProcess(context)
        return FileBackedEngineRuntimeSlotStore(ContainerRuntimePaths.engineRuntimeSlotsFile(context))
    }

    @Provides
    @Singleton
    fun provideInstanceRecordStore(@ApplicationContext context: Context): InstanceRecordStore {
        requireEngineProcess(context)
        return JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(context))
    }

    @Provides
    @Singleton
    fun provideInstallRecordStore(@ApplicationContext context: Context): InstallRecordStore {
        requireEngineProcess(context)
        return JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(context))
    }

    @Provides
    @Singleton
    fun provideVirtualInstallService(
        installRecordStore: InstallRecordStore,
        @ApplicationContext context: Context
    ): VirtualInstallService {
        requireEngineProcess(context)
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
        requireEngineProcess(context)
        return DefaultInstanceManager(
            store = instanceRecordStore,
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(context),
            installRecordStore = installRecordStore
        )
    }

    // Process guard for owner stores

    /**
     * Ensures that owner stores are only constructed in the :engine process.
     *
     * This is a defense-in-depth measure. The primary guard is that host/guest
     * code does not depend on these types (verified by boundary tests). This
     * runtime check catches any future accidental dependency.
     */
    private fun requireEngineProcess(context: Context) {
        val processName = runCatching {
            Class.forName("android.app.Application")
                .getMethod("getProcessName")
                .invoke(null) as? String
        }.getOrNull()
        val expected = EngineRuntimeIpcContract.engineProcessName(context.packageName)
        check(processName == expected) {
            val actual = processName?.takeIf { it.isNotBlank() } ?: "<unavailable>"
            "Engine owner store must only be constructed in ${expected}, actual=${actual}"
        }
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
                            authorities = providerAuthorities(provider.authorities),
                            permission = provider.permission,
                            readPermission = provider.readPermission,
                            writePermission = provider.writePermission,
                            grantUriPermissions = provider.grantUriPermissions,
                            pathPermissions = provider.pathPermissions,
                            uriPermissionPatterns = provider.uriPermissionPatterns,
                            metaData = providerMetaData
                        )
                    }
                }.distinctBy { it.name },
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
        // distinctBy name：真实应用 manifest 可声明同名组件（系统安装时去重保留其一，
        // PackageManager 返回数组仍含重复项）；snapshot 校验要求组件名唯一（2026-08-03
        // 真机定位：微信/起点/WPS 因重复 Activity 导致 runtime stream encode 失败）
        return this?.asSequence()
            ?.mapNotNull { component ->
                component.name?.takeIf { it.isNotBlank() }?.let { name ->
                    val provider = component as? ProviderInfo
                    val uriPermissionPatterns = provider?.uriPermissionPatterns.orEmpty()
                        .mapNotNull { it.toVirtualProviderPathPattern() }
                    ComponentInfo(
                        name = name,
                        exported = component.exported,
                        authorities = providerAuthorities(provider?.authority),
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
            }
            ?.distinctBy { it.name }
            ?.toList()
            .orEmpty()
    }

    private fun Array<String>?.filterNotBlank(): List<String> =
        this?.filter { it.isNotBlank() }.orEmpty()

    private fun android.content.pm.ApplicationInfo.safeRequestsIsolatedSplitLoading(): Boolean =
        runCatching {
            javaClass.getMethod("requestsIsolatedSplitLoading").invoke(this) as? Boolean ?: false
        }.getOrDefault(false)

    private fun List<ManifestParser.ComponentInfo>.toInstallComponentInfos(packageName: String): List<ComponentInfo> {
        // distinctBy name：真实应用 manifest 可声明同名组件（系统安装时去重保留其一）；
        // snapshot 校验要求组件名唯一（2026-08-03 真机定位：微信/起点/WPS 重复 Activity
        // 导致 runtime stream encode 失败 → PREPARE miss → launchInstance FAIL）
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
        }.distinctBy { it.name }
    }

    internal fun providerAuthorities(authority: String?): List<String> = authority
        ?.split(';')
        .orEmpty()
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

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

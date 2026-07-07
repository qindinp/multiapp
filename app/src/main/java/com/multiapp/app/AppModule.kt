package com.multiapp.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Build
import com.multiapp.app.container.ContainerActivity
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.engine.DefaultHostedRuntimeEngine
import com.multiapp.core.engine.DefaultVirtualizationEngine
import com.multiapp.core.engine.EngineActivityLauncher
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.model.engine.EngineLaunchIntentContract
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHookEngine(): HookEngine = HookEngine.getInstance()

    @Provides
    @Singleton
    fun provideVirtualizationEngine(engine: DefaultVirtualizationEngine): VirtualizationEngine = engine

    @Provides
    @Singleton
    fun provideHostedRuntimeEngine(engine: DefaultHostedRuntimeEngine): HostedRuntimeEngine = engine

    @Provides
    @Singleton
    fun provideEngineActivityLauncher(@ApplicationContext context: Context): EngineActivityLauncher {
        val appContext = context.applicationContext ?: context
        return EngineActivityLauncher { spec ->
            val intent = ContainerActivity.createIntent(
                context = appContext,
                instanceId = spec.instanceId,
                providerHookEnabled = spec.providerRoutingEnabled
            ).apply {
                putExtra(EngineLaunchIntentContract.EXTRA_ENGINE_PROFILE, spec.profile.name)
                putExtra(EngineLaunchIntentContract.EXTRA_ENGINE_PROCESS_SLOT, spec.processSlot)
                putExtra(EngineLaunchIntentContract.EXTRA_ENGINE_PROXY_SLOT, spec.proxySlot)
                putExtra(EngineLaunchIntentContract.EXTRA_ENGINE_EVIDENCE_SESSION_ID, spec.evidenceSessionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }
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
            parseInstallMetadataFromApk(packageName, originApkPath, manifestParser)?.let { metadata ->
                return@InstallMetadataResolver metadata
            }
            val packageInfo = appContext.packageManager.getInstalledPackageInfoWithComponents(packageName)
            InstallMetadata(
                permissions = packageInfo.requestedPermissions?.toList().orEmpty(),
                activities = packageInfo.activities.toComponentInfos(),
                services = packageInfo.services.toComponentInfos(),
                receivers = packageInfo.receivers.toComponentInfos(),
                providers = packageInfo.providers.toComponentInfos()
            )
        }
    }

    private fun parseInstallMetadataFromApk(
        packageName: String,
        originApkPath: String,
        manifestParser: ManifestParser
    ): InstallMetadata? {
        val originApk = File(originApkPath).takeIf { it.isFile } ?: return null
        return runCatching {
            val manifest = manifestParser.parse(originApk)
            val manifestPackageName = manifest.packageName.ifBlank { packageName }
            if (manifestPackageName != packageName) return@runCatching null
            InstallMetadata(
                permissions = manifest.permissions,
                activities = manifest.activities.toInstallComponentInfos(manifestPackageName),
                services = manifest.services.toInstallComponentInfos(manifestPackageName),
                receivers = manifest.receivers.toInstallComponentInfos(manifestPackageName),
                providers = manifest.providers.mapNotNull { provider ->
                    normalizeManifestComponentName(manifestPackageName, provider.name)?.let { name ->
                        ComponentInfo(
                            name = name,
                            exported = provider.exported,
                            permission = provider.permission,
                            grantUriPermissions = provider.grantUriPermissions
                        )
                    }
                }
            )
        }.getOrNull()
    }

    private fun PackageManager.getInstalledPackageInfoWithComponents(packageName: String): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= 33) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, flags)
        }
    }

    private fun Array<out android.content.pm.ComponentInfo>?.toComponentInfos(): List<ComponentInfo> {
        return this?.mapNotNull { component ->
            component.name?.takeIf { it.isNotBlank() }?.let { name ->
                ComponentInfo(
                    name = name,
                    exported = component.exported,
                    permission = component.componentPermission(),
                    grantUriPermissions = (component as? ProviderInfo)?.grantUriPermissions ?: false
                )
            }
        }.orEmpty()
    }

    private fun List<ManifestParser.ComponentInfo>.toInstallComponentInfos(packageName: String): List<ComponentInfo> {
        return mapNotNull { component ->
            normalizeManifestComponentName(packageName, component.name)?.let { name ->
                ComponentInfo(
                    name = name,
                    exported = component.exported,
                    permission = component.permission
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

    private fun ProviderInfo.providerPermission(): String? {
        val readPermission = readPermission?.takeIf { it.isNotBlank() }
        val writePermission = writePermission?.takeIf { it.isNotBlank() }
        return when {
            readPermission == null && writePermission == null -> null
            readPermission == writePermission -> readPermission
            else -> listOfNotNull(
                readPermission?.let { "read=$it" },
                writePermission?.let { "write=$it" }
            ).joinToString(";")
        }
    }
}

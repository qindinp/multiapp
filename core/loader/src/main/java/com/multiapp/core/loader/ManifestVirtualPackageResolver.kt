package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageResolver
import java.io.File

/** Production APK manifest resolver used by the hosted container runtime. */
class ManifestVirtualPackageResolver(
    context: Context,
    private val parser: ManifestParser = ManifestParser(context.applicationContext),
    private val extractor: ComponentExtractor = ComponentExtractor()
) : VirtualPackageResolver {

    override fun resolve(apkPath: String): ResolvedPackage? {
        val apkFile = File(apkPath)
        if (!apkFile.isFile) return null
        return runCatching {
            val manifest = parser.parse(apkFile)
            val launcher = extractor.extractLauncherActivity(manifest)
            ResolvedPackage(
                packageName = manifest.packageName,
                versionCode = 1L,
                versionName = "unknown",
                targetSdk = manifest.targetSdkVersion,
                minSdk = manifest.minSdkVersion,
                applicationClassName = normalizeComponentName(
                    manifest.packageName,
                    manifest.applicationClass
                ),
                themeId = manifest.applicationThemeId,
                launcherActivityName = normalizeComponentName(
                    manifest.packageName,
                    launcher?.targetActivityName ?: launcher?.name
                ),
                activities = manifest.activities.mapNotNull { component ->
                    normalizeComponentName(manifest.packageName, component.name)?.let { name ->
                        ResolvedComponent(
                            name = name,
                            exported = component.exported,
                            intentFilters = component.intentFilters.flatMap { filter ->
                                filter.actions + filter.categories
                            },
                            launchMode = component.launchMode,
                            processName = component.process,
                            taskAffinity = component.taskAffinity,
                            themeId = component.themeId,
                            screenOrientation = component.screenOrientation,
                            configChanges = component.configChanges,
                            permission = component.permission,
                            resolvedIntentFilters = component.intentFilters.map { filter ->
                                ResolvedIntentFilter(
                                    actions = filter.actions,
                                    categories = filter.categories
                                )
                            },
                            targetActivityName = normalizeComponentName(
                                manifest.packageName,
                                component.targetActivityName
                            )
                        )
                    }
                },
                services = manifest.services.mapNotNull { component ->
                    normalizeComponentName(manifest.packageName, component.name)?.let { name ->
                        ResolvedComponent(
                            name = name,
                            exported = component.exported,
                            processName = component.process,
                            permission = component.permission
                        )
                    }
                },
                receivers = manifest.receivers.mapNotNull { component ->
                    normalizeComponentName(manifest.packageName, component.name)?.let { name ->
                        ResolvedComponent(
                            name = name,
                            exported = component.exported,
                            processName = component.process,
                            permission = component.permission
                        )
                    }
                },
                providers = manifest.providers.mapNotNull { provider ->
                    normalizeComponentName(manifest.packageName, provider.name)?.let { name ->
                        ResolvedComponent(
                            name = name,
                            exported = provider.exported,
                            authorities = provider.authorities
                                .split(';')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                            permission = provider.permission,
                            grantUriPermissions = provider.grantUriPermissions,
                            metaData = manifest.providerMetaData[provider.name].toStringMap()
                        )
                    }
                },
                permissions = manifest.permissions,
                applicationLabel = manifest.applicationLabel
            )
        }.getOrNull()
    }

    companion object {
        internal fun normalizeComponentName(packageName: String, name: String?): String? {
            if (name.isNullOrBlank()) return null
            return when {
                name.startsWith(".") -> packageName + name
                '.' !in name -> "$packageName.$name"
                else -> name
            }
        }

        private fun List<ManifestParser.MetaDataInfo>?.toStringMap(): Map<String, String> =
            this?.mapNotNull { item ->
                val value = item.value
                    ?: item.resource
                    ?: item.resourceId.takeIf { it != 0 }?.let { "@0x${Integer.toHexString(it)}" }
                value?.let { item.name to it }
            }?.toMap().orEmpty()
    }
}

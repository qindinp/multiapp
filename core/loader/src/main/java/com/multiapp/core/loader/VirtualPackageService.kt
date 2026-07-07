package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Snapshot-backed VPMS query backend for one hosted virtual package.
 *
 * This class intentionally does not hook AppGlobals/IPackageManager or native IO;
 * callers decide how to fall back when a query does not belong to the snapshot.
 */
class VirtualPackageService(
    private val snapshot: VirtualPackageSnapshot
) {

    fun getPackageInfo(packageName: String): PackageInfo? =
        if (snapshot.matchesPackageName(packageName)) VirtualPackageInfoFactory.packageInfo(snapshot) else null

    fun getApplicationInfo(packageName: String): ApplicationInfo? =
        if (snapshot.matchesPackageName(packageName)) VirtualPackageInfoFactory.applicationInfo(snapshot) else null

    fun getApplicationInfoForResources(packageName: String): ApplicationInfo? =
        getApplicationInfo(packageName)

    fun getApplicationInfoForResources(appInfo: ApplicationInfo): ApplicationInfo? =
        if (snapshot.matchesPackageName(appInfo.packageName)) {
            VirtualPackageInfoFactory.applicationInfo(snapshot)
        } else {
            null
        }

    fun getActivityInfo(component: ComponentName): ActivityInfo? =
        VirtualPackageInfoFactory.findActivity(snapshot, component)

    fun getServiceInfo(component: ComponentName): ServiceInfo? =
        VirtualPackageInfoFactory.findService(snapshot, component)

    fun getReceiverInfo(component: ComponentName): ActivityInfo? =
        VirtualPackageInfoFactory.findReceiver(snapshot, component)

    fun getProviderInfo(component: ComponentName): ProviderInfo? {
        if (!snapshot.matchesPackageName(component.packageName)) return null
        return snapshot.providers.firstOrNull { it.name == component.className }
            ?.let { VirtualPackageInfoFactory.providerInfo(snapshot, it) }
    }

    fun containsComponent(component: ComponentName): Boolean {
        if (!snapshot.matchesPackageName(component.packageName)) return false
        return allComponents().any { it.name == component.className || it.targetActivityName == component.className }
    }

    fun getComponentEnabledSetting(component: ComponentName): Int? =
        if (containsComponent(component)) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else null

    fun setComponentEnabledSetting(component: ComponentName, newState: Int, flags: Int): Boolean =
        containsComponent(component)

    fun resolveContentProvider(authority: String): ProviderInfo? =
        VirtualPackageInfoFactory.findProvider(snapshot, authority)

    fun queryIntentActivities(intent: Intent): List<ResolveInfo> =
        queryComponents(intent, snapshot.activities) { component ->
            ResolveInfo().apply { activityInfo = VirtualPackageInfoFactory.activityInfo(snapshot, component) }
        }.ifEmpty {
            resolveLauncherActivity(intent)?.let { listOf(it) } ?: emptyList()
        }

    fun resolveActivity(intent: Intent): ResolveInfo? =
        queryIntentActivities(intent).firstOrNull()

    fun queryIntentServices(intent: Intent): List<ResolveInfo> =
        queryComponents(intent, snapshot.services) { component ->
            ResolveInfo().apply { serviceInfo = VirtualPackageInfoFactory.serviceInfo(snapshot, component) }
        }

    fun resolveService(intent: Intent): ResolveInfo? =
        queryIntentServices(intent).firstOrNull()

    fun queryBroadcastReceivers(intent: Intent): List<ResolveInfo> =
        queryComponents(intent, snapshot.receivers) { component ->
            ResolveInfo().apply { activityInfo = VirtualPackageInfoFactory.receiverInfo(snapshot, component) }
        }

    fun queryIntentContentProviders(intent: Intent): List<ResolveInfo> =
        queryComponents(intent, snapshot.providers) { component ->
            VirtualPackageInfoFactory.providerInfo(snapshot, component)?.let { providerInfo ->
                ResolveInfo().apply { this.providerInfo = providerInfo }
            }
        }

    fun getLaunchIntentForPackage(packageName: String): Intent? {
        if (!snapshot.matchesPackageName(packageName)) return null
        val launcher = snapshot.launcherActivityName
            ?: snapshot.activities.resolveLauncherIntentActivityName()
            ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(snapshot.originPackageName, launcher))
    }

    fun getInstalledPackages(): List<PackageInfo> =
        listOf(VirtualPackageInfoFactory.packageInfo(snapshot))

    fun getInstalledApplications(): List<ApplicationInfo> =
        listOf(VirtualPackageInfoFactory.applicationInfo(snapshot))

    fun getApplicationLabel(info: ApplicationInfo): CharSequence? =
        if (snapshot.matchesPackageName(info.packageName)) snapshot.applicationLabel else null

    fun checkPermission(permissionName: String, packageName: String): Int? {
        if (!snapshot.matchesPackageName(packageName)) return null
        return if (permissionName in snapshot.permissions) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
    }

    fun isInstantApp(packageName: String): Boolean? =
        if (snapshot.matchesPackageName(packageName)) false else null

    fun getPackageUid(packageName: String, runtimeUid: Int): Int? =
        if (snapshot.matchesPackageName(packageName)) runtimeUid else null

    fun getPackagesForUid(uid: Int, runtimeUid: Int): Array<String>? =
        if (uid == runtimeUid) packageAliases().toTypedArray() else null

    fun getNameForUid(uid: Int, runtimeUid: Int): String? =
        if (uid == runtimeUid) snapshot.originPackageName else null

    fun getPackagesHoldingPermissions(permissions: Array<String>): List<PackageInfo> {
        if (permissions.isEmpty()) return emptyList()
        return if (permissions.any { it in snapshot.permissions }) {
            listOf(VirtualPackageInfoFactory.packageInfo(snapshot))
        } else {
            emptyList()
        }
    }

    fun queryContentProviders(processName: String?, uid: Int, runtimeUid: Int): List<ProviderInfo> {
        if (uid != runtimeUid) return emptyList()
        return snapshot.providers.mapNotNull { component ->
            if (!component.matchesProcessName(processName)) return@mapNotNull null
            VirtualPackageInfoFactory.providerInfo(snapshot, component)
        }
    }

    fun packageAliases(): List<String> = listOf(
        snapshot.originPackageName,
        snapshot.virtualPackageName
    ).filter { it.isNotBlank() }.distinct()

    private fun allComponents(): List<ResolvedComponent> =
        snapshot.activities + snapshot.services + snapshot.receivers + snapshot.providers

    private fun resolveLauncherActivity(intent: Intent): ResolveInfo? {
        if (intent.action != Intent.ACTION_MAIN) return null
        if (intent.categories?.contains(Intent.CATEGORY_LAUNCHER) != true) return null
        return VirtualPackageInfoFactory.launcherResolveInfo(snapshot)
    }

    private fun <T> queryComponents(
        intent: Intent,
        components: List<ResolvedComponent>,
        toResolveInfo: (ResolvedComponent) -> T?
    ): List<T> {
        val componentName = intent.component
        val candidates = if (componentName != null) {
            if (!snapshot.matchesPackageName(componentName.packageName)) return emptyList()
            components.filter { it.name == componentName.className }
        } else {
            val packageName = intent.safePackageName()
            if (packageName != null && !snapshot.matchesPackageName(packageName)) return emptyList()
            components.filter { VirtualIntentFilterMatcher.matches(intent, it) }
        }
        return candidates.mapNotNull(toResolveInfo)
    }

    private fun ResolvedComponent.matchesProcessName(processName: String?): Boolean {
        if (processName == null) return true
        val componentProcessName = this.processName ?: snapshot.processName ?: snapshot.originPackageName
        return componentProcessName == processName
    }
}

internal object VirtualIntentFilterMatcher {

    fun matches(intent: Intent, component: ResolvedComponent): Boolean {
        val action = intent.action ?: return false
        val categories = intent.categories.orEmpty()
        val scheme = intent.safeScheme()
        return component.effectiveFilters().any { filter ->
            filter.matches(action, categories, scheme)
        }
    }

    private fun ResolvedComponent.effectiveFilters(): List<ResolvedIntentFilter> =
        resolvedIntentFilters.ifEmpty { legacyIntentFilters() }

    private fun ResolvedComponent.legacyIntentFilters(): List<ResolvedIntentFilter> {
        if (intentFilters.isEmpty()) return emptyList()
        return listOf(
            ResolvedIntentFilter(
                actions = intentFilters.filterNot { it.isLegacyCategory() },
                categories = intentFilters.filter { it.isLegacyCategory() }
            )
        )
    }

    private fun ResolvedIntentFilter.matches(
        action: String,
        categories: Set<String>,
        scheme: String?
    ): Boolean {
        if (action !in actions) return false
        if (!categories.all { it in this.categories }) return false
        if (dataSchemes.isEmpty()) return scheme == null
        return scheme != null && dataSchemes.any { it.equals(scheme, ignoreCase = true) }
    }

    private fun String.isLegacyCategory(): Boolean =
        startsWith("android.intent.category.")
}

internal fun Intent.safePackageName(): String? = runCatching { `package` }.getOrNull()

internal fun Intent.safeScheme(): String? = runCatching { scheme }.getOrNull()

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
import com.multiapp.core.model.virtual.IntentFilterMatchRequest
import com.multiapp.core.model.virtual.IntentFilterMatchResult
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedIntentFilterMatcher as SharedIntentFilterMatcher
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Snapshot-backed VPMS query backend for one hosted virtual package.
 *
 * This class intentionally does not hook AppGlobals/IPackageManager or native IO;
 * callers decide how to fall back when a query does not belong to the snapshot.
 */
class VirtualPackageService(
    private val snapshot: VirtualPackageSnapshot,
    private val runtimeUid: Int,
    private val packageSigningInfo: VirtualPackageSigningInfo? = null,
    private val permissionCheckDispatcher: VirtualPermissionCheckDispatcher =
        VirtualPermissionCheckDispatcher(VirtualPermissionCheckDispatchers::dispatch),
    private val enabledStateDispatcher: VirtualPackageEnabledStateDispatcher =
        VirtualPackageEnabledStateDispatcher(VirtualPackageEnabledStateDispatchers::dispatch)
) {

    init {
        require(runtimeUid > 0) { "runtimeUid must be a positive Android application UID" }
    }

    fun handlesPackage(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && snapshot.matchesPackageName(packageName)

    internal fun getPackageInfo(packageName: String): PackageInfo? =
        getPackageInfo(packageName, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getPackageInfo(packageName: String, flags: Long): PackageInfo? =
        if (handlesPackage(packageName)) {
            VirtualPackageInfoFactory.packageInfo(snapshot, runtimeUid, flags, packageSigningInfo)
        } else {
            null
        }

    internal fun getApplicationInfo(packageName: String): ApplicationInfo? =
        getApplicationInfo(packageName, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getApplicationInfo(packageName: String, flags: Long): ApplicationInfo? =
        if (handlesPackage(packageName)) {
            VirtualPackageInfoFactory.applicationInfo(snapshot, runtimeUid, flags)
        } else {
            null
        }

    fun getApplicationInfoForResources(packageName: String): ApplicationInfo? =
        getApplicationInfo(packageName, VirtualPackageQueryFlags.NONE)

    fun getApplicationInfoForResources(appInfo: ApplicationInfo): ApplicationInfo? =
        if (handlesPackage(appInfo.packageName)) {
            VirtualPackageInfoFactory.applicationInfo(snapshot, runtimeUid, VirtualPackageQueryFlags.NONE)
        } else {
            null
        }

    internal fun getActivityInfo(component: ComponentName): ActivityInfo? =
        getActivityInfo(component, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getActivityInfo(component: ComponentName, flags: Long): ActivityInfo? =
        VirtualPackageInfoFactory.findActivity(snapshot, component, runtimeUid, flags)

    internal fun getServiceInfo(component: ComponentName): ServiceInfo? =
        getServiceInfo(component, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getServiceInfo(component: ComponentName, flags: Long): ServiceInfo? =
        VirtualPackageInfoFactory.findService(snapshot, component, runtimeUid, flags)

    internal fun getReceiverInfo(component: ComponentName): ActivityInfo? =
        getReceiverInfo(component, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getReceiverInfo(component: ComponentName, flags: Long): ActivityInfo? =
        VirtualPackageInfoFactory.findReceiver(snapshot, component, runtimeUid, flags)

    internal fun getProviderInfo(component: ComponentName): ProviderInfo? =
        getProviderInfo(component, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getProviderInfo(component: ComponentName, flags: Long): ProviderInfo? {
        if (!snapshot.matchesPackageName(component.packageName)) return null
        return snapshot.providers.firstOrNull { it.name == component.className }
            ?.let { VirtualPackageInfoFactory.providerInfo(snapshot, it, runtimeUid, flags) }
    }

    fun containsComponent(component: ComponentName): Boolean {
        return enabledStateComponent(component) != null
    }

    fun getApplicationEnabledSetting(packageName: String): Int? {
        if (!handlesPackage(packageName)) return null
        return queryEnabledState(
            VirtualPackageEnabledStateRequest(
                instanceId = snapshot.instanceId,
                packageName = packageName,
                operation = VirtualPackageEnabledStateOperation.QUERY,
                target = VirtualPackageEnabledStateTarget.APPLICATION
            )
        )
    }

    fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int): Boolean {
        if (!handlesPackage(packageName)) return false
        dispatchEnabledStateMutation(
            VirtualPackageEnabledStateRequest(
                instanceId = snapshot.instanceId,
                packageName = packageName,
                operation = VirtualPackageEnabledStateOperation.SET,
                target = VirtualPackageEnabledStateTarget.APPLICATION,
                newState = newState,
                flags = flags
            )
        )
        return true
    }

    fun getComponentEnabledSetting(component: ComponentName): Int? {
        if (!handlesPackage(component.packageName)) return null
        val resolvedComponent = enabledStateComponent(component)
            ?: return PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        return queryEnabledState(
            VirtualPackageEnabledStateRequest(
                instanceId = snapshot.instanceId,
                packageName = component.packageName,
                operation = VirtualPackageEnabledStateOperation.QUERY,
                target = VirtualPackageEnabledStateTarget.COMPONENT,
                componentType = resolvedComponent.type,
                className = component.className
            )
        )
    }

    fun setComponentEnabledSetting(component: ComponentName, newState: Int, flags: Int): Boolean {
        if (!handlesPackage(component.packageName)) return false
        val resolvedComponent = enabledStateComponent(component) ?: return true
        dispatchEnabledStateMutation(
            VirtualPackageEnabledStateRequest(
                instanceId = snapshot.instanceId,
                packageName = component.packageName,
                operation = VirtualPackageEnabledStateOperation.SET,
                target = VirtualPackageEnabledStateTarget.COMPONENT,
                componentType = resolvedComponent.type,
                className = component.className,
                newState = newState,
                flags = flags
            )
        )
        return true
    }

    internal fun resolveContentProvider(authority: String): ProviderInfo? =
        resolveContentProvider(authority, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun resolveContentProvider(authority: String, flags: Long): ProviderInfo? =
        VirtualPackageInfoFactory.findProvider(snapshot, authority, runtimeUid, flags)

    internal fun queryIntentActivities(intent: Intent): List<ResolveInfo> =
        queryIntentActivities(intent, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun queryIntentActivities(
        intent: Intent,
        flags: Long,
        resolvedType: String? = null
    ): List<ResolveInfo> =
        queryComponents(intent, snapshot.activities, resolvedType) { component, priority ->
            ResolveInfo().apply {
                activityInfo = VirtualPackageInfoFactory.activityInfo(snapshot, component, runtimeUid, flags)
                this.priority = priority
            }
        }.ifEmpty {
            resolveLauncherActivity(intent, flags)?.let { listOf(it) } ?: emptyList()
        }

    internal fun resolveActivity(intent: Intent): ResolveInfo? =
        resolveActivity(intent, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun resolveActivity(intent: Intent, flags: Long, resolvedType: String? = null): ResolveInfo? =
        queryIntentActivities(intent, flags, resolvedType).firstOrNull()

    internal fun queryIntentServices(intent: Intent): List<ResolveInfo> =
        queryIntentServices(intent, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun queryIntentServices(
        intent: Intent,
        flags: Long,
        resolvedType: String? = null
    ): List<ResolveInfo> =
        queryComponents(intent, snapshot.services, resolvedType) { component, priority ->
            ResolveInfo().apply {
                serviceInfo = VirtualPackageInfoFactory.serviceInfo(snapshot, component, runtimeUid, flags)
                this.priority = priority
            }
        }

    internal fun resolveService(intent: Intent): ResolveInfo? =
        resolveService(intent, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun resolveService(intent: Intent, flags: Long, resolvedType: String? = null): ResolveInfo? =
        queryIntentServices(intent, flags, resolvedType).firstOrNull()

    internal fun queryBroadcastReceivers(intent: Intent): List<ResolveInfo> =
        queryBroadcastReceivers(intent, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun queryBroadcastReceivers(
        intent: Intent,
        flags: Long,
        resolvedType: String? = null
    ): List<ResolveInfo> =
        queryComponents(intent, snapshot.receivers, resolvedType) { component, priority ->
            ResolveInfo().apply {
                activityInfo = VirtualPackageInfoFactory.receiverInfo(snapshot, component, runtimeUid, flags)
                this.priority = priority
            }
        }

    internal fun queryIntentContentProviders(intent: Intent): List<ResolveInfo> =
        queryIntentContentProviders(intent, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun queryIntentContentProviders(
        intent: Intent,
        flags: Long,
        resolvedType: String? = null
    ): List<ResolveInfo> =
        queryComponents(intent, snapshot.providers, resolvedType) { component, priority ->
            VirtualPackageInfoFactory.providerInfo(snapshot, component, runtimeUid, flags)?.let { providerInfo ->
                ResolveInfo().apply {
                    this.providerInfo = providerInfo
                    this.priority = priority
                }
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

    internal fun getInstalledPackages(): List<PackageInfo> =
        getInstalledPackages(VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getInstalledPackages(flags: Long): List<PackageInfo> =
        listOf(VirtualPackageInfoFactory.packageInfo(snapshot, runtimeUid, flags, packageSigningInfo))

    internal fun getInstalledApplications(): List<ApplicationInfo> =
        getInstalledApplications(VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getInstalledApplications(flags: Long): List<ApplicationInfo> =
        listOf(VirtualPackageInfoFactory.applicationInfo(snapshot, runtimeUid, flags))

    fun getApplicationLabel(info: ApplicationInfo): CharSequence? =
        if (snapshot.matchesPackageName(info.packageName)) snapshot.applicationLabel else null

    fun checkPermission(permissionName: String, packageName: String): Int? {
        if (!snapshot.matchesPackageName(packageName)) return null
        if (permissionName !in snapshot.permissions) return PackageManager.PERMISSION_DENIED
        val result = permissionCheckDispatcher.dispatch(
            VirtualPermissionCheckRequest(
                instanceId = snapshot.instanceId,
                packageName = packageName,
                permissionName = permissionName
            )
        )
        return if (result.handled && result.granted) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
    }

    fun isInstantApp(packageName: String): Boolean? =
        if (snapshot.matchesPackageName(packageName)) false else null

    fun getPackageUid(packageName: String, runtimeUid: Int = this.runtimeUid): Int? =
        if (handlesPackage(packageName) && runtimeUid == this.runtimeUid) runtimeUid else null

    fun getPackagesForUid(uid: Int, runtimeUid: Int): Array<String>? =
        if (uid == runtimeUid) packageAliases().toTypedArray() else null

    fun getNameForUid(uid: Int, runtimeUid: Int): String? =
        if (uid == runtimeUid) snapshot.originPackageName else null

    internal fun getPackagesHoldingPermissions(permissions: Array<String>): List<PackageInfo> =
        getPackagesHoldingPermissions(permissions, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun getPackagesHoldingPermissions(permissions: Array<String>, flags: Long): List<PackageInfo> {
        if (permissions.isEmpty()) return emptyList()
        return if (
            permissions.any {
                checkPermission(it, snapshot.originPackageName) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            listOf(VirtualPackageInfoFactory.packageInfo(snapshot, runtimeUid, flags, packageSigningInfo))
        } else {
            emptyList()
        }
    }

    internal fun queryContentProviders(processName: String?, uid: Int, runtimeUid: Int): List<ProviderInfo> =
        queryContentProviders(processName, uid, runtimeUid, VirtualPackageQueryFlags.INTERNAL_FULL)

    fun queryContentProviders(
        processName: String?,
        uid: Int,
        runtimeUid: Int,
        flags: Long
    ): List<ProviderInfo> {
        if (runtimeUid != this.runtimeUid || uid != this.runtimeUid) return emptyList()
        return snapshot.providers.mapNotNull { component ->
            if (!component.matchesProcessName(processName)) return@mapNotNull null
            VirtualPackageInfoFactory.providerInfo(snapshot, component, this.runtimeUid, flags)
        }
    }

    internal fun signingInfoForPackage(packageName: String): VirtualPackageSigningInfo? =
        packageSigningInfo.takeIf { handlesPackage(packageName) }

    internal fun signingInfoForRuntimeUid(uid: Int): VirtualPackageSigningInfo? =
        packageSigningInfo.takeIf { uid == runtimeUid }

    fun hasSigningCertificate(packageName: String, certificate: ByteArray, type: Int): Boolean? =
        if (handlesPackage(packageName)) packageSigningInfo?.hasCertificate(certificate, type) ?: false else null

    fun hasRuntimeUidSigningCertificate(uid: Int, certificate: ByteArray, type: Int): Boolean? =
        if (uid == runtimeUid) packageSigningInfo?.hasCertificate(certificate, type) ?: false else null

    fun packageAliases(): List<String> = listOf(
        snapshot.originPackageName,
        snapshot.virtualPackageName
    ).filter { it.isNotBlank() }.distinct()

    private fun enabledStateComponent(component: ComponentName): EnabledStateComponent? {
        if (!snapshot.matchesPackageName(component.packageName)) return null
        fun List<ResolvedComponent>.containsRequestedComponent(): Boolean = any { candidate ->
            candidate.name == component.className || candidate.targetActivityName == component.className
        }
        return when {
            snapshot.activities.containsRequestedComponent() ->
                EnabledStateComponent(VirtualPackageEnabledComponentType.ACTIVITY)
            snapshot.services.containsRequestedComponent() ->
                EnabledStateComponent(VirtualPackageEnabledComponentType.SERVICE)
            snapshot.receivers.containsRequestedComponent() ->
                EnabledStateComponent(VirtualPackageEnabledComponentType.RECEIVER)
            snapshot.providers.containsRequestedComponent() ->
                EnabledStateComponent(VirtualPackageEnabledComponentType.PROVIDER)
            else -> null
        }
    }

    private fun queryEnabledState(request: VirtualPackageEnabledStateRequest): Int {
        val result = runCatching { enabledStateDispatcher.dispatch(request) }.getOrNull()
        val state = result?.enabledState
        val validState = when (request.target) {
            VirtualPackageEnabledStateTarget.APPLICATION -> state != null && isValidApplicationEnabledState(state)
            VirtualPackageEnabledStateTarget.COMPONENT -> state != null && isValidComponentEnabledState(state)
        }
        return if (result?.authoritative == true && result.found && validState) {
            requireNotNull(state)
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }

    private fun dispatchEnabledStateMutation(request: VirtualPackageEnabledStateRequest) {
        runCatching { enabledStateDispatcher.dispatch(request) }
    }

    private fun isValidApplicationEnabledState(state: Int): Boolean = state in
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT..PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED

    private fun isValidComponentEnabledState(state: Int): Boolean = state in
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT..PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun resolveLauncherActivity(intent: Intent, flags: Long): ResolveInfo? {
        if (intent.action != Intent.ACTION_MAIN) return null
        if (intent.categories?.contains(Intent.CATEGORY_LAUNCHER) != true) return null
        return VirtualPackageInfoFactory.launcherResolveInfo(snapshot, runtimeUid, flags)
    }

    private fun <T> queryComponents(
        intent: Intent,
        components: List<ResolvedComponent>,
        resolvedType: String?,
        toResolveInfo: (ResolvedComponent, Int) -> T?
    ): List<T> {
        val componentName = intent.component
        val candidates: List<Pair<ResolvedComponent, Int>> = if (componentName != null) {
            if (!snapshot.matchesPackageName(componentName.packageName)) return emptyList()
            components.filter { it.name == componentName.className }.map { component -> component to 0 }
        } else {
            val packageName = intent.safePackageName()
            if (packageName != null && !snapshot.matchesPackageName(packageName)) return emptyList()
            components.mapNotNull { component ->
                VirtualIntentFilterMatcher.bestMatch(intent, component, resolvedType)
                    ?.let { match -> component to match.priority }
            }.sortedWith(
                compareByDescending<Pair<ResolvedComponent, Int>> { it.second }
                    .thenBy { it.first.name }
            )
        }
        return candidates.mapNotNull { (component, priority) -> toResolveInfo(component, priority) }
    }

    private fun ResolvedComponent.matchesProcessName(processName: String?): Boolean {
        if (processName == null) return true
        val componentProcessName = this.processName ?: snapshot.processName ?: snapshot.originPackageName
        return componentProcessName == processName
    }

    private data class EnabledStateComponent(
        val type: VirtualPackageEnabledComponentType
    )
}

internal object VirtualIntentFilterMatcher {

    fun matches(intent: Intent, component: ResolvedComponent, resolvedType: String? = null): Boolean =
        bestMatch(intent, component, resolvedType) != null

    fun matches(request: IntentFilterMatchRequest, component: ResolvedComponent): Boolean =
        bestMatch(request, component) != null

    fun bestMatch(
        intent: Intent,
        component: ResolvedComponent,
        resolvedType: String? = null
    ): IntentFilterMatchResult? = bestMatch(intent.toMatchRequest(resolvedType), component)

    fun bestMatch(
        request: IntentFilterMatchRequest,
        component: ResolvedComponent
    ): IntentFilterMatchResult? = component.effectiveFilters(request)
            .map { filter -> SharedIntentFilterMatcher.match(filter, request) }
            .filter(IntentFilterMatchResult::matched)
            .maxWithOrNull(compareBy<IntentFilterMatchResult> { it.priority })

    private fun ResolvedComponent.effectiveFilters(
        request: IntentFilterMatchRequest
    ): List<ResolvedIntentFilter> =
        resolvedIntentFilters.ifEmpty { legacyIntentFilters(request) }

    private fun ResolvedComponent.legacyIntentFilters(
        request: IntentFilterMatchRequest
    ): List<ResolvedIntentFilter> {
        val action = request.action ?: return emptyList()
        if (action !in intentFilters) {
            return emptyList()
        }
        return listOf(
            ResolvedIntentFilter(
                actions = listOf(action),
                categories = request.categories.toList()
            )
        )
    }

    private fun Intent.toMatchRequest(resolvedType: String?): IntentFilterMatchRequest {
        val data = runCatching { data }.getOrNull()
        val dataScheme = runCatching { scheme }.getOrNull().nonBlankOrNull()
        val dataHost = runCatching { data?.host }.getOrNull().nonBlankOrNull()
        val dataPath = runCatching { data?.path }.getOrNull().nonBlankOrNull()
        val dataString = runCatching { dataString }.getOrNull().nonBlankOrNull()
        val dataPort = runCatching { data?.port }.getOrNull()
            ?.takeIf { dataHost != null && it >= 0 }
        return IntentFilterMatchRequest(
            action = runCatching { action }.getOrNull().nonBlankOrNull(),
            categories = runCatching { categories.orEmpty() }
                .getOrDefault(emptySet())
                .filterTo(linkedSetOf()) { it.isNotBlank() },
            scheme = dataScheme,
            mimeType = (resolvedType ?: runCatching { type }.getOrNull()).nonBlankOrNull(),
            host = dataHost,
            port = dataPort,
            path = dataPath,
            hasData = dataString != null || dataScheme != null || dataHost != null ||
                dataPort != null || dataPath != null
        )
    }
}

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

internal fun Intent.safePackageName(): String? = runCatching { `package` }.getOrNull()

internal fun Intent.safeScheme(): String? = runCatching { scheme }.getOrNull()

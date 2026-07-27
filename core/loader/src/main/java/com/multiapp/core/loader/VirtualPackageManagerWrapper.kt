package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageItemInfo
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.Build
import android.os.UserHandle
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/** PackageManager facade for one hosted virtual package snapshot. */
class VirtualPackageManagerWrapper(
    private val base: PackageManager,
    snapshot: VirtualPackageSnapshot,
    private val runtimeUid: Int,
    packageSigningInfo: VirtualPackageSigningInfo? = VirtualPackageArchiveSigningResolver.resolve(base, snapshot),
    permissionCheckDispatcher: VirtualPermissionCheckDispatcher =
        VirtualPermissionCheckDispatcher(VirtualPermissionCheckDispatchers::dispatch),
    enabledStateDispatcher: VirtualPackageEnabledStateDispatcher =
        VirtualPackageEnabledStateDispatcher(VirtualPackageEnabledStateDispatchers::dispatch)
) : PackageManager() {

    private val service = VirtualPackageService(
        snapshot = snapshot,
        runtimeUid = runtimeUid,
        packageSigningInfo = packageSigningInfo,
        permissionCheckDispatcher = permissionCheckDispatcher,
        enabledStateDispatcher = enabledStateDispatcher
    )

    init {
        require(runtimeUid > 0) { "runtimeUid must be a positive Android application UID" }
    }

    override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (service.handlesPackage(packageName)) {
            return service.getPackageInfo(packageName, VirtualPackageQueryFlags.fromInt(flags))
                ?: throw NameNotFoundException(packageName)
        }
        return base.getPackageInfo(packageName, flags)
    }

    override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo {
        if (service.handlesPackage(versionedPackage.packageName)) {
            return service.getPackageInfo(versionedPackage.packageName, VirtualPackageQueryFlags.fromInt(flags))
                ?: throw NameNotFoundException(versionedPackage.packageName)
        }
        return base.getPackageInfo(versionedPackage, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getPackageInfo(packageName: String, flags: PackageInfoFlags): PackageInfo {
        if (service.handlesPackage(packageName)) {
            return service.getPackageInfo(packageName, flags.value) ?: throw NameNotFoundException(packageName)
        }
        return base.getPackageInfo(packageName, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getPackageInfo(versionedPackage: VersionedPackage, flags: PackageInfoFlags): PackageInfo {
        if (service.handlesPackage(versionedPackage.packageName)) {
            return service.getPackageInfo(versionedPackage.packageName, flags.value)
                ?: throw NameNotFoundException(versionedPackage.packageName)
        }
        return base.getPackageInfo(versionedPackage, flags)
    }

    override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo {
        if (service.handlesPackage(packageName)) {
            return service.getApplicationInfo(packageName, VirtualPackageQueryFlags.fromInt(flags))
                ?: throw NameNotFoundException(packageName)
        }
        return base.getApplicationInfo(packageName, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getApplicationInfo(packageName: String, flags: ApplicationInfoFlags): ApplicationInfo {
        if (service.handlesPackage(packageName)) {
            return service.getApplicationInfo(packageName, flags.value) ?: throw NameNotFoundException(packageName)
        }
        return base.getApplicationInfo(packageName, flags)
    }

    override fun getActivityInfo(component: ComponentName, flags: Int): ActivityInfo {
        return virtualComponentInfo(component) {
            service.getActivityInfo(component, VirtualPackageQueryFlags.fromInt(flags))
        } ?: base.getActivityInfo(component, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getActivityInfo(component: ComponentName, flags: ComponentInfoFlags): ActivityInfo {
        return virtualComponentInfo(component) { service.getActivityInfo(component, flags.value) }
            ?: base.getActivityInfo(component, flags)
    }

    override fun getServiceInfo(component: ComponentName, flags: Int): ServiceInfo {
        return virtualComponentInfo(component) {
            service.getServiceInfo(component, VirtualPackageQueryFlags.fromInt(flags))
        } ?: base.getServiceInfo(component, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getServiceInfo(component: ComponentName, flags: ComponentInfoFlags): ServiceInfo {
        return virtualComponentInfo(component) { service.getServiceInfo(component, flags.value) }
            ?: base.getServiceInfo(component, flags)
    }

    override fun getReceiverInfo(component: ComponentName, flags: Int): ActivityInfo {
        return virtualComponentInfo(component) {
            service.getReceiverInfo(component, VirtualPackageQueryFlags.fromInt(flags))
        } ?: base.getReceiverInfo(component, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getReceiverInfo(component: ComponentName, flags: ComponentInfoFlags): ActivityInfo {
        return virtualComponentInfo(component) { service.getReceiverInfo(component, flags.value) }
            ?: base.getReceiverInfo(component, flags)
    }

    override fun getProviderInfo(component: ComponentName, flags: Int): ProviderInfo {
        return virtualComponentInfo(component) {
            service.getProviderInfo(component, VirtualPackageQueryFlags.fromInt(flags))
        } ?: base.getProviderInfo(component, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getProviderInfo(component: ComponentName, flags: ComponentInfoFlags): ProviderInfo {
        return virtualComponentInfo(component) { service.getProviderInfo(component, flags.value) }
            ?: base.getProviderInfo(component, flags)
    }

    override fun resolveContentProvider(authority: String, flags: Int): ProviderInfo? {
        return service.resolveContentProvider(authority, VirtualPackageQueryFlags.fromInt(flags))
            ?: base.resolveContentProvider(authority, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun resolveContentProvider(authority: String, flags: ComponentInfoFlags): ProviderInfo? =
        service.resolveContentProvider(authority, flags.value) ?: base.resolveContentProvider(authority, flags)

    override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> {
        service.queryIntentActivities(intent, VirtualPackageQueryFlags.fromInt(flags))
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return base.queryIntentActivities(intent, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun queryIntentActivities(intent: Intent, flags: ResolveInfoFlags): List<ResolveInfo> {
        service.queryIntentActivities(intent, flags.value).takeIf { it.isNotEmpty() }?.let { return it }
        return base.queryIntentActivities(intent, flags)
    }

    override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? {
        return service.resolveActivity(intent, VirtualPackageQueryFlags.fromInt(flags))
            ?: base.resolveActivity(intent, flags)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun resolveActivity(intent: Intent, flags: ResolveInfoFlags): ResolveInfo? =
        service.resolveActivity(intent, flags.value) ?: base.resolveActivity(intent, flags)

    override fun getLaunchIntentForPackage(packageName: String): Intent? {
        return service.getLaunchIntentForPackage(packageName) ?: base.getLaunchIntentForPackage(packageName)
    }

    override fun getInstalledPackages(flags: Int): List<PackageInfo> =
        service.getInstalledPackages(VirtualPackageQueryFlags.fromInt(flags)) + base.getInstalledPackages(flags)

    override fun getInstalledApplications(flags: Int): List<ApplicationInfo> =
        service.getInstalledApplications(VirtualPackageQueryFlags.fromInt(flags)) + base.getInstalledApplications(flags)

    override fun getApplicationLabel(info: ApplicationInfo): CharSequence {
        return service.getApplicationLabel(info) ?: base.getApplicationLabel(info)
    }

    override fun checkPermission(permissionName: String, packageName: String): Int {
        return service.checkPermission(permissionName, packageName)
            ?: base.checkPermission(permissionName, packageName)
    }

    @Suppress("unused")
    fun getPermissionControllerPackageName(): String? {
        return invokeBasePackageManagerMethod("getPermissionControllerPackageName") as? String
            ?: resolvePermissionControllerPackageName()
    }

    @Suppress("unused")
    fun buildRequestPermissionsIntent(permissions: Array<String>?): Intent {
        val delegated = invokeBasePackageManagerMethod(
            name = "buildRequestPermissionsIntent",
            parameterTypes = arrayOf(Array<String>::class.java),
            args = arrayOf(permissions)
        ) as? Intent
        if (delegated != null) return delegated

        return Intent(ACTION_REQUEST_PERMISSIONS).apply {
            putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, permissions ?: emptyArray())
            getPermissionControllerPackageName()?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
        }
    }

    @Suppress("unused")
    fun shouldShowRequestPermissionRationale(permissionName: String?): Boolean {
        return invokeBasePackageManagerMethod(
            name = "shouldShowRequestPermissionRationale",
            parameterTypes = arrayOf(String::class.java),
            args = arrayOf(permissionName)
        ) as? Boolean ?: false
    }

    @Suppress("unused")
    fun shouldShowRequestPermissionRationale(permissionName: String?, deviceId: Int): Boolean {
        return invokeBasePackageManagerMethod(
            name = "shouldShowRequestPermissionRationale",
            parameterTypes = arrayOf(String::class.java, Integer.TYPE),
            args = arrayOf(permissionName, deviceId)
        ) as? Boolean ?: shouldShowRequestPermissionRationale(permissionName)
    }

    /**
     * Hidden framework contract used by [PackageItemInfo.loadIcon]. It is absent from the
     * public SDK stub but abstract on the Android 16 runtime PackageManager class.
     */
    @Keep
    @Suppress("unused")
    fun loadItemIcon(itemInfo: PackageItemInfo, appInfo: ApplicationInfo?): Drawable =
        loadFrameworkItemIcon(
            methodName = "loadItemIcon",
            itemInfo = itemInfo,
            appInfo = appInfo,
            badgeFallback = true
        )

    /** Hidden companion contract used by [PackageItemInfo.loadUnbadgedIcon]. */
    @Keep
    @Suppress("unused")
    fun loadUnbadgedItemIcon(itemInfo: PackageItemInfo, appInfo: ApplicationInfo?): Drawable =
        loadFrameworkItemIcon(
            methodName = "loadUnbadgedItemIcon",
            itemInfo = itemInfo,
            appInfo = appInfo,
            badgeFallback = false
        )

    override fun addPackageToPreferred(packageName: String) = base.addPackageToPreferred(packageName)
    override fun addPermission(info: PermissionInfo): Boolean = base.addPermission(info)
    override fun addPermissionAsync(info: PermissionInfo): Boolean = base.addPermissionAsync(info)
    override fun addPreferredActivity(
        filter: IntentFilter,
        match: Int,
        set: Array<ComponentName?>?,
        activity: ComponentName
    ) = base.addPreferredActivity(filter, match, set, activity)
    override fun canRequestPackageInstalls(): Boolean = base.canRequestPackageInstalls()
    override fun canonicalToCurrentPackageNames(names: Array<String>): Array<String> = base.canonicalToCurrentPackageNames(names)
    override fun checkSignatures(uid1: Int, uid2: Int): Int {
        if (uid1 != runtimeUid && uid2 != runtimeUid) return base.checkSignatures(uid1, uid2)
        val first = signingInfoForUid(uid1)
        val second = signingInfoForUid(uid2)
        return signatureComparison(first, second)
    }

    override fun checkSignatures(packageName1: String, packageName2: String): Int {
        val firstVirtual = service.handlesPackage(packageName1)
        val secondVirtual = service.handlesPackage(packageName2)
        if (!firstVirtual && !secondVirtual) return base.checkSignatures(packageName1, packageName2)
        val first = signingInfoForPackage(packageName1, firstVirtual)
        val second = signingInfoForPackage(packageName2, secondVirtual)
        return signatureComparison(first, second)
    }

    override fun hasSigningCertificate(packageName: String, signingCertificate: ByteArray, flags: Int): Boolean {
        return service.hasSigningCertificate(packageName, signingCertificate, flags)
            ?: base.hasSigningCertificate(packageName, signingCertificate, flags)
    }

    override fun hasSigningCertificate(uid: Int, signingCertificate: ByteArray, flags: Int): Boolean {
        return service.hasRuntimeUidSigningCertificate(uid, signingCertificate, flags)
            ?: base.hasSigningCertificate(uid, signingCertificate, flags)
    }
    override fun clearInstantAppCookie() = base.clearInstantAppCookie()
    override fun clearPackagePreferredActivities(packageName: String) = base.clearPackagePreferredActivities(packageName)
    override fun currentToCanonicalPackageNames(names: Array<String>): Array<String> = base.currentToCanonicalPackageNames(names)
    override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsToDelay: Long) = base.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay)
    override fun getActivityBanner(activityName: ComponentName): Drawable = base.getActivityBanner(activityName) ?: defaultActivityIcon
    override fun getActivityBanner(intent: Intent): Drawable = base.getActivityBanner(intent) ?: defaultActivityIcon
    override fun getActivityIcon(activityName: ComponentName): Drawable = base.getActivityIcon(activityName) ?: defaultActivityIcon
    override fun getActivityIcon(intent: Intent): Drawable = base.getActivityIcon(intent) ?: defaultActivityIcon
    override fun getActivityLogo(activityName: ComponentName): Drawable = base.getActivityLogo(activityName) ?: defaultActivityIcon
    override fun getActivityLogo(intent: Intent): Drawable = base.getActivityLogo(intent) ?: defaultActivityIcon
    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> = base.getAllPermissionGroups(flags)
    override fun getApplicationBanner(info: ApplicationInfo): Drawable =
        virtualApplicationInfo(info)?.let { virtualInfo ->
            runCatching { base.getApplicationBanner(virtualInfo) }.getOrNull()
        } ?: base.getApplicationBanner(info) ?: defaultActivityIcon

    override fun getApplicationBanner(packageName: String): Drawable =
        virtualApplicationInfo(packageName)?.let { virtualInfo ->
            runCatching { base.getApplicationBanner(virtualInfo) }.getOrNull()
        } ?: base.getApplicationBanner(packageName) ?: defaultActivityIcon

    override fun getApplicationEnabledSetting(packageName: String): Int =
        service.getApplicationEnabledSetting(packageName)
            ?: base.getApplicationEnabledSetting(packageName)

    override fun getApplicationIcon(info: ApplicationInfo): Drawable =
        virtualApplicationInfo(info)?.let { virtualInfo ->
            runCatching { base.getApplicationIcon(virtualInfo) }.getOrNull()
        } ?: base.getApplicationIcon(info)

    override fun getApplicationIcon(packageName: String): Drawable =
        virtualApplicationInfo(packageName)?.let { virtualInfo ->
            runCatching { base.getApplicationIcon(virtualInfo) }.getOrNull()
        } ?: base.getApplicationIcon(packageName)

    override fun getApplicationLogo(info: ApplicationInfo): Drawable =
        virtualApplicationInfo(info)?.let { virtualInfo ->
            runCatching { base.getApplicationLogo(virtualInfo) }.getOrNull()
        } ?: base.getApplicationLogo(info) ?: defaultActivityIcon

    override fun getApplicationLogo(packageName: String): Drawable =
        virtualApplicationInfo(packageName)?.let { virtualInfo ->
            runCatching { base.getApplicationLogo(virtualInfo) }.getOrNull()
        } ?: base.getApplicationLogo(packageName) ?: defaultActivityIcon
    override fun getChangedPackages(sequenceNumber: Int): ChangedPackages? = base.getChangedPackages(sequenceNumber)
    override fun getComponentEnabledSetting(componentName: ComponentName): Int =
        service.getComponentEnabledSetting(componentName) ?: base.getComponentEnabledSetting(componentName)
    override fun getDefaultActivityIcon(): Drawable = base.getDefaultActivityIcon()
    override fun getDrawable(packageName: String, resid: Int, appInfo: ApplicationInfo?): Drawable? =
        virtualResourcesForPackage(packageName, appInfo)
            ?.let { resources -> runCatching { resources.getDrawable(resid, null) }.getOrNull() }
            ?: base.getDrawable(packageName, resid, appInfo)

    override fun getInstallerPackageName(packageName: String): String? =
        if (virtualApplicationInfo(packageName) != null) null else base.getInstallerPackageName(packageName)
    override fun getInstantAppCookie(): ByteArray = base.instantAppCookie
    override fun getInstantAppCookieMaxBytes(): Int = base.instantAppCookieMaxBytes
    override fun getInstrumentationInfo(className: ComponentName, flags: Int): InstrumentationInfo = base.getInstrumentationInfo(className, flags)
    override fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? =
        if (virtualApplicationInfo(packageName) != null) null else base.getLeanbackLaunchIntentForPackage(packageName)
    override fun getNameForUid(uid: Int): String? = service.getNameForUid(uid, runtimeUid) ?: base.getNameForUid(uid)
    override fun getPackageGids(packageName: String): IntArray =
        if (virtualApplicationInfo(packageName) != null) intArrayOf() else base.getPackageGids(packageName)

    override fun getPackageGids(packageName: String, flags: Int): IntArray =
        if (virtualApplicationInfo(packageName) != null) intArrayOf() else base.getPackageGids(packageName, flags)
    override fun getPackageInstaller(): PackageInstaller = base.packageInstaller
    override fun getPackageUid(packageName: String, flags: Int): Int = service.getPackageUid(packageName, runtimeUid) ?: base.getPackageUid(packageName, flags)
    override fun getPackagesForUid(uid: Int): Array<String>? = service.getPackagesForUid(uid, runtimeUid) ?: base.getPackagesForUid(uid)
    override fun getPackagesHoldingPermissions(permissions: Array<String>, flags: Int): List<PackageInfo> {
        val virtualPackages = service.getPackagesHoldingPermissions(
            permissions,
            VirtualPackageQueryFlags.fromInt(flags)
        )
        val basePackages = base.getPackagesHoldingPermissions(permissions, flags)
        return (virtualPackages + basePackages).distinctBy { it.packageName }
    }
    override fun getPermissionGroupInfo(name: String, flags: Int): PermissionGroupInfo = base.getPermissionGroupInfo(name, flags)
    override fun getPermissionInfo(name: String, flags: Int): PermissionInfo = base.getPermissionInfo(name, flags)
    override fun getPreferredActivities(outFilters: MutableList<IntentFilter>, outActivities: MutableList<ComponentName>, packageName: String?): Int = base.getPreferredActivities(outFilters, outActivities, packageName)
    override fun getPreferredPackages(flags: Int): List<PackageInfo> = base.getPreferredPackages(flags)
    override fun getResourcesForActivity(activityName: ComponentName): Resources = base.getResourcesForActivity(activityName)
    override fun getResourcesForApplication(app: ApplicationInfo): Resources {
        service.getApplicationInfoForResources(app)?.let { return base.getResourcesForApplication(it) }
        return base.getResourcesForApplication(app)
    }
    override fun getResourcesForApplication(appPackageName: String): Resources {
        service.getApplicationInfoForResources(appPackageName)?.let { return base.getResourcesForApplication(it) }
        return base.getResourcesForApplication(appPackageName)
    }
    override fun getSharedLibraries(flags: Int): List<SharedLibraryInfo> = base.getSharedLibraries(flags)
    override fun getSystemAvailableFeatures(): Array<FeatureInfo> = base.systemAvailableFeatures
    override fun getSystemSharedLibraryNames(): Array<String>? = base.systemSharedLibraryNames
    override fun getText(packageName: String, resid: Int, appInfo: ApplicationInfo?): CharSequence? =
        virtualResourcesForPackage(packageName, appInfo)
            ?.let { resources -> runCatching { resources.getText(resid) }.getOrNull() }
            ?: base.getText(packageName, resid, appInfo)
    override fun getUserBadgedDrawableForDensity(drawable: Drawable, user: UserHandle, badgeLocation: Rect?, badgeDensity: Int): Drawable = base.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity)
    override fun getUserBadgedIcon(icon: Drawable, user: UserHandle): Drawable = base.getUserBadgedIcon(icon, user)
    override fun getUserBadgedLabel(label: CharSequence, user: UserHandle): CharSequence = base.getUserBadgedLabel(label, user)
    override fun getXml(packageName: String, resid: Int, appInfo: ApplicationInfo?): XmlResourceParser? =
        virtualResourcesForPackage(packageName, appInfo)
            ?.let { resources -> runCatching { resources.getXml(resid) }.getOrNull() }
            ?: base.getXml(packageName, resid, appInfo)
    override fun hasSystemFeature(name: String): Boolean = base.hasSystemFeature(name)
    override fun hasSystemFeature(name: String, version: Int): Boolean = base.hasSystemFeature(name, version)
    override fun isInstantApp(): Boolean = base.isInstantApp
    override fun isInstantApp(packageName: String): Boolean = service.isInstantApp(packageName) ?: base.isInstantApp(packageName)
    override fun isPermissionRevokedByPolicy(permission: String, packageName: String): Boolean =
        if (virtualApplicationInfo(packageName) != null) false else base.isPermissionRevokedByPolicy(permission, packageName)
    override fun isSafeMode(): Boolean = base.isSafeMode
    override fun queryBroadcastReceivers(intent: Intent, flags: Int): List<ResolveInfo> =
        service.queryBroadcastReceivers(intent, VirtualPackageQueryFlags.fromInt(flags))
            .ifEmpty { base.queryBroadcastReceivers(intent, flags) }

    override fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> {
        val providers = service.queryContentProviders(
            processName,
            uid,
            runtimeUid,
            VirtualPackageQueryFlags.fromInt(flags)
        )
        if (uid == runtimeUid) return providers
        val baseProviders = base.queryContentProviders(processName, uid, flags)
        return (providers + baseProviders).distinctBy { "${it.authority}:${it.name}" }
    }
    override fun queryInstrumentation(targetPackage: String, flags: Int): List<InstrumentationInfo> = base.queryInstrumentation(targetPackage, flags)
    override fun queryIntentActivityOptions(caller: ComponentName?, specifics: Array<Intent>?, intent: Intent, flags: Int): List<ResolveInfo> = base.queryIntentActivityOptions(caller, specifics, intent, flags)
    override fun queryIntentContentProviders(intent: Intent, flags: Int): List<ResolveInfo> =
        service.queryIntentContentProviders(intent, VirtualPackageQueryFlags.fromInt(flags))
            .ifEmpty { base.queryIntentContentProviders(intent, flags) }

    override fun queryIntentServices(intent: Intent, flags: Int): List<ResolveInfo> =
        service.queryIntentServices(intent, VirtualPackageQueryFlags.fromInt(flags))
            .ifEmpty { base.queryIntentServices(intent, flags) }
    override fun queryPermissionsByGroup(group: String?, flags: Int): List<PermissionInfo> = base.queryPermissionsByGroup(group, flags)
    override fun removePackageFromPreferred(packageName: String) = base.removePackageFromPreferred(packageName)
    override fun removePermission(name: String) = base.removePermission(name)
    override fun resolveService(intent: Intent, flags: Int): ResolveInfo? =
        service.resolveService(intent, VirtualPackageQueryFlags.fromInt(flags)) ?: base.resolveService(intent, flags)
    override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) = base.setApplicationCategoryHint(packageName, categoryHint)
    override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) {
        if (service.setApplicationEnabledSetting(packageName, newState, flags)) return
        base.setApplicationEnabledSetting(packageName, newState, flags)
    }
    override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
        if (service.setComponentEnabledSetting(componentName, newState, flags)) return
        base.setComponentEnabledSetting(componentName, newState, flags)
    }
    override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) = base.setInstallerPackageName(targetPackage, installerPackageName)
    override fun updateInstantAppCookie(cookie: ByteArray?) = base.updateInstantAppCookie(cookie)
    override fun verifyPendingInstall(id: Int, verificationCode: Int) = base.verifyPendingInstall(id, verificationCode)

    private fun resolvePermissionControllerPackageName(): String? {
        val requestIntent = Intent(ACTION_REQUEST_PERMISSIONS)
        return runCatching {
            base.resolveActivity(requestIntent, 0)?.activityInfo?.packageName
        }.getOrNull()
    }

    private fun virtualApplicationInfo(packageName: String?): ApplicationInfo? =
        packageName?.let { service.getApplicationInfo(it) }

    private fun virtualApplicationInfo(info: ApplicationInfo?): ApplicationInfo? =
        info?.let { service.getApplicationInfoForResources(it) }

    private fun virtualResourcesForPackage(
        packageName: String?,
        appInfo: ApplicationInfo?
    ): Resources? {
        val virtualInfo = virtualApplicationInfo(appInfo)
            ?: virtualApplicationInfo(packageName)
            ?: return null
        return runCatching { base.getResourcesForApplication(virtualInfo) }.getOrNull()
    }

    private fun loadFrameworkItemIcon(
        methodName: String,
        itemInfo: PackageItemInfo,
        appInfo: ApplicationInfo?,
        badgeFallback: Boolean
    ): Drawable {
        val virtualAppInfo = virtualApplicationInfo(appInfo)
            ?: (itemInfo as? ApplicationInfo)?.let(::virtualApplicationInfo)
            ?: virtualApplicationInfo(itemInfo.packageName)
        val delegatedAppInfo = virtualAppInfo ?: appInfo
        val delegatedItemInfo = if (itemInfo is ApplicationInfo && virtualAppInfo != null) {
            virtualAppInfo
        } else {
            itemInfo
        }
        val delegated = invokeBasePackageManagerMethod(
            name = methodName,
            parameterTypes = arrayOf(PackageItemInfo::class.java, ApplicationInfo::class.java),
            args = arrayOf(delegatedItemInfo, delegatedAppInfo)
        ) as? Drawable
        if (delegated != null) return delegated

        val fallback = itemInfo.packageName
            ?.let { packageName -> getDrawable(packageName, itemInfo.icon, delegatedAppInfo) }
            ?: delegatedAppInfo?.let { info -> runCatching { getApplicationIcon(info) }.getOrNull() }
            ?: defaultActivityIcon
        if (!badgeFallback) return fallback
        return runCatching { base.getUserBadgedIcon(fallback, Process.myUserHandle()) }
            .getOrNull()
            ?: fallback
    }

    private inline fun <T> virtualComponentInfo(component: ComponentName, query: () -> T?): T? {
        if (!service.handlesPackage(component.packageName)) return null
        return query() ?: throw NameNotFoundException(component.flattenToShortString())
    }

    private fun signingInfoForPackage(
        packageName: String,
        virtualPackage: Boolean
    ): VirtualPackageSigningInfo? = if (virtualPackage) {
        service.signingInfoForPackage(packageName)
    } else {
        hostPackageSigningInfo(packageName)
    }

    private fun signingInfoForUid(uid: Int): VirtualPackageSigningInfo? {
        if (uid == runtimeUid) return service.signingInfoForRuntimeUid(uid)
        val packages = runCatching { base.getPackagesForUid(uid)?.toList().orEmpty() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val signingInfos = packages.map { packageName ->
            hostPackageSigningInfo(packageName) ?: return null
        }
        val first = signingInfos.firstOrNull() ?: return null
        return first.takeIf { candidate -> signingInfos.all(candidate::matches) }
    }

    @Suppress("DEPRECATION")
    private fun hostPackageSigningInfo(packageName: String): VirtualPackageSigningInfo? = runCatching {
        base.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        )
    }.getOrNull()?.let(VirtualPackageArchiveSigningResolver::fromPackageInfo)

    private fun signatureComparison(
        first: VirtualPackageSigningInfo?,
        second: VirtualPackageSigningInfo?
    ): Int = if (first?.matches(second) == true) {
        PackageManager.SIGNATURE_MATCH
    } else {
        PackageManager.SIGNATURE_NO_MATCH
    }

    private fun invokeBasePackageManagerMethod(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray()
    ): Any? {
        var current: Class<*>? = base.javaClass
        while (current != null) {
            val method = runCatching { current.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(base, *args)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private companion object {
        const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
        const val EXTRA_REQUEST_PERMISSIONS_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"
    }
}

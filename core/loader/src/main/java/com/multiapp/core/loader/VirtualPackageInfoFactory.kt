package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

internal object VirtualPackageInfoFactory {

    fun applicationInfo(snapshot: VirtualPackageSnapshot): ApplicationInfo = ApplicationInfo().apply {
        packageName = snapshot.originPackageName
        className = snapshot.applicationClassName
        name = snapshot.applicationClassName
        sourceDir = snapshot.sourceDir
        publicSourceDir = snapshot.publicSourceDir
        if (snapshot.splitSourceDirs.isNotEmpty()) {
            splitSourceDirs = snapshot.splitSourceDirs.toTypedArray()
        }
        val splitPublicDirs = snapshot.splitPublicSourceDirs.ifEmpty { snapshot.splitSourceDirs }
        if (splitPublicDirs.isNotEmpty()) {
            splitPublicSourceDirs = splitPublicDirs.toTypedArray()
        }
        if (snapshot.splitNames.isNotEmpty()) {
            splitNames = snapshot.splitNames.toTypedArray()
        }
        dataDir = snapshot.dataDir
        ApplicationInfoNativePathCompat.applyTo(this, snapshot.dataDir, snapshot.nativeLibraryDir)
        minSdkVersion = snapshot.minSdk
        targetSdkVersion = snapshot.targetSdk
        nonLocalizedLabel = snapshot.applicationLabel
        processName = snapshot.processName
        taskAffinity = snapshot.taskAffinity
        theme = snapshot.themeId
        metaData = snapshot.metaData.toBundle()
        enabled = true
    }

    fun packageInfo(snapshot: VirtualPackageSnapshot): PackageInfo = PackageInfo().apply {
        packageName = snapshot.originPackageName
        versionCode = snapshot.versionCode.toInt()
        versionName = snapshot.versionName
        applicationInfo = applicationInfo(snapshot)
        requestedPermissions = snapshot.permissions.toTypedArray()
        activities = snapshot.activities.map { activityInfo(snapshot, it) }.toTypedArray()
        services = snapshot.services.map { serviceInfo(snapshot, it) }.toTypedArray()
        receivers = snapshot.receivers.map { receiverInfo(snapshot, it) }.toTypedArray()
        providers = snapshot.providers.mapNotNull { providerInfo(snapshot, it) }.toTypedArray()
    }

    fun activityInfo(snapshot: VirtualPackageSnapshot, component: ResolvedComponent): ActivityInfo =
        ActivityInfo().apply {
            packageName = snapshot.originPackageName
            name = component.name
            exported = component.exported
            applicationInfo = applicationInfo(snapshot)
            enabled = true
            launchMode = toActivityInfoLaunchMode(component.launchMode)
            processName = component.processName
            taskAffinity = component.taskAffinity
            theme = component.themeId.takeIf { it != 0 } ?: snapshot.themeId
            screenOrientation = toActivityInfoScreenOrientation(component.screenOrientation)
            configChanges = toActivityInfoConfigChanges(component.configChanges)
            permission = component.permission
            metaData = component.metaData.toBundle()
            targetActivity = component.targetActivityName
        }

    fun receiverInfo(snapshot: VirtualPackageSnapshot, component: ResolvedComponent): ActivityInfo =
        activityInfo(snapshot, component)

    fun serviceInfo(snapshot: VirtualPackageSnapshot, component: ResolvedComponent): ServiceInfo =
        ServiceInfo().apply {
            packageName = snapshot.originPackageName
            name = component.name
            exported = component.exported
            applicationInfo = applicationInfo(snapshot)
            enabled = true
            processName = component.processName
            permission = component.permission
            metaData = component.metaData.toBundle()
        }

    fun providerInfo(snapshot: VirtualPackageSnapshot, component: ResolvedComponent): ProviderInfo? {
        val authority = component.authorities.firstOrNull() ?: return null
        return ProviderInfo().apply {
            packageName = snapshot.originPackageName
            name = component.name
            exported = component.exported
            applicationInfo = applicationInfo(snapshot)
            enabled = true
            this.authority = authority
            processName = component.processName
            readPermission = component.permission
            writePermission = component.permission
            grantUriPermissions = component.grantUriPermissions
            metaData = component.metaData.toBundle()
        }
    }

    fun launcherResolveInfo(snapshot: VirtualPackageSnapshot): ResolveInfo? {
        val launcherName = snapshot.launcherActivityName
            ?: snapshot.activities.resolveLauncherIntentActivityName()
            ?: return null
        val component = snapshot.activities.firstOrNull {
            it.name == launcherName || it.targetActivityName == launcherName
        }
            ?: ResolvedComponent(
                name = launcherName,
                exported = true,
                intentFilters = listOf(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER)
            )
        return ResolveInfo().apply {
            activityInfo = activityInfo(snapshot, component)
        }
    }

    fun findActivity(snapshot: VirtualPackageSnapshot, componentName: ComponentName): ActivityInfo? {
        if (!snapshot.matchesPackageName(componentName.packageName)) return null
        return findActivity(snapshot, componentName.className)
    }

    internal fun findActivity(snapshot: VirtualPackageSnapshot, className: String): ActivityInfo? =
        snapshot.activities.firstOrNull {
            it.name == className || it.targetActivityName == className
        }?.let { activityInfo(snapshot, it) }

    fun findService(snapshot: VirtualPackageSnapshot, componentName: ComponentName): ServiceInfo? {
        if (!snapshot.matchesPackageName(componentName.packageName)) return null
        return snapshot.services.firstOrNull { it.name == componentName.className }
            ?.let { serviceInfo(snapshot, it) }
    }

    fun findReceiver(snapshot: VirtualPackageSnapshot, componentName: ComponentName): ActivityInfo? {
        if (!snapshot.matchesPackageName(componentName.packageName)) return null
        return snapshot.receivers.firstOrNull { it.name == componentName.className }
            ?.let { receiverInfo(snapshot, it) }
    }

    fun findProvider(snapshot: VirtualPackageSnapshot, authority: String): ProviderInfo? =
        snapshot.providers.firstOrNull { authority in it.authorities }
            ?.let { providerInfo(snapshot, it) }

    private fun toActivityInfoLaunchMode(launchMode: String?): Int = when (launchMode) {
        "singleTop" -> ActivityInfo.LAUNCH_SINGLE_TOP
        "singleTask" -> ActivityInfo.LAUNCH_SINGLE_TASK
        "singleInstance" -> ActivityInfo.LAUNCH_SINGLE_INSTANCE
        "singleInstancePerTask" -> 4
        else -> ActivityInfo.LAUNCH_MULTIPLE
    }

    private fun toActivityInfoScreenOrientation(screenOrientation: String?): Int = when (screenOrientation) {
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "user" -> ActivityInfo.SCREEN_ORIENTATION_USER
        "behind" -> ActivityInfo.SCREEN_ORIENTATION_BEHIND
        "sensor" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        "nosensor" -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        "sensorLandscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        "sensorPortrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        "reverseLandscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        "reversePortrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        "fullSensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        "userLandscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        "userPortrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        "fullUser" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        "locked" -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun toActivityInfoConfigChanges(configChanges: String?): Int =
        configChanges?.split('|')
            ?.map { it.trim() }
            ?.fold(0) { flags, token -> flags or toActivityInfoConfigChange(token) }
            ?: 0

    private fun toActivityInfoConfigChange(token: String): Int = when (token) {
        "mcc" -> ActivityInfo.CONFIG_MCC
        "mnc" -> ActivityInfo.CONFIG_MNC
        "locale" -> ActivityInfo.CONFIG_LOCALE
        "touchscreen" -> ActivityInfo.CONFIG_TOUCHSCREEN
        "keyboard" -> ActivityInfo.CONFIG_KEYBOARD
        "keyboardHidden" -> ActivityInfo.CONFIG_KEYBOARD_HIDDEN
        "navigation" -> ActivityInfo.CONFIG_NAVIGATION
        "orientation" -> ActivityInfo.CONFIG_ORIENTATION
        "screenLayout" -> ActivityInfo.CONFIG_SCREEN_LAYOUT
        "uiMode" -> ActivityInfo.CONFIG_UI_MODE
        "screenSize" -> ActivityInfo.CONFIG_SCREEN_SIZE
        "smallestScreenSize" -> ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
        "density" -> ActivityInfo.CONFIG_DENSITY
        "colorMode" -> ActivityInfo.CONFIG_COLOR_MODE
        else -> 0
    }

    private fun Map<String, String>.toBundle(): Bundle =
        Bundle(size).apply {
            forEach { (key, value) -> putString(key, value) }
        }
}

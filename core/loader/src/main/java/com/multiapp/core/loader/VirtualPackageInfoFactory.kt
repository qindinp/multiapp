package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.PathPermission
import android.os.PatternMatcher
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import com.multiapp.core.model.virtual.VirtualMetaDataValueType
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

internal object VirtualPackageInfoFactory {

    fun applicationInfo(
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int,
        flags: Long
    ): ApplicationInfo = ApplicationInfo().apply {
        uid = requireRuntimeUid(runtimeUid)
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
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_META_DATA)) {
            metaData = snapshot.metaData.toBundle(snapshot.typedMetaData)
        }
        enabled = true
        this.flags = ApplicationInfo.FLAG_INSTALLED
        if (snapshot.debuggable) {
            this.flags = this.flags or ApplicationInfo.FLAG_DEBUGGABLE
        }
        writeIntField(this, "privateFlags", 0)
    }

    @Suppress("DEPRECATION")
    fun packageInfo(
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int,
        flags: Long,
        packageSigningInfo: VirtualPackageSigningInfo? = null
    ): PackageInfo = PackageInfo().apply {
        packageName = snapshot.originPackageName
        versionCode = snapshot.versionCode.toInt()
        versionName = snapshot.versionName
        applicationInfo = applicationInfo(snapshot, runtimeUid, flags)
        sharedUserId = snapshot.sharedUserId
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_PERMISSIONS)) {
            requestedPermissions = snapshot.permissions.toTypedArray()
        }
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_ACTIVITIES)) {
            activities = snapshot.activities.map { activityInfo(snapshot, it, runtimeUid, flags) }.toTypedArray()
        }
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_SERVICES)) {
            services = snapshot.services.map { serviceInfo(snapshot, it, runtimeUid, flags) }.toTypedArray()
        }
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_RECEIVERS)) {
            receivers = snapshot.receivers.map { receiverInfo(snapshot, it, runtimeUid, flags) }.toTypedArray()
        }
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_PROVIDERS)) {
            providers = snapshot.providers.mapNotNull {
                providerInfo(snapshot, it, runtimeUid, flags)
            }.toTypedArray()
        }
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_SIGNATURES)) {
            packageSigningInfo?.let { signing -> signatures = signing.legacySignatures.copyOf() }
        }
        if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_SIGNING_CERTIFICATES)) {
            packageSigningInfo?.let { signing -> signingInfo = signing.signingInfo }
        }
    }

    fun activityInfo(
        snapshot: VirtualPackageSnapshot,
        component: ResolvedComponent,
        runtimeUid: Int,
        flags: Long
    ): ActivityInfo =
        ActivityInfo().apply {
            packageName = snapshot.originPackageName
            name = component.name
            exported = component.exported
            applicationInfo = applicationInfo(snapshot, runtimeUid, flags)
            enabled = component.enabled
            launchMode = toActivityInfoLaunchMode(component.launchMode)
            processName = component.processName
            taskAffinity = component.taskAffinity
            theme = component.themeId.takeIf { it != 0 } ?: snapshot.themeId
            screenOrientation = toActivityInfoScreenOrientation(component.screenOrientation)
            configChanges = toActivityInfoConfigChanges(component.configChanges)
            permission = component.permission
            if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_META_DATA)) {
                metaData = component.metaData.toBundle(component.typedMetaData)
            }
            targetActivity = component.targetActivityName
        }

    fun receiverInfo(
        snapshot: VirtualPackageSnapshot,
        component: ResolvedComponent,
        runtimeUid: Int,
        flags: Long
    ): ActivityInfo = activityInfo(snapshot, component, runtimeUid, flags)

    fun serviceInfo(
        snapshot: VirtualPackageSnapshot,
        component: ResolvedComponent,
        runtimeUid: Int,
        flags: Long
    ): ServiceInfo =
        ServiceInfo().apply {
            packageName = snapshot.originPackageName
            name = component.name
            exported = component.exported
            applicationInfo = applicationInfo(snapshot, runtimeUid, flags)
            enabled = component.enabled
            processName = component.processName
            permission = component.permission
            if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_META_DATA)) {
                metaData = component.metaData.toBundle(component.typedMetaData)
            }
        }

    fun providerInfo(
        snapshot: VirtualPackageSnapshot,
        component: ResolvedComponent,
        runtimeUid: Int,
        flags: Long
    ): ProviderInfo? {
        val authority = component.authorities.filter { it.isNotBlank() }.joinToString(";")
            .takeIf { it.isNotBlank() }
            ?: return null
        return ProviderInfo().apply {
            packageName = snapshot.originPackageName
            name = component.name
            exported = component.exported
            applicationInfo = applicationInfo(snapshot, runtimeUid, flags)
            enabled = component.enabled
            this.authority = authority
            processName = component.processName
            readPermission = component.readPermission ?: component.permission
            writePermission = component.writePermission ?: component.permission
            grantUriPermissions = component.grantUriPermissions || component.uriPermissionPatterns.isNotEmpty()
            if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_URI_PERMISSION_PATTERNS)) {
                pathPermissions = component.pathPermissions.map { permission ->
                    PathPermission(
                        permission.pattern.path,
                        permission.pattern.type.toAndroidPatternType(),
                        permission.readPermission,
                        permission.writePermission
                    )
                }.toTypedArray().takeIf { it.isNotEmpty() }
                uriPermissionPatterns = component.uriPermissionPatterns.map { pattern ->
                    PatternMatcher(pattern.path, pattern.type.toAndroidPatternType())
                }.toTypedArray().takeIf { it.isNotEmpty() }
            }
            if (VirtualPackageQueryFlags.includes(flags, PackageManager.GET_META_DATA)) {
                metaData = component.metaData.toBundle(component.typedMetaData)
            }
        }
    }

    private fun com.multiapp.core.model.virtual.VirtualProviderPathPatternType.toAndroidPatternType(): Int =
        when (this) {
            com.multiapp.core.model.virtual.VirtualProviderPathPatternType.LITERAL -> PatternMatcher.PATTERN_LITERAL
            com.multiapp.core.model.virtual.VirtualProviderPathPatternType.PREFIX -> PatternMatcher.PATTERN_PREFIX
            com.multiapp.core.model.virtual.VirtualProviderPathPatternType.SIMPLE_GLOB -> PatternMatcher.PATTERN_SIMPLE_GLOB
            com.multiapp.core.model.virtual.VirtualProviderPathPatternType.ADVANCED_GLOB -> PatternMatcher.PATTERN_ADVANCED_GLOB
            com.multiapp.core.model.virtual.VirtualProviderPathPatternType.SUFFIX -> PatternMatcher.PATTERN_SUFFIX
        }

    fun launcherResolveInfo(
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int,
        flags: Long
    ): ResolveInfo? {
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
            activityInfo = activityInfo(snapshot, component, runtimeUid, flags)
        }
    }

    fun findActivity(
        snapshot: VirtualPackageSnapshot,
        componentName: ComponentName,
        runtimeUid: Int,
        flags: Long
    ): ActivityInfo? {
        if (!snapshot.matchesPackageName(componentName.packageName)) return null
        return findActivity(snapshot, componentName.className, runtimeUid, flags)
    }

    internal fun findActivity(
        snapshot: VirtualPackageSnapshot,
        className: String,
        runtimeUid: Int,
        flags: Long
    ): ActivityInfo? =
        snapshot.activities.firstOrNull {
            it.name == className || it.targetActivityName == className
        }?.let { activityInfo(snapshot, it, runtimeUid, flags) }

    fun findService(
        snapshot: VirtualPackageSnapshot,
        componentName: ComponentName,
        runtimeUid: Int,
        flags: Long
    ): ServiceInfo? {
        if (!snapshot.matchesPackageName(componentName.packageName)) return null
        return snapshot.services.firstOrNull { it.name == componentName.className }
            ?.let { serviceInfo(snapshot, it, runtimeUid, flags) }
    }

    fun findReceiver(
        snapshot: VirtualPackageSnapshot,
        componentName: ComponentName,
        runtimeUid: Int,
        flags: Long
    ): ActivityInfo? {
        if (!snapshot.matchesPackageName(componentName.packageName)) return null
        return snapshot.receivers.firstOrNull { it.name == componentName.className }
            ?.let { receiverInfo(snapshot, it, runtimeUid, flags) }
    }

    fun findProvider(
        snapshot: VirtualPackageSnapshot,
        authority: String,
        runtimeUid: Int,
        flags: Long
    ): ProviderInfo? =
        snapshot.providers.firstOrNull { authority in it.authorities }
            ?.let { providerInfo(snapshot, it, runtimeUid, flags) }

    private fun requireRuntimeUid(runtimeUid: Int): Int {
        require(runtimeUid > 0) { "runtimeUid must be a positive Android application UID" }
        return runtimeUid
    }

    private fun writeIntField(target: Any, fieldName: String, value: Int) {
        runCatching {
            target.javaClass.getField(fieldName).setInt(target, value)
        }
    }

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

    private fun Map<String, String>.toBundle(
        typedValues: Map<String, VirtualMetaDataValue>
    ): Bundle =
        Bundle(size + typedValues.size).apply {
            this@toBundle.forEach { (key, value) -> putString(key, value) }
            typedValues.forEach { (key, value) -> value.putInto(this, key) }
        }

    private fun VirtualMetaDataValue.putInto(bundle: Bundle, key: String) {
        when (val value = toPlatformMetaDataValue()) {
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }
}

internal object VirtualPackageQueryFlags {
    const val NONE: Long = 0L

    val INTERNAL_FULL: Long = listOf(
        PackageManager.GET_ACTIVITIES,
        PackageManager.GET_RECEIVERS,
        PackageManager.GET_SERVICES,
        PackageManager.GET_PROVIDERS,
        PackageManager.GET_PERMISSIONS,
        PackageManager.GET_META_DATA,
        PackageManager.GET_SIGNATURES,
        PackageManager.GET_SIGNING_CERTIFICATES,
        PackageManager.GET_URI_PERMISSION_PATTERNS
    ).fold(0L) { result, flag -> result or fromInt(flag) }

    fun fromInt(flags: Int): Long = flags.toLong() and 0xffff_ffffL

    fun includes(flags: Long, requested: Int): Boolean = flags and fromInt(requested) != 0L
}

internal fun VirtualMetaDataValue.toPlatformMetaDataValue(): Any = when (type) {
    VirtualMetaDataValueType.STRING -> encodedValue
    VirtualMetaDataValueType.BOOLEAN -> encodedValue.toBooleanStrictOrNull() ?: encodedValue
    VirtualMetaDataValueType.INT -> encodedValue.toIntOrNull() ?: encodedValue
    VirtualMetaDataValueType.LONG -> encodedValue.toLongOrNull() ?: encodedValue
    VirtualMetaDataValueType.FLOAT -> encodedValue.toFloatOrNull() ?: encodedValue
    VirtualMetaDataValueType.DOUBLE -> encodedValue.toDoubleOrNull() ?: encodedValue
    VirtualMetaDataValueType.RESOURCE -> encodedValue.toResourceIdOrNull() ?: encodedValue
}

private fun String.toResourceIdOrNull(): Int? = when {
    startsWith("@0x", ignoreCase = true) -> substring(3).toLongOrNull(16)?.toInt()
    startsWith("0x", ignoreCase = true) -> substring(2).toLongOrNull(16)?.toInt()
    else -> toIntOrNull()
}

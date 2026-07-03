package com.multiapp.core.loader

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File

internal object HostedActivityContextInjector {

    private const val TAG = "HostedActivityCtx"

    data class InjectionResult(
        val contextInjected: Boolean,
        val applicationInjected: Boolean,
        val dataDir: String,
        val packageName: String,
        val applicationClassName: String?,
        val originPackageName: String,
        val virtualPackageName: String,
        val activityInfoPackageName: String?,
        val applicationInfoPackageName: String?,
        val loadedApkTargetClassName: String? = null,
        val loadedApkPatchedFields: List<String> = emptyList(),
        val loadedApkSkippedFieldReasons: List<String> = emptyList(),
        val loadedApkInstalledAliasCount: Int = 0,
        val loadedApkInstalledAliasesByField: Map<String, List<String>> = emptyMap(),
        val loadedApkAliasSkippedReasonsByField: Map<String, String> = emptyMap(),
        val loadedApkSkippedReason: String? = null,
        val loadedApkSource: String? = null,
        val activityRecordPatchedFields: List<String> = emptyList(),
        val activityRecordSkippedReason: String? = null,
        val appCompatThemeGuardApplied: Boolean = false,
        val appCompatThemeResourceId: Int = 0
    )

    fun inject(
        activity: Activity,
        hostContext: Context,
        hostPackageName: String?,
        config: VirtualContextConfig,
        guestApplication: Application?,
        guestClassLoader: ClassLoader
    ): InjectionResult {
        val guestContext = VirtualContextWrappers.create(
            base = hostContext,
            config = config,
            guestClassLoader = guestClassLoader
        )

        val runtimeApplicationInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = guestContext.applicationInfo
        )
        val appCompatThemeGuard = applyHostAppCompatThemeGuardIfNeeded(activity, hostContext)
        val contextInjected = replaceBaseContext(activity, guestContext)
        val applicationInjected = guestApplication?.let { replaceApplication(activity, it) } ?: false
        replaceFieldIfPresent(activity, "mResources", guestContext.resources)
        val loadedApkPatch = patchLoadedApkIfPresent(
            activity = activity,
            hostPackageName = hostPackageName,
            guestContext = guestContext,
            config = config,
            applicationInfo = runtimeApplicationInfo,
            guestClassLoader = guestClassLoader
        )
        val activityRecordPatch = patchActivityClientRecordIfPresent(
            activity = activity,
            config = config,
            applicationInfo = runtimeApplicationInfo,
            loadedApk = loadedApkPatch?.loadedApk
        )

        return InjectionResult(
            contextInjected = contextInjected,
            applicationInjected = applicationInjected,
            dataDir = config.dataDir,
            packageName = config.virtualPackageName,
            applicationClassName = guestApplication?.javaClass?.name,
            originPackageName = config.originPackageName,
            virtualPackageName = config.virtualPackageName,
            activityInfoPackageName = activityRecordPatch.activityInfoPackageName,
            applicationInfoPackageName = activityRecordPatch.applicationInfoPackageName,
            loadedApkTargetClassName = loadedApkPatch?.targetClassName,
            loadedApkPatchedFields = loadedApkPatch?.patchResult?.patchedFields.orEmpty(),
            loadedApkSkippedFieldReasons = loadedApkPatch?.patchResult?.skippedFieldReasons.orEmpty(),
            loadedApkInstalledAliasCount = loadedApkPatch?.installedAliasCount ?: 0,
            loadedApkInstalledAliasesByField = loadedApkPatch?.installedAliasesByField.orEmpty(),
            loadedApkAliasSkippedReasonsByField = loadedApkPatch?.skippedAliasInstallReasonsByField.orEmpty(),
            loadedApkSkippedReason = loadedApkPatch?.skippedReason,
            loadedApkSource = loadedApkPatch?.source?.name,
            activityRecordPatchedFields = activityRecordPatch.patchResult?.patchedFields.orEmpty(),
            activityRecordSkippedReason = activityRecordPatch.patchResult?.skippedReason,
            appCompatThemeGuardApplied = appCompatThemeGuard.applied,
            appCompatThemeResourceId = appCompatThemeGuard.themeResourceId
        )
    }

    private fun applyHostAppCompatThemeGuardIfNeeded(
        activity: Activity,
        hostContext: Context
    ): AppCompatThemeGuardResult {
        if (!isAppCompatActivity(activity)) return AppCompatThemeGuardResult(applied = false)
        val themeId = resolveHostProxyTheme(hostContext)
        if (themeId == 0) return AppCompatThemeGuardResult(applied = false)
        return runCatching {
            activity.setTheme(themeId)
            activity.theme
            replaceFieldIfPresent(activity, "mThemeResource", themeId)
            AppCompatThemeGuardResult(applied = true, themeResourceId = themeId)
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply host AppCompat theme guard: ${activity.javaClass.name}", error)
        }.getOrDefault(AppCompatThemeGuardResult(applied = false, themeResourceId = themeId))
    }

    private fun resolveHostProxyTheme(hostContext: Context): Int {
        val resources = hostContext.resources
        val packageName = hostContext.packageName
        val hostStyle = runCatching {
            resources.getIdentifier("Theme.MultiApp.Proxy", "style", packageName)
        }.getOrDefault(0)
        if (hostStyle != 0) return hostStyle

        return runCatching {
            Class.forName("androidx.appcompat.R\$style")
                .getField("Theme_AppCompat_Light_NoActionBar")
                .getInt(null)
        }.getOrDefault(0)
    }

    private fun isAppCompatActivity(activity: Activity): Boolean {
        var current: Class<*>? = activity.javaClass
        while (current != null) {
            if (current.name == "androidx.appcompat.app.AppCompatActivity" ||
                current.name.contains("AppCompatActivity")) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun patchActivityClientRecordIfPresent(
        activity: Activity,
        config: VirtualContextConfig,
        applicationInfo: android.content.pm.ApplicationInfo,
        loadedApk: Any?
    ): ActivityRecordInjectionResult {
        val guestActivityClassName = activity.intent?.getStringExtra("multiapp.guestActivityClassName")
            ?.takeIf { it.isNotBlank() }
            ?: activity.javaClass.name
        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = guestActivityClassName,
            applicationInfo = applicationInfo
        )
        val guestIntent = Intent(activity.intent).apply {
            component = ComponentName(config.originPackageName, guestActivityClassName)
            setPackage(config.originPackageName)
        }
        val patchResult = runCatching {
            ActivityClientRecordBridge.patchCurrentActivityRecord(
                activityThread = ActivityThreadCompat.currentActivityThread(),
                activity = activity,
                state = ActivityClientRecordRuntimeState(
                    activityInfo = activityInfo,
                    intent = guestIntent,
                    loadedApk = loadedApk
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to patch ActivityClientRecord: ${activity.javaClass.name}", error)
        }.getOrNull()
        return ActivityRecordInjectionResult(
            patchResult = patchResult,
            activityInfoPackageName = activityInfo.packageName,
            applicationInfoPackageName = activityInfo.applicationInfo?.packageName
        )
    }

    private fun patchLoadedApkIfPresent(
        activity: Activity,
        hostPackageName: String?,
        guestContext: VirtualContextWrapper,
        config: VirtualContextConfig,
        applicationInfo: android.content.pm.ApplicationInfo,
        guestClassLoader: ClassLoader
    ): ActivityThreadLoadedApkInstallResult? {
        val state = LoadedApkRuntimeState(
            packageName = config.virtualPackageName,
            applicationInfo = applicationInfo,
            resources = guestContext.resources,
            classLoader = guestClassLoader
        )
        val aliases = listOf(config.originPackageName, config.virtualPackageName)
        val activityThread = ActivityThreadCompat.currentActivityThread()
        var sandboxFailureClassName: String? = null

        runCatching {
            return ActivityThreadLoadedApkInstaller.installGuestSandbox(
                activityThread = activityThread,
                state = state,
                packageAliases = aliases
            )
        }.onFailure { error ->
            sandboxFailureClassName = error.javaClass.simpleName
            Log.w(TAG, "Guest LoadedApk sandbox creation failed; falling back to existing LoadedApk patch", error)
        }

        val loadedApk = findLoadedApk(activity) ?: return ActivityThreadLoadedApkInstaller.skippedInstallResult(
            targetClassName = "",
            packageAliases = aliases,
            skippedReason = "LOADED_APK_TARGET_NOT_FOUND_AFTER_GUEST_SANDBOX_FAILED:${sandboxFailureClassName.orEmpty()}"
        )
        val guardPackageName = hostPackageName
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it == config.originPackageName || it == config.virtualPackageName }
            ?: return ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason = "HOST_PACKAGE_GUARD_UNAVAILABLE_AFTER_GUEST_SANDBOX_FAILED:${sandboxFailureClassName.orEmpty()}"
            )
        return runCatching {
            ActivityThreadLoadedApkInstaller.install(
                activityThread = activityThread,
                loadedApk = loadedApk,
                state = state,
                packageAliases = aliases,
                hostPackageName = guardPackageName
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to install ActivityThread LoadedApk aliases", error)
        }.getOrElse { error ->
            ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason = "EXISTING_LOADED_APK_PATCH_FAILED:${error.javaClass.simpleName}"
            )
        }
    }

    private fun findLoadedApk(activity: Activity): Any? =
        findFieldInHierarchy(Activity::class.java, "mLoadedApk")
            ?.let { field -> runCatching { field.get(activity) }.getOrNull() }
            ?: findFieldInHierarchy(Activity::class.java, "mPackageInfo")
                ?.let { field -> runCatching { field.get(activity) }.getOrNull() }

    private fun replaceBaseContext(activity: Activity, context: Context): Boolean {
        return runCatching {
            val field = findFieldInHierarchy(ContextWrapper::class.java, "mBase")
                ?: error("ContextWrapper.mBase not found")
            field.set(activity, context)
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to replace Activity base context: ${activity.javaClass.name}", error)
        }.getOrDefault(false)
    }

    private fun replaceApplication(activity: Activity, application: Application): Boolean {
        return runCatching {
            val field = findFieldInHierarchy(Activity::class.java, "mApplication")
                ?: error("Activity.mApplication not found")
            field.set(activity, application)
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to replace Activity application: ${activity.javaClass.name}", error)
        }.getOrDefault(false)
    }

    private fun replaceFieldIfPresent(target: Any, name: String, value: Any?) {
        runCatching {
            findFieldInHierarchy(target.javaClass, name)?.set(target, value)
        }.onFailure { error ->
            Log.d(TAG, "Optional field replace skipped: ${target.javaClass.name}.$name: ${error.message}")
        }
    }

    private data class AppCompatThemeGuardResult(
        val applied: Boolean,
        val themeResourceId: Int = 0
    )

    private data class ActivityRecordInjectionResult(
        val patchResult: ActivityClientRecordPatchResult?,
        val activityInfoPackageName: String,
        val applicationInfoPackageName: String?
    )

    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
}

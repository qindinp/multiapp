package com.multiapp.core.loader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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
        val loadedApkPatchedFields: List<String> = emptyList(),
        val loadedApkInstalledAliasCount: Int = 0,
        val loadedApkSkippedReason: String? = null
    )

    fun inject(
        activity: Activity,
        hostContext: Context,
        config: VirtualContextConfig,
        guestApplication: Application?,
        guestClassLoader: ClassLoader
    ): InjectionResult {
        val guestContext = VirtualContextWrapper(
            base = hostContext,
            config = config,
            guestClassLoader = guestClassLoader
        )

        val contextInjected = replaceBaseContext(activity, guestContext)
        val applicationInjected = guestApplication?.let { replaceApplication(activity, it) } ?: false
        replaceFieldIfPresent(activity, "mResources", guestContext.resources)
        val loadedApkPatch = patchLoadedApkIfPresent(activity, guestContext, config, guestClassLoader)

        return InjectionResult(
            contextInjected = contextInjected,
            applicationInjected = applicationInjected,
            dataDir = config.dataDir,
            packageName = config.virtualPackageName,
            applicationClassName = guestApplication?.javaClass?.name,
            loadedApkPatchedFields = loadedApkPatch?.patchResult?.patchedFields.orEmpty(),
            loadedApkInstalledAliasCount = loadedApkPatch?.installedAliasCount ?: 0,
            loadedApkSkippedReason = loadedApkPatch?.skippedReason
        )
    }

    private fun patchLoadedApkIfPresent(
        activity: Activity,
        guestContext: VirtualContextWrapper,
        config: VirtualContextConfig,
        guestClassLoader: ClassLoader
    ): ActivityThreadLoadedApkInstallResult? {
        val loadedApk = findFieldInHierarchy(Activity::class.java, "mLoadedApk")
            ?.let { field -> runCatching { field.get(activity) }.getOrNull() }
            ?: findFieldInHierarchy(Activity::class.java, "mPackageInfo")
                ?.let { field -> runCatching { field.get(activity) }.getOrNull() }
            ?: return null

        val state = LoadedApkRuntimeState(
            packageName = config.originPackageName,
            applicationInfo = guestContext.applicationInfo,
            resources = guestContext.resources,
            classLoader = guestClassLoader
        )
        return runCatching {
            ActivityThreadLoadedApkInstaller.install(
                activityThread = ActivityThreadCompat.currentActivityThread(),
                loadedApk = loadedApk,
                state = state,
                packageAliases = listOf(config.originPackageName, config.virtualPackageName),
                hostPackageName = guestContext.baseContext.packageName
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to install ActivityThread LoadedApk aliases", error)
        }.getOrNull()
    }

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

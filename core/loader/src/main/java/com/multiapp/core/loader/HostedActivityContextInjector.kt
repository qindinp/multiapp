package com.multiapp.core.loader

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import com.multiapp.core.common.AndroidCompat
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
        val appCompatThemeResourceId: Int = 0,
        val themeVerdict: String = "UNKNOWN",
        val themeAppliedSource: String = "NONE",
        val appCompatAttrsVerdict: String = "UNKNOWN",
        val hostAppCompatBridgeApplied: Boolean = false,
        val hostAppCompatFallbackApplied: Boolean = false,
        val appCompatAttrsProbe: String = "",
        val themeRuntimeOwner: String = "UNKNOWN",
        val activityThemeProbe: String = "",
        val contextThemeProbe: String = "",
        val themeFieldPatched: Boolean = false,
        val baseContextInjectedBeforeTheme: Boolean = false,
        val hiddenApiBypassApplied: Boolean = false,
        val injectionPhase: String = "preOnCreate"
    )

    fun inject(
        activity: Activity,
        hostContext: Context,
        hostPackageName: String?,
        config: VirtualContextConfig,
        guestApplication: Application?,
        guestClassLoader: ClassLoader,
        injectionPhase: String = "preOnCreate",
        allowHostAppCompatFallback: Boolean = false
    ): InjectionResult {
        val guestContext = VirtualContextWrappers.create(
            base = hostContext,
            config = config,
            guestClassLoader = guestClassLoader
        )
        val hiddenApiBypassApplied = runCatching { AndroidCompat.bypassHiddenApis() }
            .onFailure { error -> Log.d(TAG, "Hidden API bypass unavailable: ${error.message}") }
            .getOrDefault(false)
        val guestActivityClassName = activity.intent?.getStringExtra("multiapp.guestActivityClassName")
            ?.takeIf { it.isNotBlank() }
            ?: activity.javaClass.name

        val runtimeApplicationInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = guestContext.applicationInfo
        )
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
        val activityTheme = applyHostedActivityTheme(
            activity = activity,
            hostContext = hostContext,
            guestContext = guestContext,
            config = config,
            guestActivityClassName = guestActivityClassName,
            guestClassLoader = guestClassLoader,
            allowHostAppCompatFallback = allowHostAppCompatFallback,
            baseContextInjectedBeforeTheme = contextInjected
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
            appCompatThemeGuardApplied = activityTheme.applied,
            appCompatThemeResourceId = activityTheme.themeResourceId,
            themeVerdict = activityTheme.themeVerdict,
            themeAppliedSource = activityTheme.appliedSource,
            appCompatAttrsVerdict = activityTheme.appCompatAttrsVerdict,
            hostAppCompatBridgeApplied = activityTheme.hostAppCompatBridgeApplied,
            hostAppCompatFallbackApplied = activityTheme.hostAppCompatFallbackApplied,
            appCompatAttrsProbe = activityTheme.appCompatAttrsProbe,
            themeRuntimeOwner = activityTheme.runtimeOwner,
            activityThemeProbe = activityTheme.activityThemeProbe,
            contextThemeProbe = activityTheme.contextThemeProbe,
            themeFieldPatched = activityTheme.themeFieldPatched,
            baseContextInjectedBeforeTheme = activityTheme.baseContextInjectedBeforeTheme,
            hiddenApiBypassApplied = hiddenApiBypassApplied,
            injectionPhase = injectionPhase
        )
    }

    private fun applyHostedActivityTheme(
        activity: Activity,
        hostContext: Context,
        guestContext: VirtualContextWrapper,
        config: VirtualContextConfig,
        guestActivityClassName: String,
        guestClassLoader: ClassLoader,
        allowHostAppCompatFallback: Boolean,
        baseContextInjectedBeforeTheme: Boolean
    ): ActivityThemeResult {
        val guestTheme = resolveGuestActivityTheme(config, guestActivityClassName)
        if (guestTheme.themeResourceId != 0) {
            return applyGuestActivityTheme(
                activity = activity,
                hostContext = hostContext,
                guestContext = guestContext,
                themeResourceId = guestTheme.themeResourceId,
                appliedSource = guestTheme.source,
                guestClassLoader = guestClassLoader,
                originPackageName = config.originPackageName,
                virtualPackageName = config.virtualPackageName,
                allowHostAppCompatFallback = allowHostAppCompatFallback,
                baseContextInjectedBeforeTheme = baseContextInjectedBeforeTheme
            )
        }

        val themeId = resolveHostProxyTheme(hostContext)
        if (themeId == 0) {
            return ActivityThemeResult(
                applied = false,
                themeVerdict = "FAIL",
                appliedSource = "HOST_PROXY_THEME_NOT_FOUND",
                appCompatAttrsVerdict = "UNKNOWN"
            )
        }
        return runCatching {
            activity.setTheme(themeId)
            activity.theme
            replaceFieldIfPresent(activity, "mThemeResource", themeId)
            val attrsProbe = appCompatAttrsProbe(
                activity,
                guestClassLoader,
                config.originPackageName,
                config.virtualPackageName,
                allowHostStyleablePass = true
            )
            ActivityThemeResult(
                applied = true,
                themeResourceId = themeId,
                themeVerdict = if (attrsProbe.verdict == "PASS") "PASS" else "PARTIAL",
                appliedSource = "HOST_PROXY_APPCOMPAT_BASELINE",
                appCompatAttrsVerdict = attrsProbe.verdict,
                appCompatAttrsProbe = attrsProbe.detail,
                runtimeOwner = "HOST_PROXY",
                activityThemeProbe = attrsProbe.detail,
                contextThemeProbe = "NO_GUEST_THEME",
                themeFieldPatched = false,
                baseContextInjectedBeforeTheme = baseContextInjectedBeforeTheme
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply host AppCompat theme guard: ${activity.javaClass.name}", error)
        }.getOrDefault(
            ActivityThemeResult(
                applied = false,
                themeResourceId = themeId,
                themeVerdict = "FAIL",
                appliedSource = "HOST_PROXY_APPCOMPAT_FAILED",
                appCompatAttrsVerdict = "UNKNOWN"
            )
        )
    }

    private fun applyGuestActivityTheme(
        activity: Activity,
        hostContext: Context,
        guestContext: VirtualContextWrapper,
        themeResourceId: Int,
        appliedSource: String,
        guestClassLoader: ClassLoader,
        originPackageName: String,
        virtualPackageName: String,
        @Suppress("UNUSED_PARAMETER") allowHostAppCompatFallback: Boolean,
        baseContextInjectedBeforeTheme: Boolean
    ): ActivityThemeResult {
        return runCatching {
            guestContext.setTheme(themeResourceId)
            replaceFieldIfPresent(activity, "mResources", guestContext.resources)
            activity.setTheme(themeResourceId)
            val hostBridgeApplied = applyHostAppCompatBridge(
                hostContext = hostContext,
                targetTheme = guestContext.theme
            )
            guestContext.theme.applyStyle(themeResourceId, true)
            val themeFieldPatched = replaceFieldIfPresent(activity, "mTheme", guestContext.theme)
            replaceFieldIfPresent(activity, "mThemeResource", themeResourceId)
            activity.applicationInfo?.let { info ->
                info.theme = themeResourceId
            }
            val attrsProbe = appCompatAttrsProbe(
                activity,
                guestClassLoader,
                originPackageName,
                virtualPackageName,
                allowHostStyleablePass = false
            )
            val contextProbe = appCompatAttrsProbe(
                guestContext,
                guestClassLoader,
                originPackageName,
                virtualPackageName,
                allowHostStyleablePass = false
            )
            ActivityThemeResult(
                applied = true,
                themeResourceId = themeResourceId,
                themeVerdict = if (attrsProbe.verdict == "PASS") "PASS" else "PARTIAL",
                appliedSource = appliedSource,
                appCompatAttrsVerdict = attrsProbe.verdict,
                hostAppCompatBridgeApplied = hostBridgeApplied,
                appCompatAttrsProbe = listOf(
                    "activity=${attrsProbe.detail}",
                    "context=${contextProbe.detail}"
                ).joinToString(";"),
                runtimeOwner = "GUEST_RUNTIME",
                activityThemeProbe = attrsProbe.detail,
                contextThemeProbe = contextProbe.detail,
                themeFieldPatched = themeFieldPatched,
                baseContextInjectedBeforeTheme = baseContextInjectedBeforeTheme
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply guest Activity theme: ${activity.javaClass.name}", error)
        }.getOrDefault(
            ActivityThemeResult(
                applied = false,
                themeResourceId = themeResourceId,
                themeVerdict = "FAIL",
                appliedSource = "${appliedSource}_FAILED",
                appCompatAttrsVerdict = "UNKNOWN"
            )
        )
    }

    private fun resolveGuestActivityTheme(
        config: VirtualContextConfig,
        guestActivityClassName: String
    ): ResolvedActivityTheme {
        val snapshot = config.packageSnapshot ?: return ResolvedActivityTheme(0, "NONE")
        val activityTheme = snapshot.activities.firstOrNull {
            it.name == guestActivityClassName || it.targetActivityName == guestActivityClassName
        }?.themeId ?: 0
        if (activityTheme != 0) {
            return ResolvedActivityTheme(activityTheme, "GUEST_ACTIVITY_THEME")
        }
        if (snapshot.themeId != 0) {
            return ResolvedActivityTheme(snapshot.themeId, "GUEST_APPLICATION_THEME")
        }
        return ResolvedActivityTheme(0, "NONE")
    }

    private fun applyHostAppCompatBridge(
        hostContext: Context,
        targetTheme: Resources.Theme
    ): Boolean {
        val hostThemeId = resolveHostProxyTheme(hostContext)
        if (hostThemeId == 0) return false
        return runCatching {
            val hostTheme = hostContext.resources.newTheme()
            hostTheme.applyStyle(hostThemeId, true)
            targetTheme.setTo(hostTheme)
            true
        }.onFailure { error ->
            Log.d(TAG, "Host AppCompat theme bridge skipped: ${error.message}")
        }.getOrDefault(false)
    }

    private fun appCompatAttrsProbe(
        context: Context,
        guestClassLoader: ClassLoader,
        originPackageName: String,
        virtualPackageName: String,
        allowHostStyleablePass: Boolean
    ): AppCompatAttrsProbe {
        val styleableCandidates = listOf(
            "androidx.appcompat.R\$styleable",
            "$originPackageName.R\$styleable",
            "$virtualPackageName.R\$styleable"
        )
        val attempts = mutableListOf<String>()
        var sawFail = false
        for (className in styleableCandidates) {
            val styleableClass = runCatching {
                Class.forName(className, false, guestClassLoader)
            }.getOrNull()
            if (styleableClass == null) {
                attempts += "$className:CLASS_NOT_FOUND_IN_GUEST"
                continue
            }

            val probe = probeStyleableClass(context, styleableClass, "guest:$className")
            attempts += probe.detail
            if (probe.verdict == "PASS") return probe
            if (probe.verdict == "FAIL") sawFail = true
        }
        val hostStyleableClass = runCatching {
            Class.forName("androidx.appcompat.R\$styleable")
        }.getOrNull()
        if (hostStyleableClass != null) {
            val hostProbe = probeStyleableClass(context, hostStyleableClass, "host:androidx.appcompat.R\$styleable")
            attempts += hostProbe.detail
            if (hostProbe.verdict == "PASS") {
                return if (allowHostStyleablePass) {
                    hostProbe
                } else {
                    AppCompatAttrsProbe("HOST_ONLY", hostProbe.detail)
                }
            }
            if (hostProbe.verdict == "FAIL") sawFail = true
        }
        return AppCompatAttrsProbe(
            verdict = if (sawFail) "FAIL" else "UNKNOWN",
            detail = attempts.joinToString(";")
        )
    }

    private fun probeStyleableClass(
        context: Context,
        styleableClass: Class<*>,
        label: String
    ): AppCompatAttrsProbe {
        return runCatching {
            val attrs = styleableClass.getField("AppCompatTheme").get(null) as IntArray
            val windowActionBarIndex = styleableClass.getField("AppCompatTheme_windowActionBar").getInt(null)
            val typedArray = context.obtainStyledAttributes(attrs)
            try {
                val hasValue = typedArray.hasValue(windowActionBarIndex)
                val attrId = attrs.getOrNull(windowActionBarIndex) ?: 0
                AppCompatAttrsProbe(
                    verdict = if (hasValue) "PASS" else "FAIL",
                    detail = "$label:windowActionBar=0x${Integer.toHexString(attrId)}:hasValue=$hasValue"
                )
            } finally {
                typedArray.recycle()
            }
        }.getOrElse { error ->
            AppCompatAttrsProbe(
                verdict = "UNKNOWN",
                detail = "$label:${error.javaClass.simpleName}"
            )
        }
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

    private fun replaceFieldIfPresent(target: Any, name: String, value: Any?): Boolean {
        val field = findFieldInHierarchy(target.javaClass, name) ?: return false
        return runCatching {
            field.set(target, value)
            true
        }.recoverCatching { reflectionError ->
            UnsafeFieldWriter.write(target, field, value).getOrElse { unsafeError ->
                throw IllegalStateException(
                    "reflection=${reflectionError.javaClass.simpleName}, unsafe=${unsafeError.javaClass.simpleName}",
                    unsafeError
                )
            }
            true
        }.onFailure { error ->
            Log.d(TAG, "Optional field replace skipped: ${target.javaClass.name}.$name: ${error.message}")
        }.getOrDefault(false)
    }

    private data class ActivityThemeResult(
        val applied: Boolean,
        val themeResourceId: Int = 0,
        val themeVerdict: String = "UNKNOWN",
        val appliedSource: String = "NONE",
        val appCompatAttrsVerdict: String = "UNKNOWN",
        val hostAppCompatBridgeApplied: Boolean = false,
        val hostAppCompatFallbackApplied: Boolean = false,
        val appCompatAttrsProbe: String = "",
        val runtimeOwner: String = "UNKNOWN",
        val activityThemeProbe: String = "",
        val contextThemeProbe: String = "",
        val themeFieldPatched: Boolean = false,
        val baseContextInjectedBeforeTheme: Boolean = false
    )

    private data class ResolvedActivityTheme(
        val themeResourceId: Int,
        val source: String
    )

    private data class AppCompatAttrsProbe(
        val verdict: String,
        val detail: String
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
        runCatching {
            return com.multiapp.core.common.findField(type, name)?.apply { isAccessible = true }
        }.onFailure { error ->
            Log.d(TAG, "HiddenApiBypass field lookup skipped: ${type.name}.$name: ${error.message}")
        }
        return null
    }

    private object UnsafeFieldWriter {
        private val unsafeClass by lazy(LazyThreadSafetyMode.NONE) { Class.forName("sun.misc.Unsafe") }
        private val unsafe by lazy(LazyThreadSafetyMode.NONE) {
            unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        }
        private val objectFieldOffset by lazy(LazyThreadSafetyMode.NONE) {
            unsafeClass.getDeclaredMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
        }
        private val putObject by lazy(LazyThreadSafetyMode.NONE) {
            unsafeClass.getDeclaredMethod(
                "putObject",
                Any::class.java,
                Long::class.javaPrimitiveType,
                Any::class.java
            )
        }
        private val putInt by lazy(LazyThreadSafetyMode.NONE) {
            unsafeClass.getDeclaredMethod(
                "putInt",
                Any::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }

        fun write(target: Any, field: java.lang.reflect.Field, value: Any?): Result<Unit> = runCatching {
            val offset = objectFieldOffset.invoke(unsafe, field) as Long
            if (field.type == Int::class.javaPrimitiveType) {
                putInt.invoke(unsafe, target, offset, value as? Int ?: 0)
            } else {
                putObject.invoke(unsafe, target, offset, value)
            }
        }
    }
}

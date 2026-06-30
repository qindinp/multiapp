package com.multiapp.core.loader

import android.content.ComponentName
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import java.lang.reflect.Field

/**
 * Activity 主题运行期补齐 — 确保 Activity 使用原始 APK 的主题和资源。
 *
 * 从 LoaderFactory 提取的纯逻辑：
 * - 主题解析（manifest 主题 → application 主题 → 系统 fallback）
 * - Activity 主题应用（反射设置 mTheme / mThemeResource）
 * - 资源对象图替换（递归替换 Resources / AssetManager 引用）
 * - Activity Context 包装（GuestContextWrapper 安装与恢复）
 * - LayoutInflater context 同步
 */
object ActivityThemeCompat {

    private const val TAG = "ActivityThemeCompat"

    /**
     * 解析 Activity 的主题 ID。
     * 优先级：Activity 自身主题 > Application 主题 > PackageManager 查询。
     *
     * @param className Activity 类名
     * @param activityThemes 从 manifest 解析的 Activity 主题映射
     * @param applicationThemeId Application 级别主题 ID
     * @param stubPackageName stub 包名（用于 PackageManager 回退查询）
     * @param guestPackageName 原始包名（用于 PackageManager 回退查询）
     * @return 主题 ID，0 表示未找到
     */
    fun resolveActivityTheme(
        className: String,
        activityThemes: Map<String, Int>,
        applicationThemeId: Int,
        stubPackageName: String?,
        guestPackageName: String?
    ): Int {
        val originTheme = activityThemes[className] ?: 0
        if (originTheme != 0) return originTheme
        if (applicationThemeId != 0) return applicationThemeId

        return try {
            val at = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val systemContext = at.javaClass
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
                .invoke(at) as android.content.Context
            val candidates = listOfNotNull(stubPackageName, guestPackageName)
            var resolved = 0
            for (pkg in candidates) {
                try {
                    val info = systemContext.packageManager.getActivityInfo(
                        ComponentName(pkg, className), 0
                    )
                    if (info.theme != 0) { resolved = info.theme; break }
                } catch (_: Exception) { }
            }
            resolved
        } catch (e: Throwable) {
            Log.w(TAG, "resolveActivityTheme fallback failed: ${e.message}")
            0
        }
    }

    /**
     * 在 instantiateActivity 时应用主题。
     * 设置 ApplicationInfo.theme 并通过反射替换 mThemeResource。
     *
     * @return 应用的主题 ID，0 表示未应用
     */
    fun applyActivityThemeIfKnown(
        activity: android.app.Activity,
        className: String,
        activityThemes: Map<String, Int>,
        applicationThemeId: Int,
        stubPackageName: String?,
        guestPackageName: String?
    ): Int {
        return try {
            val themeId = resolveActivityTheme(
                className, activityThemes, applicationThemeId, stubPackageName, guestPackageName
            )
            if (themeId != 0) {
                activity.setTheme(themeId)
                activity.applicationInfo?.let { it.theme = themeId }
                replaceFieldIfPresent(activity, "mThemeResource", themeId)
                Log.d(TAG, "Activity theme set early: $className -> 0x${Integer.toHexString(themeId)}")
                themeId
            } else {
                val appInfo = activity.applicationInfo
                if (appInfo != null && appInfo.theme == 0) {
                    // 优先尝试 AppCompat 主题（解决 AppCompatActivity 要求 Theme.AppCompat 的问题）
                    val appCompatTheme = resolveAppCompatTheme(activity, guestPackageName, stubPackageName)
                    if (appCompatTheme != 0) {
                        appInfo.theme = appCompatTheme
                        activity.setTheme(appCompatTheme)
                        replaceFieldIfPresent(activity, "mThemeResource", appCompatTheme)
                        Log.d(TAG, "Activity theme fallback (AppCompat): $className -> 0x${Integer.toHexString(appCompatTheme)}")
                        appCompatTheme
                    } else if (isAppCompatActivity(activity)) {
                        // Activity 继承 AppCompatActivity 但找不到 AppCompat 主题时，尝试从宿主 APK 加载
                        val hostAppCompatTheme = resolveAppCompatThemeFromHost(activity)
                        if (hostAppCompatTheme != 0) {
                            appInfo.theme = hostAppCompatTheme
                            activity.setTheme(hostAppCompatTheme)
                            replaceFieldIfPresent(activity, "mThemeResource", hostAppCompatTheme)
                            Log.d(TAG, "Activity theme fallback (host AppCompat): $className -> 0x${Integer.toHexString(hostAppCompatTheme)}")
                            hostAppCompatTheme
                        } else {
                            // 最终 fallback：使用 Material 主题，但记录警告
                            appInfo.theme = android.R.style.Theme_Material_Light_NoActionBar
                            Log.w(TAG, "Activity theme fallback (Material, but activity is AppCompat!): $className")
                            android.R.style.Theme_Material_Light_NoActionBar
                        }
                    } else {
                        appInfo.theme = android.R.style.Theme_Material_Light_NoActionBar
                        Log.d(TAG, "Activity theme fallback (Material): $className -> Theme_Material_Light_NoActionBar")
                        android.R.style.Theme_Material_Light_NoActionBar
                    }
                } else {
                    Log.w(TAG, "Activity theme is 0 for $className")
                    0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyActivityThemeIfKnown failed for $className: ${e.message}")
            0
        }
    }

    /**
     * 用原始资源和主题同步 Activity 的 resource context。
     * 在 ActivityLifecycleCallbacks.onActivityPreCreated 和 IntentRemappingInstrumentation 回调中调用。
     */
    fun syncActivityResourceContext(
        activity: android.app.Activity,
        pkg: String,
        apkPath: String,
        reason: String,
        originResources: Resources?,
        originActivityThemes: Map<String, Int>,
        originApplicationThemeId: Int,
        originResourceApkPath: String?,
        originNativeLibDir: String?,
        originMetaData: android.os.Bundle?,
        wrapContextForGuest: (android.content.ContextWrapper, String, String) -> android.content.Context?,
        syncInflater: (android.app.Activity, android.content.Context) -> Unit
    ) {
        if (originResources == null) {
            Log.w(TAG, "Activity resource sync skipped, originResources is null: ${activity.javaClass.name}")
            return
        }

        try {
            val wrappedContext = if (shouldWrapActivityBase(reason)) {
                wrapContextForGuest(activity, pkg, apkPath)
            } else {
                Log.d(TAG, "Skip Activity base context wrapping[$reason]: ${activity.javaClass.name}")
                null
            }
            replaceFieldIfPresent(activity, "mResources", originResources)
            patchResourceObjectGraph(activity.resources, originResources, "activity.resources")
            patchResourceObjectGraph(activity.application?.resources, originResources, "application.resources")
            if (wrappedContext != null) {
                patchResourceObjectGraph(wrappedContext.resources, originResources, "wrappedContext.resources")
            }

            val themeId = originActivityThemes[activity.javaClass.name]
                ?: originApplicationThemeId
            applyOriginActivityTheme(activity, wrappedContext, themeId, originResources)

            val inflaterContext = if (shouldUseActivityAsInflaterContext(activity)) {
                activity
            } else {
                wrappedContext ?: activity
            }
            syncInflater(activity, inflaterContext)

            Log.d(
                TAG,
                "Synced Activity resources[$reason]: ${activity.javaClass.name}, " +
                    "theme=0x${Integer.toHexString(themeId)}, res=${originResources.javaClass.name}"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Activity resource sync failed: ${activity.javaClass.name}: ${e.message}")
        }
    }

    /**
     * 在 Activity.onCreate 后恢复 base context（移除 GuestContextWrapper）。
     */
    fun restoreActivityBaseContextAfterLifecycle(activity: android.app.Activity, reason: String) {
        val className = activity.javaClass.name
        if (reason != "callActivityOnCreate" && reason != "callActivityOnCreatePersistable") return
        try {
            val mBaseField = findFieldInHierarchy(android.content.ContextWrapper::class.java, "mBase")
                ?: return
            val current = mBaseField.get(activity)
            if (current is GuestContextWrapper) {
                mBaseField.set(activity, current.baseContext)
                Log.d(TAG, "Restored Activity base context after $reason: $className")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Restore Activity base context failed after $reason: $className: ${e.message}")
        }
    }

    /**
     * 用原始资源和主题重建 LoadedApk 的 Resources。
     */
    fun rebuildLoadedApkResources(loadedApk: Any, originApk: java.io.File): Resources? {
        return try {
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }

            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val result = addAssetPath.invoke(assets, originApk.absolutePath) as Int
            Log.d(TAG, "Clean AssetManager.addAssetPath result: $result for ${originApk.absolutePath}")

            if (result == 0) {
                Log.w(TAG, "addAssetPath returned 0 — resources may not load correctly")
            }

            val oldResources = loadedApk.javaClass
                .getDeclaredField("mResources")
                .apply { isAccessible = true }
                .get(loadedApk) as? Resources

            val displayMetrics = oldResources?.displayMetrics ?: android.util.DisplayMetrics()
            val configuration = oldResources?.configuration ?: Configuration()

            val newResources = Resources(assets, displayMetrics, configuration)

            loadedApk.javaClass
                .getDeclaredField("mResources")
                .apply { isAccessible = true }
                .set(loadedApk, newResources)

            Log.d(TAG, "Replaced LoadedApk.mResources with clean origin Resources")
            newResources
        } catch (e: Exception) {
            Log.w(TAG, "Rebuilding origin Resources failed: ${e.message}")
            null
        }
    }

    /**
     * 递归替换对象图中的 Resources / AssetManager 引用。
     */
    fun patchResourceObjectGraph(
        target: Any?,
        originResources: Resources,
        label: String,
        seen: MutableSet<Int> = mutableSetOf()
    ) {
        if (target == null) return
        if (!seen.add(System.identityHashCode(target))) return

        val originImpl = try {
            findFieldInHierarchy(Resources::class.java, "mResourcesImpl")?.get(originResources)
        } catch (_: Throwable) { null }
        val originAssets = originResources.assets
        val originDisplayMetrics = originResources.displayMetrics
        val originConfiguration = originResources.configuration

        var patchedAny = false
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val fieldType = field.type
                    when {
                        Resources::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originResources) {
                                field.set(target, originResources)
                                patchedAny = true
                                Log.d(TAG, "Patched $label.${field.name} -> originResources")
                            }
                        }
                        fieldType.name.contains("ResourcesImpl") && originImpl != null -> {
                            if (field.get(target) !== originImpl) {
                                field.set(target, originImpl)
                                patchedAny = true
                                Log.d(TAG, "Patched $label.${field.name} -> originResourcesImpl")
                            }
                        }
                        AssetManager::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originAssets) {
                                field.set(target, originAssets)
                                patchedAny = true
                                Log.d(TAG, "Patched $label.${field.name} -> originAssets")
                            }
                        }
                        android.util.DisplayMetrics::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originDisplayMetrics) {
                                field.set(target, originDisplayMetrics)
                                patchedAny = true
                            }
                        }
                        Configuration::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originConfiguration) {
                                field.set(target, originConfiguration)
                                patchedAny = true
                            }
                        }
                    }
                } catch (_: Throwable) { }
            }
            current = current.superclass
        }

        if (!patchedAny && target is Resources) {
            Log.d(TAG, "Resource graph already aligned for $label: ${target.javaClass.name}")
        }

        current = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(target) ?: continue
                    when {
                        value is Resources && value !== originResources -> {
                            patchResourceObjectGraph(value, originResources, "$label.${field.name}", seen)
                        }
                        value.javaClass.name.startsWith("com.qq.reader.") -> {
                            patchResourceObjectGraph(value, originResources, "$label.${field.name}", seen)
                        }
                    }
                } catch (_: Throwable) { }
            }
            current = current.superclass
        }
    }

    /**
     * 替换目标对象中指定名称的字段（向上遍历继承链）。
     *
     * @return true 如果替换成功
     */
    fun replaceFieldIfPresent(target: Any, name: String, value: Any?): Boolean {
        val field = findFieldInHierarchy(target.javaClass, name) ?: return false
        return try {
            field.set(target, value)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Reflect set ${target.javaClass.name}.$name failed: ${e.message}")
            false
        }
    }

    /**
     * 在继承链中查找指定名称的字段。
     */
    fun findFieldInHierarchy(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun applyOriginActivityTheme(
        activity: android.app.Activity,
        wrappedContext: android.content.Context?,
        themeId: Int,
        originResources: Resources
    ) {
        try {
            if (isAppCompatActivity(activity)) {
                // AppCompatActivity validates AppCompat theme attrs in onPostCreate.
                // Keep the host proxy AppCompat theme and only swap resources; applying
                // an origin APK theme here can strip the AppCompat attrs and crash.
                (wrappedContext as? GuestContextWrapper)?.setTheme(0)
                replaceFieldIfPresent(activity, "mResources", originResources)
                Log.d(
                    TAG,
                    "Preserved host AppCompat Activity theme: ${activity.javaClass.name}, " +
                        "originTheme=0x${Integer.toHexString(themeId)}"
                )
                return
            }

            (wrappedContext as? GuestContextWrapper)?.setTheme(themeId)

            val theme = originResources.newTheme()
            try {
                theme.setTo(activity.application?.theme)
            } catch (_: Throwable) { }
            if (themeId != 0) {
                theme.applyStyle(themeId, true)
            }

            replaceFieldIfPresent(activity, "mResources", originResources)
            replaceFieldIfPresent(activity, "mTheme", theme)
            if (themeId != 0) {
                replaceFieldIfPresent(activity, "mThemeResource", themeId)
            }
            Log.d(
                TAG,
                "Applied origin Activity theme: ${activity.javaClass.name}, " +
                    "theme=0x${Integer.toHexString(themeId)}"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Apply origin Activity theme failed: ${activity.javaClass.name}: ${e.message}")
        }
    }

    private fun shouldWrapActivityBase(reason: String): Boolean {
        return reason == "preCreated" ||
            reason == "callActivityOnCreate" ||
            reason == "callActivityOnCreatePersistable"
    }

    private fun shouldUseActivityAsInflaterContext(activity: android.app.Activity): Boolean {
        return activity.javaClass.name == "com.qq.reader.activity.ReaderPageActivity"
    }

    /**
     * 从 Activity context 的资源中解析 AppCompat 主题。
     */
    private fun resolveAppCompatTheme(
        activity: android.app.Activity,
        guestPkg: String?,
        stubPkg: String?
    ): Int {
        val res = activity.resources
        val candidates = listOf(
            "Theme.AppCompat.Light.NoActionBar",
            "Theme.AppCompat.DayNight.NoActionBar",
            "Theme.AppCompat.NoActionBar",
            "Theme.AppCompat.Light.DarkActionBar",
            "Theme.AppCompat.Light",
            "Theme.AppCompat",
        )
        for (name in candidates) {
            for (pkg in listOfNotNull(guestPkg, stubPkg)) {
                try {
                    val id = res.getIdentifier(name, "style", pkg)
                    if (id != 0) return id
                } catch (_: Throwable) { }
            }
        }
        return 0
    }

    /**
     * 检查 Activity 是否继承自 AppCompatActivity。
     */
    private fun isAppCompatActivity(activity: android.app.Activity): Boolean {
        var clazz: Class<*>? = activity.javaClass
        while (clazz != null) {
            if (clazz.name == "androidx.appcompat.app.AppCompatActivity" ||
                clazz.name == "androidx.appcompat.app.AppCompatActivity" ||
                clazz.name.contains("AppCompatActivity")) {
                return true
            }
            clazz = clazz.superclass
        }
        return false
    }

    /**
     * 从宿主 APK（multiapp 自身）的资源中解析 AppCompat 主题。
     * 当 guest/stub 包中找不到 AppCompat 主题时使用。
     */
    private fun resolveAppCompatThemeFromHost(activity: android.app.Activity): Int {
        val candidates = listOf(
            "Theme_AppCompat_Light_NoActionBar",
            "Theme_AppCompat_DayNight_NoActionBar",
            "Theme_AppCompat_Light",
        )
        // 尝试从宿主 APK 的资源中查找
        for (name in candidates) {
            try {
                val id = activity.resources.getIdentifier(name, "style", "com.multiapp.app")
                if (id != 0) return id
            } catch (_: Throwable) { }
        }
        // 尝试从 androidx.appcompat 包中查找
        try {
            val appCompatR = Class.forName("androidx.appcompat.R\$style")
            for (name in candidates) {
                try {
                    val field = appCompatR.getField(name)
                    val id = field.getInt(null)
                    if (id != 0) return id
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        return 0
    }
}

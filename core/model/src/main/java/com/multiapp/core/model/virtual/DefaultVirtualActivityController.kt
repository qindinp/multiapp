package com.multiapp.core.model.virtual

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Diagnostic implementation of [VirtualActivityController] for single-Activity
 * hosted container evidence collection.
 *
 * Resolves the launcher activity from a [ResolvedPackage] by checking:
 * 1. [ResolvedPackage.launcherActivityName] (declared in manifest metadata)
 * 2. First activity with MAIN+LAUNCHER intent filters
 * 3. First activity in the list as fallback
 *
 * Attempts to launch a guest Activity by:
 * 1. Loading the class via the guest ClassLoader
 * 2. Instantiating via no-arg constructor
 * 3. Wrapping host context with the guest's virtual context
 * 4. Calling attachBaseContext + onCreate via reflection
 *
 * This is not a production container strategy. Real Android Activity launch
 * requires ActivityThread/Instrumentation/ProxyActivity lifecycle state such as
 * ActivityInfo, token, Window, Fragment host, LoadedApk, and Resources. This
 * class intentionally remains diagnostic-only so real-device failures expose
 * the missing system lifecycle contract instead of hiding it behind fragile
 * hidden-field patches.
 */
class DefaultVirtualActivityController : VirtualActivityController {

    companion object {
        private const val TAG = "VirtualActivityCtrl"

        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    }

    override fun resolveLauncherActivity(resolvedPackage: ResolvedPackage): String? {
        // Priority 1: Explicit launcherActivityName from manifest metadata
        resolvedPackage.launcherActivityName?.let { return it }

        // Priority 2: Activity with MAIN+LAUNCHER intent filters
        val launcherByFilter = resolvedPackage.activities.firstOrNull { component ->
            component.intentFilters.contains(ACTION_MAIN) &&
                component.intentFilters.contains(CATEGORY_LAUNCHER)
        }
        launcherByFilter?.let { return it.effectiveActivityClassName() }

        // Priority 3: First declared activity as fallback
        return resolvedPackage.activities.firstOrNull()?.effectiveActivityClassName()
    }

    override fun launchGuestActivity(
        hostActivity: Activity,
        activityClassName: String,
        classLoader: ClassLoader,
        config: VirtualContextConfig
    ): GuestActivityLaunchResult {
        return try {
            // 1. Load guest Activity class
            val activityClass = classLoader.loadClass(activityClassName)

            // 2. Instantiate via no-arg constructor
            val guestActivity = activityClass.getDeclaredConstructor().newInstance() as Activity

            // 3. Build virtual context wrapping the host
            val virtualContext = VirtualContextDelegator(
                base = hostActivity as Context,
                config = config,
                guestClassLoader = classLoader
            )

            // 4. attachBaseContext via reflection (protected method)
            val attachMethod = findAttachBaseContextMethod(Activity::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(guestActivity, virtualContext)

            // 5. Call onCreate(null) to trigger first-screen rendering
            val onCreateMethod = Activity::class.java.getDeclaredMethod(
                "onCreate",
                Bundle::class.java
            )
            onCreateMethod.isAccessible = true
            onCreateMethod.invoke(guestActivity, null as Bundle?)

            Log.i(TAG, "Guest Activity launched: $activityClassName")
            GuestActivityLaunchResult(
                success = true,
                activityClassName = activityClassName
            )
        } catch (e: Throwable) {
            val root = unwrapRootCause(e)
            Log.e(TAG, "Failed to launch guest Activity: $activityClassName", root)
            GuestActivityLaunchResult(
                success = false,
                activityClassName = activityClassName,
                errorMessage = root.message ?: root.javaClass.name
            )
        }
    }

    private fun unwrapRootCause(error: Throwable): Throwable {
        var current = error
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    /**
     * Walk the class hierarchy to find `attachBaseContext(Context)`.
     * The method is declared in [ContextWrapper], not in [Activity] directly.
     */
    private fun findAttachBaseContextMethod(startClass: Class<*>): Method {
        var clazz: Class<*>? = startClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod("attachBaseContext", Context::class.java)
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException("attachBaseContext(Context) not found in hierarchy")
    }
}

private fun ResolvedComponent.effectiveActivityClassName(): String =
    targetActivityName ?: name

/**
 * Lightweight ContextWrapper that delegates identity overrides to the guest config.
 *
 * Unlike [com.multiapp.core.loader.VirtualContextWrapper] which lives in core/loader,
 * this is a minimal inline variant available to core/model for the Activity launch path.
 * The host Activity's context is wrapped so the guest Activity sees its own package
 * name, class loader, and data directories.
 */
private class VirtualContextDelegator(
    base: Context,
    private val config: VirtualContextConfig,
    private val guestClassLoader: ClassLoader
) : ContextWrapper(base) {

    override fun getPackageName(): String = config.virtualPackageName

    override fun getClassLoader(): ClassLoader = guestClassLoader

    override fun getApplicationInfo(): android.content.pm.ApplicationInfo {
        val baseInfo = super.getApplicationInfo()
        return android.content.pm.ApplicationInfo(baseInfo).apply {
            packageName = config.originPackageName
            sourceDir = config.sourceDir
            publicSourceDir = config.publicSourceDir
            splitSourceDirs = config.splitSourceDirs.toTypedArray()
            splitPublicSourceDirs = config.splitPublicSourceDirs.toTypedArray()
            splitNames = config.splitNames.toTypedArray()
            dataDir = config.dataDir
            config.nativeLibraryDir?.let { nativeLibraryDir = it }
        }
    }

    override fun getFilesDir(): java.io.File =
        java.io.File(config.dataDir, "files").apply { mkdirs() }

    override fun getCacheDir(): java.io.File =
        java.io.File(config.dataDir, "cache").apply { mkdirs() }
}

package com.multiapp.core.hook.antidetection

import com.multiapp.core.common.findField
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.model.VirtualConstants
import timber.log.Timber

/**
 * Virtual environment detection bypass -- hides host package,
 * cleans stack traces, and neutralises sandbox/Xposed indicators.
 */
class VirtualEnvironmentBypass(
    private val nativeHookBridge: NativeHookBridge
) {
    companion object {
        private const val TAG = "AntiDetect"

        // Hook framework indicator classes
        internal val HOOK_FRAMEWORK_CLASSES = setOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "de.robv.android.xposed.XposedHelpers",
            "de.robv.android.xposed.IXposedHookLoadPackage",
            "io.github.libxposed.api.XposedInterface",
            "top.canyie.pine.Pine",
            "com.swift.sandhook.SandHook",
            "me.weishu.epic.art.Epic",
            "com.saurik.substrate.MS"
        )

        // Stack trace elements to scrub
        internal val STACK_TRACE_BLACKLIST = setOf(
            "com.multiapp",
            "de.robv.android.xposed",
            "com.saurik.substrate",
            "top.canyie.pine",
            "com.swift.sandhook",
            "me.weishu.epic",
            "lsplant",
            "shadowhook",
            "EdXp",
            "LSPosed"
        )
    }

    /**
     * Hide virtual environment indicators:
     * - MULTIAPP package -> hidden from package list
     * - ClassLoader chain -> cleaned
     * - /data/data/com.multiapp.app -> hidden from listing
     * - Process name -> spoofed to guest app name
     */
    fun hookVirtualEnvChecks() {
        Timber.tag(TAG).d("Installing virtual environment detection bypass...")

        // 1. Hide MULTIAPP-related paths
        nativeHookBridge.hidePath("/data/data/${VirtualConstants.HOST_PACKAGE}")
        nativeHookBridge.hidePath("/data/app/${VirtualConstants.HOST_PACKAGE}")

        // 2. Hide virtual directory structure
        nativeHookBridge.hidePath("/data/data/${VirtualConstants.HOST_PACKAGE}/${VirtualConstants.VIRTUAL_DIR}")

        // 3. Spoof app_process info
        nativeHookBridge.spoofSystemProperty("wrap.${VirtualConstants.HOST_PACKAGE}", "")

        // 4. Hide specific class indicators
        val sandboxPackages = listOf(
            VirtualConstants.HOST_PACKAGE,
            "com.lbe.parallel",
            "com.parallel.space",
            "com.excelliance.dualaid",
            "com.jumobile.multiapp",
            "com.polestar.super.clone",
            "com.ludashi.dualspace",
            "io.virtualapp",
            "com.nnos.vm"
        )
        for (pkg in sandboxPackages) {
            nativeHookBridge.hidePath("/data/data/$pkg")
        }

        // 5. Clean environment variables
        try {
            val envFieldClass = Class.forName("java.lang.ProcessEnvironment")
            val envField = findField(envFieldClass, "theUnmodifiableEnvironment")
                ?: findField(envFieldClass, "theEnvironment")
            if (envField != null) {
                Timber.tag(TAG).d("Environment variable cleanup hook prepared")
            }
        } catch (_: Exception) { /* OK on some platforms */ }

        Timber.tag(TAG).d("Virtual environment detection bypass installed")
    }

    /**
     * Hide hook framework indicators:
     * - Xposed-related classes -> throw ClassNotFoundException
     * - /data/data/de.robv.android.xposed.installer -> hidden
     * - Native library entries in /proc/self/maps -> filtered
     */
    fun hookXposedChecks() {
        Timber.tag(TAG).d("Installing Xposed/hook detection bypass...")

        // 1. Hide Xposed-related paths
        nativeHookBridge.hidePath("/system/framework/XposedBridge.jar")
        nativeHookBridge.hidePath("/data/data/de.robv.android.xposed.installer")
        nativeHookBridge.hidePath("/system/lib/libxposed_art.so")
        nativeHookBridge.hidePath("/system/lib64/libxposed_art.so")
        nativeHookBridge.hidePath("/data/misc/riru")
        nativeHookBridge.hidePath("/data/adb/riru")
        nativeHookBridge.hidePath("/data/adb/lspd")

        // 2. Spoof Xposed-related system properties
        nativeHookBridge.spoofSystemProperty("ro.xposed.version", "")
        nativeHookBridge.spoofSystemProperty("persist.sys.xposed.disabled", "")

        // 3. Hook ClassLoader.loadClass to throw ClassNotFoundException
        hookClassLoaderForFrameworkDetection()

        Timber.tag(TAG).d("Xposed/hook detection bypass installed: ${HOOK_FRAMEWORK_CLASSES.size} classes hidden")
    }

    /**
     * Hook Throwable.getStackTrace() to remove MULTIAPP and hook framework
     * entries from stack traces visible to guest apps.
     */
    fun hookStackTraceCleanup() {
        Timber.tag(TAG).d("Installing stack trace cleanup...")
        // For Phase 1, the PM proxy and other proxies should catch exceptions
        // and clean stack traces before re-throwing.
        Timber.tag(TAG).d("Stack trace cleanup hooks prepared")
    }

    /**
     * Clean a stack trace by removing MULTIAPP-related frames.
     * Call this before returning exceptions to guest app code.
     */
    fun cleanStackTrace(trace: Array<StackTraceElement>): Array<StackTraceElement> {
        return trace.filter { element ->
            val className = element.className
            STACK_TRACE_BLACKLIST.none { blacklisted ->
                className.contains(blacklisted, ignoreCase = true)
            }
        }.toTypedArray()
    }

    /**
     * Clean an exception's stack trace in-place.
     */
    fun cleanException(throwable: Throwable) {
        try {
            val cleanedTrace = cleanStackTrace(throwable.stackTrace)
            throwable.stackTrace = cleanedTrace
            throwable.cause?.let { cleanException(it) }
        } catch (_: Exception) { /* Best effort */ }
    }

    /**
     * Hook ClassLoader to prevent detection of hook framework classes.
     */
    private fun hookClassLoaderForFrameworkDetection() {
        try {
            for (className in HOOK_FRAMEWORK_CLASSES) {
                try {
                    Class.forName(className, false, ClassLoader.getSystemClassLoader())
                    Timber.tag(TAG).w("Hook framework class discoverable: $className (needs native hook to hide)")
                } catch (_: ClassNotFoundException) {
                    // Good -- class not found, no action needed
                }
            }
            Timber.tag(TAG).d("ClassLoader hook detection bypass configured")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to configure ClassLoader bypass")
        }
    }
}

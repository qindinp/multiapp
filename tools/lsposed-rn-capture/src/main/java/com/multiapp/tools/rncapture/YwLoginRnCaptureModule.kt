package com.multiapp.tools.rncapture

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.multiapp.core.hook.NativeHookBridge
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class YwLoginRnCaptureModule : IXposedHookLoadPackage {
    @Volatile
    private var targetClassLoader: ClassLoader? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        val processName = lpparam.processName ?: packageName
        if (packageName != TARGET_PACKAGE || processName != TARGET_PACKAGE) return

        targetClassLoader = lpparam.classLoader
        log("handleLoadPackage package=$packageName process=$processName first=${lpparam.isFirstApplication}")
        installOnce("early")
        thread(name = "ywlogin-rn-capture-installer", isDaemon = true) {
            val delays = longArrayOf(100L, 300L, 700L, 1200L, 2000L, 3500L, 5000L, 8000L)
            for (delayMs in delays) {
                try {
                    Thread.sleep(delayMs)
                    installOnce("delayed-${delayMs}ms")
                    tryHookJiaguLibrary("delayed-${delayMs}ms")
                    tryRegisterStubs("delayed-${delayMs}ms")
                } catch (t: Throwable) {
                    log("delayed install failed delay=${delayMs}ms error=${t.javaClass.name}: ${t.message}")
                }
            }
        }
    }

    private fun installOnce(stage: String) {
        if (isInstalled.get()) {
            log("install stage=$stage skipped already-installed")
            return
        }
        try {
            val loadedByPath = ensureNativeLibraryLoaded(stage)
            val bridge = NativeHookBridge.getInstance()
            val nativeOk = bridge.initNativeHooks()
            if (nativeOk) {
                bridge.setSuppressSelfSigkill(true)
            }
            val rnOk = bridge.installRegisterNativesLogger()
            val runtimeOk = bridge.hookRuntimeNativeLoad()
            val jiaguDiagOk = bridge.installJiaguJniDiagHooks()
            if (nativeOk && rnOk) {
                isInstalled.set(true)
            }
            log(
                "install stage=$stage loadedByPath=$loadedByPath nativeOk=$nativeOk " +
                    "rnOk=$rnOk runtimeOk=$runtimeOk jiaguDiagOk=$jiaguDiagOk installed=${isInstalled.get()}"
            )
        } catch (t: Throwable) {
            log("install stage=$stage failed ${t.javaClass.name}: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun ensureNativeLibraryLoaded(stage: String): Boolean {
        if (isNativeLoaded.get()) return true
        val libDir = findModuleNativeLibraryDir()
        if (libDir == null) {
            log("native load stage=$stage failed: module nativeLibraryDir not found classLoader=${javaClass.classLoader}")
            return false
        }

        var loadedMain = false
        for (libName in NATIVE_LOAD_ORDER) {
            val libFile = File(libDir, libName)
            if (!libFile.exists()) {
                log("native load stage=$stage missing ${libFile.absolutePath}")
                continue
            }
            try {
                System.load(libFile.absolutePath)
                log("native load stage=$stage loaded ${libFile.absolutePath}")
                if (libName == MAIN_NATIVE_LIB) loadedMain = true
            } catch (t: Throwable) {
                log("native load stage=$stage failed ${libFile.absolutePath}: ${t.javaClass.name}: ${t.message}")
            }
        }

        if (loadedMain) {
            NativeHookBridge.markNativeLibLoaded()
            isNativeLoaded.set(true)
        }
        return loadedMain
    }

    private fun findModuleNativeLibraryDir(): File? {
        currentApplication()?.let { app ->
            findNativeLibraryDirFromContext(app)?.let { return it }
        }
        findNativeLibraryDirFromClassLoader()?.let { return it }
        return null
    }

    private fun findNativeLibraryDirFromContext(context: Context): File? {
        return try {
            val packageManager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
            }
            File(info.nativeLibraryDir).takeIf { it.isDirectory }
        } catch (t: Throwable) {
            log("native dir from context failed ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    private fun findNativeLibraryDirFromClassLoader(): File? {
        val text = javaClass.classLoader?.toString().orEmpty()
        val apkPath = Regex("/data/app/[^\\s\\]]*com\\.multiapp\\.tools\\.rncapture[^\\s\\]]*/base\\.apk")
            .find(text)
            ?.value
            ?: return null
        val apkParent = File(apkPath).parentFile ?: return null
        val candidates = listOf(
            File(apkParent, "lib/arm64"),
            File(apkParent, "lib/arm"),
            File(apkParent, "lib/${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    private fun currentApplication(): Application? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryHookJiaguLibrary(stage: String) {
        if (jiaguLibraryHooked.get()) return
        try {
            val bridge = NativeHookBridge.getInstance()
            bridge.gotHookLibrary(JIAGU_LIB)
            jiaguLibraryHooked.set(true)
            log("gotHookLibrary $JIAGU_LIB stage=$stage success")
        } catch (t: Throwable) {
            log("gotHookLibrary $JIAGU_LIB stage=$stage failed ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun tryRegisterStubs(stage: String) {
        if (stubsRegistered.get()) return
        val classLoader = targetClassLoader ?: return
        try {
            val bridge = NativeHookBridge.getInstance()
            val businessOk = bridge.registerBusinessStubs(classLoader)
            val qrencryptOk = bridge.registerQrencryptStubs(classLoader)
            val chapterStateOk = bridge.registerOnlineChapterStateStubs(classLoader)
            val chapterDownloadOk = bridge.registerOnlineChapterDownloadFallbackStubs(classLoader)
            val missingCount = bridge.registerAllMissingNativeMethods(classLoader)
            if (businessOk || qrencryptOk || missingCount > 0) {
                stubsRegistered.set(true)
            }
            log("registerStubs stage=$stage businessOk=$businessOk qrencryptOk=$qrencryptOk chapterStateOk=$chapterStateOk chapterDownloadOk=$chapterDownloadOk missingCount=$missingCount registered=${stubsRegistered.get()}")
        } catch (t: Throwable) {
            log("registerStubs stage=$stage failed ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun log(message: String) {
        Log.w(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    companion object {
        private const val TAG = "YWLoginRNCapture"
        private const val TARGET_PACKAGE = "com.qq.reader"
        private const val MODULE_PACKAGE = "com.multiapp.tools.rncapture"
        private const val JIAGU_LIB = "libjiagu_vip.so"
        private const val MAIN_NATIVE_LIB = "libmultiapp-native.so"
        private val NATIVE_LOAD_ORDER = listOf(
            "libc++_shared.so",
            "libshadowhook.so",
            "libshadowhook_nothing.so",
            "liblsplant.so",
            MAIN_NATIVE_LIB
        )
        private val isInstalled = AtomicBoolean(false)
        private val isNativeLoaded = AtomicBoolean(false)
        private val jiaguLibraryHooked = AtomicBoolean(false)
        private val stubsRegistered = AtomicBoolean(false)
    }
}

package com.multiapp.core.loader

import android.content.Context
import android.util.Log
import com.multiapp.core.hook.HookEngine
import java.io.File
import java.lang.reflect.Method

/**
 * WeChat (com.tencent.mm) process-name compatibility.
 *
 * Root cause (verified on-device + dexdump 2026-08-04): WeChat process-name
 * getter in5.f1.a() (dex descriptor Lin5/f1;) reads the CURRENT process name via
 *  1. Application.getProcessName()
 *  2. reflection on ActivityThread.currentProcessName()
 *  3. /proc/self/cmdline fallback
 * The first two are Java-side and return the real host process name
 * com.multiapp.app:vN. WeChat ProcessDescriptor (com.tencent.mm.app.t5)
 * then derives suffix ":vN", fails the enum lookup, and background pool
 * threads crash with NPE (x75.a$$b.run / x75.a$$c.run) followed by
 * System.exit(1).
 *
 * The /proc/self/cmdline spoof (native fopen path) cannot fix this because
 * WeChat resolves the name from Java first. This profile installs an LSPlant
 * hook on in5.f1.a() that returns the guest identity "com.tencent.mm", so
 * in5.f1.b() yields no suffix and t5.a() resolves to the MM descriptor.
 *
 * NOTE on the class name: dexdump prints the raw type descriptor "Lin5/f1;"
 * whose leading 'L' is the dex descriptor marker, not part of the name. The
 * binary name used by Class.forName is therefore "in5.f1" (package in5, class
 * f1), NOT "lin5.f1". Using the wrong name here fails with
 * ClassNotFoundException even though the class is loadable from the guest
 * ClassLoader.
 */
object MicroMsgProcessNameCompat {

    private const val TAG = "MicroMsgProcessNameCompat"

    const val GUEST_PROCESS_NAME = "com.tencent.mm"

    /**
     * Obfuscated process-name holder observed in the current WeChat fixture.
     * b() derives the suffix from a(); both cache into the static array [a].
     */
    const val PROCESS_NAME_HOLDER_CLASS = "in5.f1"

    /**
     * The static no-arg process-name getter on [PROCESS_NAME_HOLDER_CLASS].
     */
    const val PROCESS_NAME_GETTER = "a"

    data class HookResult(
        val holderClassFound: Boolean = false,
        val getterMethodFound: Boolean = false,
        val hooked: Boolean = false
    ) {
        val anyInstalled: Boolean get() = hooked
    }

    fun isMicroMsgPackage(packageName: String?): Boolean =
        packageName == "com.tencent.mm" || (packageName?.startsWith("com.tencent.mm.") == true)

    /**
     * Resolves the getter Method on the guest class loader. Exposed for tests.
     */
    fun resolveProcessNameGetter(guestCl: ClassLoader): Method? = try {
        val holder = Class.forName(PROCESS_NAME_HOLDER_CLASS, false, guestCl)
        holder.declaredMethods.firstOrNull {
            it.name == PROCESS_NAME_GETTER &&
                it.parameterCount == 0 &&
                it.returnType == String::class.java &&
                java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
    } catch (e: Throwable) {
        safeLog("resolveProcessNameGetter skipped: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Installs the hook so the getter returns [GUEST_PROCESS_NAME].
     */
    fun installProcessNameHook(
        guestCl: ClassLoader,
        hookEngine: HookEngine
    ): HookResult {
        val holderFound = try {
            Class.forName(PROCESS_NAME_HOLDER_CLASS, false, guestCl)
            true
        } catch (e: Throwable) {
            safeLog("holder class not found: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        val getter = resolveProcessNameGetter(guestCl)
        val getterFound = getter != null
        val hooked = if (getter != null) {
            runCatching {
                hookEngine.hookMethod(
                    getter,
                    afterCallback = { _, _, _ ->
                        safeLog("process name getter intercepted - $GUEST_PROCESS_NAME")
                        GUEST_PROCESS_NAME
                    }
                )
            }.getOrElse { e ->
                safeLog("hook install failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        } else {
            safeLog("process name getter not found on $PROCESS_NAME_HOLDER_CLASS")
            false
        }
        val result = HookResult(
            holderClassFound = holderFound,
            getterMethodFound = getterFound,
            hooked = hooked
        )
        safeLog("Hook result: $result")
        return result
    }


    data class DataDirHookResult(
        val contextClassFound: Boolean = false,
        val getterMethodFound: Boolean = false,
        val hooked: Boolean = false
    )

    /**
     * WeChat's embedded Flutter engine calls Context.getDir("flutter") during
     * FlutterLoader.initResources. Inside the virtual container this can run on
     * a framework ContextImpl whose LoadedApk has a null dataDir (the guest
     * identity is injected but the dataDir fields were never populated), which
     * makes android.app.ContextImpl.getDataDir() throw
     * "No data directory found for package com.tencent.mm".
     *
     * This installs an LSPlant around-hook on ContextImpl.getDataDir():
     *  - original non-null result -> pass through unchanged;
     *  - original throws / returns null AND the receiver is a guest package
     *    (origin or virtual) -> return the instance data dir so getDir() has a
     *    writable root (the same dir the guest LoadedApk is patched to use);
     *  - original fails for a NON-guest package -> rethrow, never mask host
     *    context failures.
     */
    fun installDataDirFallbackHook(
        guestDataDir: String,
        originPackageName: String,
        virtualPackageName: String,
        hookEngine: HookEngine
    ): DataDirHookResult {
        val contextImpl = try {
            Class.forName("android.app.ContextImpl")
        } catch (e: Throwable) {
            safeLog("ContextImpl class not found: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val getter = contextImpl?.declaredMethods?.firstOrNull {
            it.name == "getDataDir" && it.parameterCount == 0 && it.returnType == File::class.java
        }
        val hooked = if (getter != null) {
            runCatching {
                hookEngine.hookMethodAround(getter) { receiver, _, callOriginal ->
                    val originalResult = runCatching { callOriginal(emptyArray()) }
                    val original = originalResult.getOrNull()
                    if (original != null) {
                        original
                    } else {
                        val packageName = (receiver as? Context)
                            ?.let { runCatching { it.packageName }.getOrNull() }
                        if (shouldFallbackToGuestDataDir(packageName, originPackageName, virtualPackageName)) {
                            safeLog(
                                "getDataDir fallback triggered: context=${receiver?.javaClass?.name}, " +
                                    "package=$packageName, guestDataDir=$guestDataDir"
                            )
                            File(guestDataDir)
                        } else {
                            throw originalResult.exceptionOrNull()
                                ?: IllegalStateException(
                                    "No data directory found for package $packageName (non-guest, not substituted)"
                                )
                        }
                    }
                }
            }.getOrElse { e ->
                safeLog("getDataDir fallback hook install failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        } else {
            safeLog("ContextImpl.getDataDir method not found")
            false
        }
        val result = DataDirHookResult(
            contextClassFound = contextImpl != null,
            getterMethodFound = getter != null,
            hooked = hooked
        )
        safeLog("DataDir fallback hook result: $result")
        return result
    }

    /**
     * Decision rule for the getDataDir fallback. Exposed for JVM tests.
     */
    fun shouldFallbackToGuestDataDir(
        packageName: String?,
        originPackageName: String,
        virtualPackageName: String
    ): Boolean =
        packageName == originPackageName || packageName == virtualPackageName

    /**
     * Pure fallback resolution: keep a valid original data dir, otherwise use
     * the guest instance data dir. Exposed for JVM tests.
     */
    fun resolveFallbackDataDir(original: File?, guestDataDir: File): File =
        original ?: guestDataDir

    private fun safeLog(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            // JVM unit tests ship android.util.Log as a throwing stub.
        }
    }
}
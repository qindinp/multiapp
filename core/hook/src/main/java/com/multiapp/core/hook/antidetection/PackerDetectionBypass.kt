package com.multiapp.core.hook.antidetection

import com.multiapp.core.hook.HookEngine
import timber.log.Timber
import java.lang.reflect.Method

/**
 * Packer detection bypass -- hooks Java-layer detection methods used by
 * commercial packers / protectors (360 Jiagu, Tencent Jiagu, iJiami, Bangcle).
 *
 * These packers embed environment checks in their Java stubs to detect
 * hook frameworks, root, emulators, and debugging.  This class intercepts
 * those checks via LSPlant (through [HookEngine]) so the protected app
 * sees a clean environment.
 */
object PackerDetectionBypass {

    private const val TAG = "PackerBypass"

    // Class names that indicate hook frameworks -- used by Class.forName / loadClass interceptors
    private val HOOK_INDICATORS = setOf(
        "xposed", "lsplant", "lspatch", "substrate", "frida", "multiapp"
    )

    /**
     * Apply packer-specific + universal bypass hooks.
     *
     * @param hookEngine The LSPlant-backed hook engine
     * @param packerType One of "360 Jiagu", "360", "Tencent Jiagu", "Tencent",
     *                   "iJiami", "Bangcle", or "universal" (universal-only)
     */
    fun apply(hookEngine: HookEngine, packerType: String) {
        Timber.tag(TAG).i("Applying packer detection bypass for: $packerType")

        when (packerType) {
            "360 Jiagu", "360" -> bypass360Jiagu(hookEngine)
            "Tencent Jiagu", "Tencent" -> bypassTencentJiagu(hookEngine)
            "iJiami" -> bypassIJiami(hookEngine)
            "Bangcle" -> bypassBangcle(hookEngine)
            "universal" -> { /* universal hooks only */ }
        }

        bypassUniversalChecks(hookEngine)
        Timber.tag(TAG).i("Packer detection bypass applied")
    }

    // ---------------------------------------------------------------------------
    // 360 Jiagu
    // ---------------------------------------------------------------------------

    /**
     * 360 Jiagu Java-layer detection bypass.
     *
     * The primary detection lives in native `libjiagu.so`, but some versions
     * also have Java stubs under `com.qihoo.util.*` that check for Xposed,
     * root, emulator, and debug state.
     */
    private fun bypass360Jiagu(hookEngine: HookEngine) {
        val targets = listOf(
            "com.qihoo.util.Utils" to "isXposedExists",
            "com.qihoo.util.Utils" to "isRooted",
            "com.qihoo.util.Utils" to "isEmulator",
            "com.qihoo.util.Utils" to "isDebug",
            "com.qihoo.util.StubApp" to "isXposedExist",
        )
        for ((className, methodName) in targets) {
            tryHookBooleanMethod(hookEngine, className, methodName, returnValue = false)
        }
    }

    // ---------------------------------------------------------------------------
    // Tencent Jiagu (Legu)
    // ---------------------------------------------------------------------------

    /** Tencent Legu Java-layer detection bypass. */
    private fun bypassTencentJiagu(hookEngine: HookEngine) {
        val targets = listOf(
            "com.tencent.StubShell.TxAppEntry" to "isXposed",
            "com.tencent.StubShell.TxAppEntry" to "isRoot",
        )
        for ((className, methodName) in targets) {
            tryHookBooleanMethod(hookEngine, className, methodName, returnValue = false)
        }
    }

    // ---------------------------------------------------------------------------
    // iJiami
    // ---------------------------------------------------------------------------

    /** iJiami Java-layer detection bypass. */
    private fun bypassIJiami(hookEngine: HookEngine) {
        tryHookBooleanMethod(hookEngine, "com.ijiami.armc.loader", "isXposed", returnValue = false)

        // checkEnvironment returns int: 0 == safe
        tryHookIntMethod(hookEngine, "com.ijiami.armc.loader", "checkEnvironment", returnValue = 0)
    }

    // ---------------------------------------------------------------------------
    // Bangcle
    // ---------------------------------------------------------------------------

    /** Bangcle (SecShell) Java-layer detection bypass. */
    private fun bypassBangcle(hookEngine: HookEngine) {
        tryHookBooleanMethod(hookEngine, "com.secshell.shellWrapper", "isXposed", returnValue = false)
    }

    // ---------------------------------------------------------------------------
    // Universal checks
    // ---------------------------------------------------------------------------

    /**
     * Intercept [Class.forName] and [ClassLoader.loadClass] to prevent
     * discovery of hook-framework class names.  If a requested class name
     * contains a known indicator, we throw [ClassNotFoundException] so the
     * packer believes the class does not exist.
     */
    private fun bypassUniversalChecks(hookEngine: HookEngine) {
        hookClassForName(hookEngine)
        hookClassLoaderLoadClass(hookEngine)
    }

    private fun hookClassForName(hookEngine: HookEngine) {
        // Class.forName has multiple overloads; try the 3-arg version first, then 1-arg
        val method = findMethod(
            "java.lang.Class",
            "forName",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            ClassLoader::class.java
        ) ?: findMethod(
            "java.lang.Class",
            "forName",
            String::class.java
        ) ?: run {
            Timber.tag(TAG).w("Class.forName not found -- skipping")
            return
        }

        val hooked = hookEngine.hookMethod(
            method,
            beforeCallback = { _, args ->
                val className = args.firstOrNull() as? String ?: return@hookMethod null
                if (containsHookIndicator(className)) {
                    Timber.tag(TAG).d("Blocking Class.forName for: $className")
                    throw ClassNotFoundException("Class not found: $className")
                }
                null // proceed with original args
            }
        )
        if (hooked) Timber.tag(TAG).d("Class.forName hook installed")
    }

    private fun hookClassLoaderLoadClass(hookEngine: HookEngine) {
        val method = findMethod(
            "java.lang.ClassLoader",
            "loadClass",
            String::class.java
        ) ?: run {
            Timber.tag(TAG).w("ClassLoader.loadClass not found -- skipping")
            return
        }

        val hooked = hookEngine.hookMethod(
            method,
            beforeCallback = { _, args ->
                val className = args.firstOrNull() as? String ?: return@hookMethod null
                if (containsHookIndicator(className)) {
                    Timber.tag(TAG).d("Blocking ClassLoader.loadClass for: $className")
                    throw ClassNotFoundException("Class not found: $className")
                }
                null
            }
        )
        if (hooked) Timber.tag(TAG).d("ClassLoader.loadClass hook installed")
    }

    private fun containsHookIndicator(className: String): Boolean {
        val lower = className.lowercase()
        return HOOK_INDICATORS.any { lower.contains(it) }
    }

    // ---------------------------------------------------------------------------
    // Helper: hook a boolean-returning method to always return [returnValue]
    // ---------------------------------------------------------------------------

    private fun tryHookBooleanMethod(
        hookEngine: HookEngine,
        className: String,
        methodName: String,
        returnValue: Boolean
    ) {
        val method = findMethod(className, methodName) ?: return
        try {
            hookEngine.hookMethod(
                method,
                afterCallback = { _, _, _ -> returnValue }
            )
            Timber.tag(TAG).d("Hooked $className.$methodName -> $returnValue")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to hook $className.$methodName: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Helper: hook an int-returning method to always return [returnValue]
    // ---------------------------------------------------------------------------

    private fun tryHookIntMethod(
        hookEngine: HookEngine,
        className: String,
        methodName: String,
        returnValue: Int
    ) {
        val method = findMethod(className, methodName) ?: return
        try {
            hookEngine.hookMethod(
                method,
                afterCallback = { _, _, _ -> returnValue }
            )
            Timber.tag(TAG).d("Hooked $className.$methodName -> $returnValue")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to hook $className.$methodName: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Helper: safely resolve a Method via reflection
    // ---------------------------------------------------------------------------

    private fun findMethod(
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>
    ): Method? {
        return try {
            val clazz = Class.forName(className)
            clazz.getDeclaredMethod(methodName, *paramTypes).also { it.isAccessible = true }
        } catch (_: ClassNotFoundException) {
            Timber.tag(TAG).d("Class not found: $className (expected for some packer versions)")
            null
        } catch (_: NoSuchMethodException) {
            Timber.tag(TAG).d("Method not found: $className.$methodName")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error resolving $className.$methodName: ${e.message}")
            null
        }
    }
}

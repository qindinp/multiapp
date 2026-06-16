package com.multiapp.core.hook

import timber.log.Timber
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
/**
 * HookEngine — Unified hook management for MultiApp.
 *
 * 支持:
 * - LSPlant ART 级方法 hook（Java + JNI）
 * - 静态/实例字段修改（通过 HiddenApiBypass 兼容 Android 14+）
 * - 全局单例（getInstance），所有 hook 模块共享同一实例
 * - 线程安全（CopyOnWriteArrayList + ConcurrentHashMap）
 *
 * 注意：不使用 Hilt @Singleton/@Inject，因为 LoaderFactory 在 AppComponentFactory 阶段
 * 运行，此时 Hilt 尚未初始化。所有调用方通过 getInstance() 获取全局单例。
 */
class HookEngine private constructor() {

    companion object {
        private const val TAG = "HookEngine"

        /**
         * 全局单例，所有 hook 模块共享同一个实例
         * 避免 FileSystemHook、SignatureBypass 等各自 new HookEngine() 导致
         * installedHooks 列表不共享，unhookAll() 无法清理全部 hook
         */
        @Volatile
        private var instance: HookEngine? = null

        fun getInstance(): HookEngine {
            return instance ?: synchronized(this) {
                instance ?: HookEngine().also { instance = it }
            }
        }

        fun resetInstance() {
            instance?.unhookAll()
            instance = null
        }
    }

    // Track installed hooks for cleanup — 线程安全
    private val installedHooks = CopyOnWriteArrayList<HookInfo>()

    // LSPlant state
    private var lsplantInitialized = false
    private val lsplantHooks = ConcurrentHashMap<Executable, Any>() // Executable -> SimpleHooker

    /**
     * Initialize LSPlant using native JNI implementation.
     * Uses ShadowHook as the inline hooker backend.
     */
    fun initLsplant(classLoader: ClassLoader): Boolean {
        if (lsplantInitialized) return true

        android.util.Log.i(TAG, "=== LSPlant.init() 开始 ===")
        android.util.Log.i(TAG, "ClassLoader: ${classLoader.javaClass.name}")

        val bridge = NativeHookBridge.getInstance()
        val result = bridge.initLsplant()
        lsplantInitialized = result

        if (result) {
            Timber.tag(TAG).i("LSPlant initialized successfully via native JNI")
            android.util.Log.i(TAG, "=== LSPlant.init() 成功 ===")
        } else {
            Timber.tag(TAG).e("LSPlant initialization failed")
            android.util.Log.e(TAG, "=== LSPlant.init() 失败 ===")
        }

        return result
    }

    /**
     * Hook a Java method using LSPlant.
     *
     * NOTE: This implementation operates in "skip-mode" only.
     * - beforeCallback returning null → skip original, return default value
     * - beforeCallback returning non-null → still skip original (pass-through not supported)
     * - afterCallback → called with default value, not original result
     *
     * For Fock.sign and similar use cases where we want to skip the original method,
     * this is sufficient. Full pass-through support would require invoking the backup method.
     */
    fun hookMethod(
        method: Executable,
        beforeCallback: ((receiver: Any?, args: Array<Any?>) -> Array<Any?>?)? = null,
        afterCallback: ((receiver: Any?, args: Array<Any?>, result: Any?) -> Any?)? = null
    ): Boolean {
        if (!lsplantInitialized) {
            Timber.tag(TAG).w("LSPlant not initialized — cannot hook ${method.name}")
            android.util.Log.w(TAG, "hookMethod: LSPlant not initialized, cannot hook ${method.name}")
            return false
        }

        // Create a SimpleHooker that wraps the before/after callbacks
        lateinit var hooker: SimpleHooker
        hooker = SimpleHooker(method) { args ->
            val receiver = if (args.isNotEmpty() && !java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                args[0]
            } else null

            val methodArgs = if (args.isNotEmpty() && !java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                args.sliceArray(1 until args.size)
            } else {
                args
            }

            // Execute beforeCallback
            if (beforeCallback != null) {
                val beforeResult = beforeCallback(receiver, methodArgs)
                if (beforeResult == null) {
                    // null = skip original method, return default value
                    val defaultVal = SimpleHooker.getDefaultValue(
                        if (method is java.lang.reflect.Method) method.returnType else null
                    )
                    android.util.Log.d(TAG, "hookMethod: ${method.name} skipped by beforeCallback")
                    return@SimpleHooker defaultVal
                }
            }

            // For now, we don't call the original method (we're just intercepting)
            // If afterCallback is provided, call it with a null result
            if (afterCallback != null) {
                val defaultVal = SimpleHooker.getDefaultValue(
                    if (method is java.lang.reflect.Method) method.returnType else null
                )
                val afterResult = afterCallback(receiver, methodArgs, defaultVal)
                android.util.Log.d(TAG, "hookMethod: ${method.name} afterCallback returned: $afterResult")
                return@SimpleHooker afterResult
            }

            // Return default value
            SimpleHooker.getDefaultValue(
                if (method is java.lang.reflect.Method) method.returnType else null
            )
        }

        val bridge = NativeHookBridge.getInstance()
        val success = bridge.hookMethod(method, hooker)

        if (success) {
            lsplantHooks[method] = hooker
            installedHooks.add(HookInfo(
                type = HookType.LSPLANT_METHOD,
                target = "${method.declaringClass.name}.${method.name}",
                originalValue = null,
                executable = method
            ))
            Timber.tag(TAG).d("LSPlant hooked: ${method.declaringClass.name}.${method.name}")
            android.util.Log.i(TAG, "hookMethod: successfully hooked ${method.declaringClass.name}.${method.name}")
        } else {
            Timber.tag(TAG).w("LSPlant.hook() failed for ${method.name}")
            android.util.Log.w(TAG, "hookMethod: failed to hook ${method.name}")
        }

        return success
    }

    fun hookMethodPassThrough(
        method: Method,
        beforeCallback: ((receiver: Any?, args: Array<Any?>) -> Array<Any?>?)? = null,
        afterCallback: ((receiver: Any?, args: Array<Any?>, result: Any?) -> Any?)? = null
    ): Boolean {
        if (!lsplantInitialized) {
            Timber.tag(TAG).w("LSPlant not initialized - cannot pass-through hook ${method.name}")
            android.util.Log.w(TAG, "hookMethodPassThrough: LSPlant not initialized, cannot hook ${method.name}")
            return false
        }

        lateinit var hooker: SimpleHooker
        hooker = SimpleHooker(method) { args ->
            val receiver = if (args.isNotEmpty() && !java.lang.reflect.Modifier.isStatic(method.modifiers)) args[0] else null
            val originalMethodArgs = if (args.isNotEmpty() && !java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                args.sliceArray(1 until args.size)
            } else {
                args
            }

            val callArgs = if (beforeCallback != null) {
                val replacementArgs = beforeCallback(receiver, originalMethodArgs)
                if (replacementArgs != null) {
                    if (java.lang.reflect.Modifier.isStatic(method.modifiers)) replacementArgs else arrayOf(receiver, *replacementArgs)
                } else {
                    args
                }
            } else {
                args
            }

            val result = try {
                hooker.callOriginal(callArgs)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "hookMethodPassThrough: callOriginal failed for ${method.name}: ${e.message}", e)
                throw e
            }

            if (afterCallback != null) {
                val afterArgs = if (callArgs.isNotEmpty() && !java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                    callArgs.sliceArray(1 until callArgs.size)
                } else {
                    callArgs
                }
                afterCallback(receiver, afterArgs, result)
            } else {
                result
            }
        }

        val bridge = NativeHookBridge.getInstance()
        val backup = bridge.hookMethodWithBackup(method, hooker)
        if (backup != null) {
            hooker.setBackup(backup)
            lsplantHooks[method] = hooker
            installedHooks.add(HookInfo(
                type = HookType.LSPLANT_METHOD,
                target = "${method.declaringClass.name}.${method.name}",
                originalValue = null,
                executable = method
            ))
            android.util.Log.i(TAG, "hookMethodPassThrough: successfully hooked ${method.declaringClass.name}.${method.name}")
            return true
        }

        Timber.tag(TAG).w("LSPlant pass-through hook failed for ${method.name}")
        android.util.Log.w(TAG, "hookMethodPassThrough: failed to hook ${method.name}")
        return false
    }

    fun hookMethodAround(
        method: Method,
        callback: (receiver: Any?, args: Array<Any?>, callOriginal: (Array<Any?>) -> Any?) -> Any?
    ): Boolean {
        if (!lsplantInitialized) {
            Timber.tag(TAG).w("LSPlant not initialized - cannot around-hook ${method.name}")
            android.util.Log.w(TAG, "hookMethodAround: LSPlant not initialized, cannot hook ${method.name}")
            return false
        }

        lateinit var hooker: SimpleHooker
        hooker = SimpleHooker(method) { args ->
            val isStatic = java.lang.reflect.Modifier.isStatic(method.modifiers)
            val receiver = if (args.isNotEmpty() && !isStatic) args[0] else null
            val methodArgs = if (args.isNotEmpty() && !isStatic) args.sliceArray(1 until args.size) else args
            val originalCaller: (Array<Any?>) -> Any? = { replacementArgs ->
                val callArgs = if (isStatic) replacementArgs else arrayOf(receiver, *replacementArgs)
                hooker.callOriginal(callArgs)
            }
            callback(receiver, methodArgs, originalCaller)
        }

        val bridge = NativeHookBridge.getInstance()
        val backup = bridge.hookMethodWithBackup(method, hooker)
        if (backup != null) {
            hooker.setBackup(backup)
            lsplantHooks[method] = hooker
            installedHooks.add(HookInfo(
                type = HookType.LSPLANT_METHOD,
                target = "${method.declaringClass.name}.${method.name}",
                originalValue = null,
                executable = method
            ))
            android.util.Log.i(TAG, "hookMethodAround: successfully hooked ${method.declaringClass.name}.${method.name}")
            return true
        }

        android.util.Log.w(TAG, "hookMethodAround: failed to hook ${method.name}")
        return false
    }

    fun hookStaticField(className: String, fieldName: String, newValue: Any?): Boolean {
        return try {
            val clazz = Class.forName(className)
            val field = com.multiapp.core.common.findField(clazz, fieldName)
                ?: throw NoSuchFieldException("$fieldName not found in $className")
            field.isAccessible = true

            // 使用 HiddenApiBypass 兼容的 removeFinalModifier
            com.multiapp.core.common.removeFinalModifier(field)

            val oldValue = field.get(null)
            field.set(null, newValue)

            installedHooks.add(HookInfo(
                type = HookType.STATIC_FIELD,
                target = "$className.$fieldName",
                originalValue = oldValue
            ))

            Timber.tag(TAG).d("Hooked static field: $className.$fieldName = $newValue")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook static field: $className.$fieldName")
            false
        }
    }

    fun hookInstanceField(instance: Any, fieldName: String, newValue: Any?): Boolean {
        return try {
            var clazz: Class<*>? = instance::class.java
            var field: java.lang.reflect.Field? = null

            while (clazz != null && field == null) {
                try {
                    field = clazz.getDeclaredField(fieldName)
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }

            if (field == null) {
                Timber.tag(TAG).e("Field not found: $fieldName")
                return false
            }

            field.isAccessible = true
            val oldValue = field.get(instance)
            field.set(instance, newValue)

            installedHooks.add(HookInfo(
                type = HookType.INSTANCE_FIELD,
                target = "${instance::class.java.name}.$fieldName",
                originalValue = oldValue,
                instance = instance
            ))

            Timber.tag(TAG).d("Hooked instance field: ${instance::class.java.simpleName}.$fieldName")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook instance field: $fieldName")
            false
        }
    }

    fun unhookAll() {
        Timber.tag(TAG).d("Clearing ${lsplantHooks.size} LSPlant hooks")
        lsplantHooks.clear()

        for (hook in installedHooks.reversed()) {
            try {
                when (hook.type) {
                    HookType.STATIC_FIELD -> {
                        val parts = hook.target.split(".")
                        val className = parts.dropLast(1).joinToString(".")
                        val fieldName = parts.last()
                        val clazz = Class.forName(className)
                        val field = clazz.getDeclaredField(fieldName)
                        field.isAccessible = true
                        field.set(null, hook.originalValue)
                    }
                    HookType.INSTANCE_FIELD -> {
                        val fieldName = hook.target.substringAfterLast(".")
                        val instance = hook.instance ?: continue
                        val field = instance::class.java.getDeclaredField(fieldName)
                        field.isAccessible = true
                        field.set(instance, hook.originalValue)
                    }
                    HookType.LSPLANT_METHOD -> {
                        try {
                            val bridge = NativeHookBridge.getInstance()
                            bridge.unhookMethod(hook.executable ?: continue)
                        } catch (e: Throwable) {
                            Timber.tag(TAG).e(e, "Failed to unhook LSPlant method: ${hook.target}")
                        }
                    }
                    else -> { }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to unhook: ${hook.target}")
            }
        }

        val count = installedHooks.size
        installedHooks.clear()
        Timber.tag(TAG).i("Unhooked $count hooks")
    }

    fun getHookCount(): Int = installedHooks.size

    private data class HookInfo(
        val type: HookType,
        val target: String,
        val originalValue: Any? = null,
        val instance: Any? = null,
        val executable: Executable? = null
    )

    private enum class HookType {
        STATIC_FIELD,
        INSTANCE_FIELD,
        METHOD_PROXY,
        LSPLANT_METHOD,
        NATIVE_INLINE,
        NATIVE_PLT
    }
}

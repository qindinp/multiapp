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
    private val lsplantHooks = ConcurrentHashMap<Executable, Any>() // Executable -> LSPlant.Unhook

    fun initLsplant(classLoader: ClassLoader): Boolean {
        if (lsplantInitialized) return true
        return initLsWithRetry(classLoader)
    }

    private fun initLsWithRetry(classLoader: ClassLoader, maxRetries: Int = 3): Boolean {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val lsplantClass = Class.forName("io.github.lsplant.LSPlant")
                val initMethod = lsplantClass.getMethod("init", ClassLoader::class.java)
                val result = initMethod.invoke(null, classLoader) as Boolean
                lsplantInitialized = result
                if (result) {
                    Timber.tag(TAG).i("LSPlant initialized successfully")
                    return true
                } else {
                    Timber.tag(TAG).w("LSPlant.init() returned false (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                lastException = e
                Timber.tag(TAG).w("LSPlant init attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < maxRetries - 1) {
                    Thread.sleep(100L shl attempt)
                }
            }
        }
        Timber.tag(TAG).e(lastException, "LSPlant init failed after $maxRetries attempts")
        return false
    }

    fun hookMethod(
        method: Executable,
        beforeCallback: ((receiver: Any?, args: Array<Any?>) -> Array<Any?>?)? = null,
        afterCallback: ((receiver: Any?, args: Array<Any?>, result: Any?) -> Any?)? = null
    ): Boolean {
        if (!lsplantInitialized) {
            Timber.tag(TAG).w("LSPlant not initialized — cannot hook ${method.name}")
            return false
        }

        return try {
            val lsplantClass = Class.forName("io.github.lsplant.LSPlant")

            val beforeCallbackImpl = beforeCallback?.let { cb ->
                createBeforeCallback(cb, method)
            }

            val afterCallbackImpl = afterCallback?.let { cb ->
                createAfterCallback(cb, method)
            }

            val hookMethod = lsplantClass.getMethod(
                "hook",
                Executable::class.java,
                Class.forName("io.github.lsplant.LSPlant\$MethodHookCallback"),
                Class.forName("io.github.lsplant.LSPlant\$MethodUnhookCallback")
            )

            val unhook = hookMethod.invoke(null, method, beforeCallbackImpl, afterCallbackImpl)
            if (unhook != null) {
                lsplantHooks[method] = unhook
                installedHooks.add(HookInfo(
                    type = HookType.LSPLANT_METHOD,
                    target = "${method.declaringClass.name}.${method.name}",
                    originalValue = null
                ))
                Timber.tag(TAG).d("LSPlant hooked: ${method.declaringClass.name}.${method.name}")
                true
            } else {
                Timber.tag(TAG).w("LSPlant.hook() returned null for ${method.name}")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to LSPlant hook ${method.declaringClass.name}.${method.name}")
            false
        }
    }

    private fun createBeforeCallback(
        callback: (receiver: Any?, args: Array<Any?>) -> Array<Any?>?,
        method: Executable
    ): Any {
        val callbackClass = Class.forName("io.github.lsplant.LSPlant\$MethodHookCallback")
        return java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "before" -> {
                    val receiver = proxyArgs?.getOrNull(0)
                    val methodArgs = proxyArgs?.getOrNull(1) as? Array<Any?> ?: emptyArray()
                    val result = callback(receiver, methodArgs)
                    if (result != null && result !== methodArgs) {
                        // Callback returned modified args — copy them back to the original array
                        // and return false (don't skip the method)
                        for (i in methodArgs.indices) {
                            if (i < result.size) {
                                (proxyArgs[1] as Array<Any?>)[i] = result[i]
                            }
                        }
                        false
                    } else {
                        // No changes — return false (don't skip)
                        false
                    }
                }
                else -> null
            }
        }
    }

    private fun createAfterCallback(
        callback: (receiver: Any?, args: Array<Any?>, result: Any?) -> Any?,
        method: Executable
    ): Any {
        val callbackClass = Class.forName("io.github.lsplant.LSPlant\$MethodHookCallback")
        return java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, proxyMethod, args ->
            when (proxyMethod.name) {
                "after" -> {
                    val receiver = args?.getOrNull(0)
                    val methodArgs = args?.getOrNull(1) as? Array<Any?> ?: emptyArray()
                    val result = args?.getOrNull(2)
                    callback(receiver, methodArgs, result)
                }
                else -> null
            }
        }
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
        for ((method, unhook) in lsplantHooks) {
            try {
                val unhookClass = unhook::class.java
                val unhookMethod = unhookClass.getMethod("unhook")
                unhookMethod.invoke(unhook)
                Timber.tag(TAG).d("LSPlant unhooked: ${method.declaringClass.name}.${method.name}")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to LSPlant unhook: ${method.name}")
            }
        }
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
                    HookType.LSPLANT_METHOD -> { }
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
        val instance: Any? = null
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

package com.multiapp.core.hook

import timber.log.Timber
import java.lang.reflect.Executable
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HookEngine — Unified hook management for MultiApp.
 *
 * Supports:
 * - Java method hooking via reflection (no native required)
 * - Proxy-based interception (InvocationHandler)
 * - ART method hooking via LSPlant (Android 5-17, including Android 16)
 *
 * For Phase 1, we use pure Java/Kotlin reflection which doesn't require
 * native libraries. This works for:
 * - Static field modification (Build.* spoofing)
 * - Singleton replacement (IActivityManager, IPackageManager)
 * - Handler.Callback injection (ActivityThread.mH)
 * - ClassLoader swapping
 *
 * For Phase 3+, LSPlant provides ART-level method hooking that intercepts
 * calls at the ART runtime level, covering both Java and JNI methods.
 */
@Singleton
class HookEngine @Inject constructor() {

    companion object {
        private const val TAG = "HookEngine"
    }

    // Track installed hooks for cleanup
    private val installedHooks = mutableListOf<HookInfo>()

    // LSPlant state
    private var lsplantInitialized = false
    private val lsplantHooks = mutableMapOf<Executable, Any>() // Executable -> LSPlant.Unhook

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
        ) { _, proxyMethod, args ->
            when (proxyMethod.name) {
                "before" -> {
                    val receiver = args?.getOrNull(0)
                    val methodArgs = args?.getOrNull(1) as? Array<Any?> ?: emptyArray()
                    callback(receiver, methodArgs) != null
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
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true

            try {
                val accessFlagsField = java.lang.reflect.Field::class.java
                    .getDeclaredField("accessFlags")
                accessFlagsField.isAccessible = true
                accessFlagsField.setInt(
                    field,
                    field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
                )
            } catch (_: Exception) {
                val modField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
                modField.isAccessible = true
                modField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
            }

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

package com.multiapp.core.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * SimpleHooker — LSPlant hook 回调类
 *
 * 当被 hook 的方法被调用时，LSPlant 会调用此对象的 callback 方法。
 * callback 返回的值将作为被 hook 方法的返回值。
 *
 * 用法：
 *   val hooker = SimpleHooker(targetMethod) { args -> "replacement value" }
 *   NativeHookBridge.hookMethod(targetMethod, hooker)
 */
class SimpleHooker(
    private val method: Executable,
    private val swallowCallbackExceptions: Boolean = true,
    private val handler: (Array<Any?>) -> Any?
) {
    private val isStatic = Modifier.isStatic(method.modifiers)
    private val returnType = if (method is Method) method.returnType else null
    @Volatile
    private var backup: Executable? = null

    fun setBackup(backup: Executable) {
        backup.isAccessible = true
        this.backup = backup
    }

    fun callOriginal(args: Array<Any?>): Any? {
        val backupMethod = backup
            ?: throw IllegalStateException("Backup not set for ${method.name}")
        val receiver = if (isStatic) null else args.firstOrNull()
        val methodArgs = if (isStatic) args else args.drop(1).toTypedArray()

        return try {
            if (backupMethod is java.lang.reflect.Method) {
                backupMethod.invoke(receiver, *methodArgs)
            } else if (backupMethod is java.lang.reflect.Constructor<*>) {
                backupMethod.newInstance(*methodArgs)
            } else {
                throw IllegalStateException(
                    "Unsupported backup type: ${backupMethod?.javaClass?.name}"
                )
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    /**
     * Called by LSPlant when the hooked method is invoked.
     * This method must have the exact signature: callback(Object[])Object
     *
     * @param args The arguments passed to the original method.
     *             For instance methods, args[0] is the receiver (this).
     * @return The value to return from the hooked method.
     */
    fun callback(args: Array<Any?>): Any? {
        return try {
            handler(args)
        } catch (e: Throwable) {
            android.util.Log.e("SimpleHooker", "callback error for ${method.name}: ${e.message}", e)
            if (swallowCallbackExceptions) {
                getDefaultValue(returnType)
            } else {
                throw e
            }
        }
    }

    companion object {
        /**
         * Get the default value for a given type (0 for primitives, null for objects).
         */
        fun getDefaultValue(type: Class<*>?): Any? {
            if (type == null) return null
            return when (type) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Char::class.javaPrimitiveType -> 0.toChar()
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0.0f
                Double::class.javaPrimitiveType -> 0.0
                Void.TYPE -> null
                else -> null
            }
        }

        /**
         * Create a SimpleHooker that returns a fixed value when the method is called.
         */
        fun returning(method: Executable, value: Any?): SimpleHooker {
            return SimpleHooker(method) { value }
        }

        /**
         * Create a SimpleHooker that returns the default value for the method's return type.
         */
        fun skipMethod(method: Executable): SimpleHooker {
            val returnType = if (method is Method) method.returnType else null
            return SimpleHooker(method) { getDefaultValue(returnType) }
        }

        /**
         * Create a SimpleHooker that returns an empty string for String-returning methods.
         */
        fun returnEmptyString(method: Executable): SimpleHooker {
            return SimpleHooker(method) { "" }
        }
    }
}

package de.robv.android.xposed

import com.multiapp.core.common.findField
import com.multiapp.core.common.findMethod
import timber.log.Timber
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object XposedHelpers {
    private const val TAG = "XposedHelpers"

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader): Class<*> {
        return Class.forName(className, true, classLoader)
    }

    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(className, true, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    @JvmStatic
    fun findMethodExact(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Any?
    ): Method {
        val clazz = findClass(className, classLoader)
        return findMethodExact(clazz, methodName, *parameterTypes)
    }

    @JvmStatic
    fun findMethodExact(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any?
    ): Method {
        val resolvedTypes = resolveParameterTypes(parameterTypes, clazz.classLoader)
        @Suppress("UNCHECKED_CAST")
        val method = findMethod(clazz, methodName, resolvedTypes as Array<Class<*>>)
            ?: throw NoSuchMethodException("${clazz.name}.$methodName(${resolvedTypes.joinToString { it.name }})")
        method.isAccessible = true
        return method
    }

    @JvmStatic
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook {
        if (parameterTypesAndCallback.isEmpty()) {
            throw IllegalArgumentException("callback not found")
        }

        val callback = parameterTypesAndCallback.last() as? XC_MethodHook
            ?: throw IllegalArgumentException("last argument must be XC_MethodHook")

        val paramCount = parameterTypesAndCallback.size - 1
        val method = if (paramCount == 0) {
            findMethodExact(className, classLoader, methodName)
        } else {
            val parameterTypes: Array<Any?> = arrayOfNulls<Any?>(paramCount)
            for (i in 0 until paramCount) parameterTypes[i] = parameterTypesAndCallback[i]
            findMethodExact(className, classLoader, methodName, *parameterTypes)
        }
        return XposedBridge.hookMethod(method, callback)
    }

    @JvmStatic
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook {
        if (parameterTypesAndCallback.isEmpty()) {
            throw IllegalArgumentException("callback not found")
        }

        val callback = parameterTypesAndCallback.last() as? XC_MethodHook
            ?: throw IllegalArgumentException("last argument must be XC_MethodHook")

        val paramCount = parameterTypesAndCallback.size - 1
        val method = if (paramCount == 0) {
            findMethodExact(clazz, methodName)
        } else {
            val parameterTypes: Array<Any?> = arrayOfNulls<Any?>(paramCount)
            for (i in 0 until paramCount) parameterTypes[i] = parameterTypesAndCallback[i]
            findMethodExact(clazz, methodName, *parameterTypes)
        }
        return XposedBridge.hookMethod(method, callback)
    }

    @JvmStatic
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val parameterTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        val method = findMethod(obj.javaClass, methodName, parameterTypes as Array<Class<*>>)
            ?: throw NoSuchMethodException("${obj.javaClass.name}.$methodName")
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    @JvmStatic
    fun callStaticMethod(className: String, methodName: String, vararg args: Any?): Any? {
        val classLoader = XposedHelpers::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        val clazz = findClass(className, classLoader)
        return callStaticMethod(clazz, methodName, *args)
    }

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val parameterTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        val method = findMethod(clazz, methodName, parameterTypes as Array<Class<*>>)
            ?: throw NoSuchMethodException("${clazz.name}.$methodName")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    @JvmStatic
    fun getObjectField(obj: Any, fieldName: String): Any? {
        val field = findField(obj.javaClass, fieldName)
            ?: throw NoSuchFieldException("${obj.javaClass.name}.$fieldName")
        field.isAccessible = true
        return field.get(obj)
    }

    @JvmStatic
    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        val field = findField(obj.javaClass, fieldName)
            ?: throw NoSuchFieldException("${obj.javaClass.name}.$fieldName")
        field.isAccessible = true
        field.set(obj, value)
    }

    @JvmStatic
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        val field = findField(clazz, fieldName)
            ?: throw NoSuchFieldException("${clazz.name}.$fieldName")
        field.isAccessible = true
        return field.get(null)
    }

    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        val field = findField(clazz, fieldName)
            ?: throw NoSuchFieldException("${clazz.name}.$fieldName")
        field.isAccessible = true
        com.multiapp.core.common.removeFinalModifier(field)
        field.set(null, value)
    }

    @JvmStatic
    fun getIntField(obj: Any, fieldName: String): Int {
        return getObjectField(obj, fieldName) as Int
    }

    @JvmStatic
    fun setIntField(obj: Any, fieldName: String, value: Int) {
        setObjectField(obj, fieldName, value)
    }

    @JvmStatic
    fun getLongField(obj: Any, fieldName: String): Long {
        return getObjectField(obj, fieldName) as Long
    }

    @JvmStatic
    fun getBooleanField(obj: Any, fieldName: String): Boolean {
        return getObjectField(obj, fieldName) as Boolean
    }

    @JvmStatic
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) {
        setObjectField(obj, fieldName, value)
    }

    @JvmStatic
    fun getAdditionalInstanceField(obj: Any, key: String): Any? {
        return XposedBridgeImpl.getAdditionalField(obj, key)
    }

    @JvmStatic
    fun setAdditionalInstanceField(obj: Any, key: String, value: Any?): Any? {
        return XposedBridgeImpl.setAdditionalField(obj, key, value)
    }

    @JvmStatic
    fun removeAdditionalInstanceField(obj: Any, key: String): Any? {
        return XposedBridgeImpl.removeAdditionalField(obj, key)
    }

    private fun resolveParameterTypes(
        parameterTypes: Array<out Any?>,
        classLoader: ClassLoader?
    ): Array<Class<*>> {
        return Array<Class<*>>(parameterTypes.size) { i ->
            when (val pt = parameterTypes[i]) {
                is Class<*> -> pt
                is String -> {
                    if (classLoader != null) findClass(pt, classLoader)
                    else Class.forName(pt)
                }
                else -> throw IllegalArgumentException(
                    "Parameter type must be Class or String, got: ${pt?.javaClass?.name}"
                )
            }
        }
    }
}

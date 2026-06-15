package de.robv.android.xposed

import com.multiapp.core.common.findField
import com.multiapp.core.common.findMethod
import timber.log.Timber
import java.lang.reflect.Constructor
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
    fun findMethodBestMatch(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method {
        @Suppress("UNCHECKED_CAST")
        val method = findMethod(clazz, methodName, parameterTypes as Array<Class<*>>)
        if (method != null) {
            method.isAccessible = true
            return method
        }
        return findMethodByBestMatch(clazz, methodName, parameterTypes)
    }

    @JvmStatic
    fun findMethodBestMatch(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method {
        val clazz = findClass(className, classLoader)
        return findMethodBestMatch(clazz, methodName, *parameterTypes)
    }

    @JvmStatic
    fun findConstructorExact(
        clazz: Class<*>,
        vararg parameterTypes: Any?
    ): Constructor<*> {
        val resolvedTypes = resolveParameterTypes(parameterTypes, clazz.classLoader)
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val constructor = current.getDeclaredConstructor(*resolvedTypes)
                constructor.isAccessible = true
                return constructor
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException(
            "${clazz.name}.<init>(${resolvedTypes.joinToString { it.name }})"
        )
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
    fun hookAllMethods(
        hookClass: Class<*>,
        methodName: String,
        callback: XC_MethodHook
    ): Set<XC_MethodHook.Unhook> {
        val unhooks = mutableSetOf<XC_MethodHook.Unhook>()
        var current: Class<*>? = hookClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name == methodName) {
                    method.isAccessible = true
                    unhooks.add(XposedBridge.hookMethod(method, callback))
                }
            }
            current = current.superclass
        }
        return unhooks
    }

    @JvmStatic
    fun hookAllConstructors(
        hookClass: Class<*>,
        callback: XC_MethodHook
    ): Set<XC_MethodHook.Unhook> {
        val unhooks = mutableSetOf<XC_MethodHook.Unhook>()
        for (constructor in hookClass.declaredConstructors) {
            constructor.isAccessible = true
            unhooks.add(XposedBridge.hookMethod(constructor, callback))
        }
        return unhooks
    }

    @JvmStatic
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val method = findMethodBestMatchByArgs(obj.javaClass, methodName, args)
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
        val method = findMethodBestMatchByArgs(clazz, methodName, args)
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
    fun getStaticIntField(clazz: Class<*>, fieldName: String): Int {
        return getStaticObjectField(clazz, fieldName) as Int
    }

    @JvmStatic
    fun getStaticLongField(clazz: Class<*>, fieldName: String): Long {
        return getStaticObjectField(clazz, fieldName) as Long
    }

    @JvmStatic
    fun getStaticBooleanField(clazz: Class<*>, fieldName: String): Boolean {
        return getStaticObjectField(clazz, fieldName) as Boolean
    }

    @JvmStatic
    fun getStaticFloatField(clazz: Class<*>, fieldName: String): Float {
        return getStaticObjectField(clazz, fieldName) as Float
    }

    @JvmStatic
    fun getStaticDoubleField(clazz: Class<*>, fieldName: String): Double {
        return getStaticObjectField(clazz, fieldName) as Double
    }

    @JvmStatic
    fun setStaticIntField(clazz: Class<*>, fieldName: String, value: Int) {
        setStaticObjectField(clazz, fieldName, value)
    }

    @JvmStatic
    fun setStaticLongField(clazz: Class<*>, fieldName: String, value: Long) {
        setStaticObjectField(clazz, fieldName, value)
    }

    @JvmStatic
    fun setStaticBooleanField(clazz: Class<*>, fieldName: String, value: Boolean) {
        setStaticObjectField(clazz, fieldName, value)
    }

    @JvmStatic
    fun setStaticFloatField(clazz: Class<*>, fieldName: String, value: Float) {
        setStaticObjectField(clazz, fieldName, value)
    }

    @JvmStatic
    fun setStaticDoubleField(clazz: Class<*>, fieldName: String, value: Double) {
        setStaticObjectField(clazz, fieldName, value)
    }

    @JvmStatic
    fun getFloatField(obj: Any, fieldName: String): Float {
        return getObjectField(obj, fieldName) as Float
    }

    @JvmStatic
    fun setFloatField(obj: Any, fieldName: String, value: Float) {
        setObjectField(obj, fieldName, value)
    }

    @JvmStatic
    fun getDoubleField(obj: Any, fieldName: String): Double {
        return getObjectField(obj, fieldName) as Double
    }

    @JvmStatic
    fun setDoubleField(obj: Any, fieldName: String, value: Double) {
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

    @JvmStatic
    fun setAdditionalStaticField(clazz: Class<*>, key: String, value: Any?) {
        XposedBridgeImpl.setAdditionalField(clazz, key, value)
    }

    @JvmStatic
    fun getAdditionalStaticField(clazz: Class<*>, key: String): Any? {
        return XposedBridgeImpl.getAdditionalField(clazz, key)
    }

    @JvmStatic
    fun removeAdditionalStaticField(clazz: Class<*>, key: String): Any? {
        return XposedBridgeImpl.removeAdditionalField(clazz, key)
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

    private fun findMethodByBestMatch(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<out Class<*>>
    ): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != methodName) continue
                val methodParams = method.parameterTypes
                if (methodParams.size != parameterTypes.size) continue
                var match = true
                for (j in methodParams.indices) {
                    if (!methodParams[j].isAssignableFrom(parameterTypes[j])) {
                        match = false
                        break
                    }
                }
                if (match) {
                    method.isAccessible = true
                    return method
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException(
            "${clazz.name}.$methodName(${parameterTypes.joinToString { it.name }})"
        )
    }

    private fun findMethodBestMatchByArgs(
        clazz: Class<*>,
        methodName: String,
        args: Array<out Any?>
    ): Method {
        val argTypes = Array(args.size) { i -> args[i]?.javaClass ?: Any::class.java }
        @Suppress("UNCHECKED_CAST")
        val exact = findMethod(clazz, methodName, argTypes as Array<Class<*>>)
        if (exact != null) {
            exact.isAccessible = true
            return exact
        }
        return findMethodByBestMatch(clazz, methodName, argTypes)
    }
}

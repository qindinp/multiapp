package com.multiapp.core.loader

import android.app.Application
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Handler
import java.lang.ref.WeakReference

object ActivityThreadCompat {

    fun currentActivityThread(): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread")
        currentActivityThread.isAccessible = true
        return currentActivityThread.invoke(null)
            ?: throw IllegalStateException("ActivityThread.currentActivityThread() returned null")
    }

    fun currentApplication(activityThread: Any = currentActivityThread()): Application {
        val method = activityThread.javaClass.getDeclaredMethod("currentApplication")
        method.isAccessible = true
        return method.invoke(activityThread) as? Application
            ?: throw IllegalStateException("ActivityThread.currentApplication() returned null")
    }

    fun getInstrumentation(activityThread: Any = currentActivityThread()): Instrumentation {
        val field = activityThread.javaClass.getDeclaredField("mInstrumentation")
        field.isAccessible = true
        return field.get(activityThread) as Instrumentation
    }

    fun setInstrumentation(
        instrumentation: Instrumentation,
        activityThread: Any = currentActivityThread()
    ): Instrumentation {
        val field = activityThread.javaClass.getDeclaredField("mInstrumentation")
        field.isAccessible = true
        val previous = field.get(activityThread) as Instrumentation
        field.set(activityThread, instrumentation)
        return previous
    }

    fun mainHandler(activityThread: Any = currentActivityThread()): Handler {
        val field = activityThread.javaClass.getDeclaredField("mH")
        field.isAccessible = true
        return field.get(activityThread) as Handler
    }

    fun getHandlerCallback(handler: Handler): Handler.Callback? {
        val field = findFieldInHierarchy(Handler::class.java, "mCallback")
            ?: throw NoSuchFieldException("Handler.mCallback")
        field.isAccessible = true
        return field.get(handler) as? Handler.Callback
    }

    fun setHandlerCallback(handler: Handler, callback: Handler.Callback?) {
        val field = findFieldInHierarchy(Handler::class.java, "mCallback")
            ?: throw NoSuchFieldException("Handler.mCallback")
        field.isAccessible = true
        field.set(handler, callback)
    }

    @Suppress("UNCHECKED_CAST")
    fun packageMap(
        fieldName: String,
        activityThread: Any = currentActivityThread()
    ): MutableMap<Any?, Any?>? {
        val field = findFieldInHierarchy(activityThread.javaClass, fieldName) ?: return null
        field.isAccessible = true
        return field.get(activityThread) as? MutableMap<Any?, Any?>
    }

    fun putLoadedApkReference(
        fieldName: String,
        packageName: String,
        loadedApk: Any,
        activityThread: Any = currentActivityThread()
    ): Boolean {
        val map = packageMap(fieldName, activityThread) ?: return false
        map[packageName] = WeakReference(loadedApk)
        return true
    }

    fun getPackageInfoNoCheck(
        applicationInfo: ApplicationInfo,
        activityThread: Any = currentActivityThread()
    ): Any {
        val method = findMethodInHierarchy(activityThread.javaClass, "getPackageInfoNoCheck") { method ->
            val types = method.parameterTypes
            types.size == 2 && ApplicationInfo::class.java.isAssignableFrom(types[0])
        } ?: throw NoSuchMethodException("ActivityThread.getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo)")
        method.isAccessible = true
        val compatibilityInfo = defaultCompatibilityInfo(method.parameterTypes[1])
        return method.invoke(activityThread, applicationInfo, compatibilityInfo)
            ?: throw IllegalStateException("ActivityThread.getPackageInfoNoCheck returned null")
    }

    private fun defaultCompatibilityInfo(type: Class<*>): Any? {
        return runCatching {
            val field = type.getDeclaredField("DEFAULT_COMPATIBILITY_INFO")
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    private fun findMethodInHierarchy(
        type: Class<*>,
        name: String,
        predicate: (java.lang.reflect.Method) -> Boolean
    ): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == name && predicate(method)) return method
            }
            current = current.superclass
        }
        return null
    }

    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name) }
            current = current.superclass
        }
        return null
    }
}

package com.multiapp.core.loader

import android.app.Application
import android.app.Instrumentation
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

    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name) }
            current = current.superclass
        }
        return null
    }
}

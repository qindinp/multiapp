package com.multiapp.core.loader

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
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

    fun sendActivityResult(
        activityToken: IBinder?,
        resultWho: String?,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        activityThread: Any? = null
    ): ActivityThreadActivityResultDispatchResult {
        if (activityToken == null) {
            return ActivityThreadActivityResultDispatchResult.skipped("ACTIVITY_THREAD_TOKEN_MISSING")
        }
        if (requestCode < 0) {
            return ActivityThreadActivityResultDispatchResult.skipped("REQUEST_CODE_NOT_FOR_RESULT")
        }
        val thread = runCatching { activityThread ?: currentActivityThread() }
            .getOrElse { error ->
                return ActivityThreadActivityResultDispatchResult.failed(
                    reason = "ACTIVITY_THREAD_LOOKUP_FAILED",
                    error = error
                )
            }
        val method = findMethodInHierarchy(thread.javaClass, "sendActivityResult") { candidate ->
            val types = candidate.parameterTypes
            types.size == 5 &&
                IBinder::class.java.isAssignableFrom(types[0]) &&
                String::class.java.isAssignableFrom(types[1]) &&
                types[2] == Integer.TYPE &&
                types[3] == Integer.TYPE &&
                Intent::class.java.isAssignableFrom(types[4])
        } ?: return ActivityThreadActivityResultDispatchResult.failed("SEND_ACTIVITY_RESULT_METHOD_MISSING")
        return runCatching {
            method.isAccessible = true
            method.invoke(thread, activityToken, resultWho, requestCode, resultCode, data)
            ActivityThreadActivityResultDispatchResult.partial(
                methodName = "${method.declaringClass.name}.${method.name}",
                reason = "CALL_SCHEDULED_DELIVERY_PENDING_DEVICE_PROOF"
            )
        }.getOrElse { error ->
            ActivityThreadActivityResultDispatchResult.failed(
                reason = "SEND_ACTIVITY_RESULT_INVOKE_FAILED",
                error = error
            )
        }
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

data class ActivityThreadActivityResultDispatchResult(
    val verdict: String,
    val attempted: Boolean,
    val invoked: Boolean,
    val methodName: String? = null,
    val reason: String,
    val errorClassName: String? = null
) {
    companion object {
        fun skipped(reason: String): ActivityThreadActivityResultDispatchResult =
            ActivityThreadActivityResultDispatchResult(
                verdict = "SKIPPED",
                attempted = false,
                invoked = false,
                reason = reason
            )

        fun partial(methodName: String, reason: String): ActivityThreadActivityResultDispatchResult =
            ActivityThreadActivityResultDispatchResult(
                verdict = "PARTIAL",
                attempted = true,
                invoked = true,
                methodName = methodName,
                reason = reason
            )

        fun failed(reason: String, error: Throwable? = null): ActivityThreadActivityResultDispatchResult =
            ActivityThreadActivityResultDispatchResult(
                verdict = "FAIL",
                attempted = true,
                invoked = false,
                reason = reason,
                errorClassName = error?.javaClass?.name
            )
    }
}

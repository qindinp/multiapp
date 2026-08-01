package com.multiapp.core.loader

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

data class ActivityFinishResultHookInstallResult(
    val installed: Boolean,
    val alreadyInstalled: Boolean = false,
    val reason: String
)

/** Installs the Android 12+ IActivityClientController finish boundary observer. */
object ActivityFinishResultHookInstaller {
    fun install(): ActivityFinishResultHookInstallResult = runCatching {
        val activityClientClass = Class.forName("android.app.ActivityClient")
        val singletonField = activityClientClass.getDeclaredField("INTERFACE_SINGLETON").apply {
            isAccessible = true
        }
        val singleton = singletonField.get(null)
            ?: return@runCatching ActivityFinishResultHookInstallResult(false, reason = "activity_client_singleton_missing")
        val knownField = findField(singleton.javaClass, "mKnownInstance")
        val instanceField = findField(singleton.javaClass, "mInstance")
        var base = knownField?.getAccessible(singleton) ?: instanceField?.getAccessible(singleton)
        if (base == null) {
            base = singleton.javaClass.getMethod("get").invoke(singleton)
        }
        if (base == null) {
            return@runCatching ActivityFinishResultHookInstallResult(false, reason = "activity_client_controller_missing")
        }
        if (Proxy.isProxyClass(base.javaClass) && Proxy.getInvocationHandler(base) is FinishInvocationHandler) {
            return@runCatching ActivityFinishResultHookInstallResult(
                installed = false,
                alreadyInstalled = true,
                reason = "activity_client_controller_already_hooked"
            )
        }
        val iface = Class.forName("android.app.IActivityClientController")
        val proxy = Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            FinishInvocationHandler(base)
        )
        knownField?.setAccessible(singleton, proxy)
        instanceField?.setAccessible(singleton, proxy)
        ActivityFinishResultHookInstallResult(true, reason = "activity_client_controller_hooked")
    }.getOrElse { error ->
        ActivityFinishResultHookInstallResult(
            installed = false,
            reason = "activity_client_controller_hook_failed:${error.javaClass.name}"
        )
    }

    private class FinishInvocationHandler(private val base: Any) : java.lang.reflect.InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            runCatching { VirtualActivityResultFrameworkBridge.captureFinishActivity(method.name, args) }
            return try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name) }
            current = current.superclass
        }
        return null
    }

    private fun java.lang.reflect.Field.getAccessible(target: Any): Any? {
        isAccessible = true
        return get(target)
    }

    private fun java.lang.reflect.Field.setAccessible(target: Any, value: Any?) {
        isAccessible = true
        set(target, value)
    }
}

package com.multiapp.core.loader

import android.annotation.SuppressLint
import android.net.Uri
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Routes ContentResolver persistable URI mutations into the active virtual instance. */
object VirtualUriGrantsServiceProxy {
    private const val TAG = "MultiApp.UriGrants"
    private const val SERVICE_NAME = "uri_grants"
    private const val DESCRIPTOR = "android.app.IUriGrantsManager"
    private val installLock = Any()

    // Hidden Binder access is best-effort and fail-closed; external URI grants
    // remain UNSUPPORTED until an API 37-safe adapter is implemented.
    @SuppressLint("BlockedPrivateApi")
    fun install(): Boolean = synchronized(installLock) {
        runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val cacheField = serviceManagerClass.getDeclaredField("sCache").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val cache = cacheField.get(null) as? MutableMap<String, Any?> ?: return false
            var baseBinder = cache[SERVICE_NAME] as? IBinder
            if (baseBinder == null) {
                baseBinder = serviceManagerClass.getDeclaredMethod(
                    "getService",
                    String::class.java
                ).apply {
                    isAccessible = true
                }.invoke(null, SERVICE_NAME) as? IBinder
            }
            baseBinder ?: return false
            if (Proxy.isProxyClass(baseBinder.javaClass)) {
                val handler = runCatching { Proxy.getInvocationHandler(baseBinder) }.getOrNull()
                if (handler is UriGrantsBinderInvocationHandler) return true
            }
            val serviceInterface = Class.forName(DESCRIPTOR)
            val stubClass = Class.forName("$DESCRIPTOR\$Stub")
            val baseService = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply {
                isAccessible = true
            }.invoke(null, baseBinder) ?: return false
            val proxyBinder = createBinderProxy(baseBinder, baseService, serviceInterface) ?: return false
            val serviceProxy = proxyBinder.queryLocalInterface(DESCRIPTOR) ?: return false
            cache[SERVICE_NAME] = proxyBinder
            if (!patchCachedService(serviceProxy)) return false
            true
        }.onFailure { error ->
            runCatching {
                Log.w(TAG, "uri_grants proxy install failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }.getOrDefault(false)
    }

    internal fun createBinderProxy(
        baseBinder: IBinder,
        baseService: Any,
        serviceInterface: Class<*>,
        dispatcher: (VirtualUriPermissionRequest) -> VirtualUriPermissionResult =
            VirtualUriPermissionRuntimeBindings::dispatch
    ): IBinder? {
        if (!IInterface::class.java.isAssignableFrom(serviceInterface)) return null
        val serviceProxy = Proxy.newProxyInstance(
            serviceInterface.classLoader,
            arrayOf(serviceInterface),
            UriGrantsServiceInvocationHandler(baseService, dispatcher)
        ) as IInterface
        return Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
            UriGrantsBinderInvocationHandler(baseBinder, serviceProxy)
        ) as IBinder
    }

    @SuppressLint("BlockedPrivateApi")
    private fun patchCachedService(serviceProxy: IInterface): Boolean = runCatching {
        val managerClass = Class.forName("android.app.UriGrantsManager")
        val singletonField = managerClass.getDeclaredField("IUriGrantsManagerSingleton").apply {
            isAccessible = true
        }
        val singleton = singletonField.get(null) ?: return@runCatching false
        val instanceField = Class.forName("android.util.Singleton")
            .getDeclaredField("mInstance")
            .apply { isAccessible = true }
        instanceField.set(singleton, serviceProxy)
        true
    }.getOrDefault(false)

    private class UriGrantsServiceInvocationHandler(
        private val base: Any,
        private val dispatcher: (VirtualUriPermissionRequest) -> VirtualUriPermissionResult
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? {
            val operation = when (method.name) {
                "takePersistableUriPermission" -> VirtualUriPermissionOperation.TAKE_PERSISTABLE
                "releasePersistableUriPermission" -> VirtualUriPermissionOperation.RELEASE_PERSISTABLE
                else -> null
            }
            if (operation != null) {
                val request = createRequest(method, args.orEmpty(), operation)
                val result = request?.let(dispatcher)
                if (result?.handled == true) {
                    if (!result.success) throw SecurityException(result.reason)
                    return null
                }
            }
            return try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

        private fun createRequest(
            method: java.lang.reflect.Method,
            args: Array<out Any?>,
            operation: VirtualUriPermissionOperation
        ): VirtualUriPermissionRequest? {
            val uriIndex = args.indexOfFirst { it is Uri }.takeIf { it >= 0 } ?: return null
            val uri = args[uriIndex] as Uri
            val modeIndex = (uriIndex + 1 until args.size).firstOrNull { index ->
                args[index] is Int && method.parameterTypes.getOrNull(index) in INT_TYPES
            } ?: return null
            return VirtualUriPermissionRequest(
                operation = operation,
                uri = uri,
                modeFlags = args[modeIndex] as Int
            )
        }
    }

    private class UriGrantsBinderInvocationHandler(
        private val baseBinder: IBinder,
        private val serviceProxy: IInterface
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "MultiAppUriGrantsBinderProxy(${System.identityHashCode(proxy)})"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            if (method.name == "queryLocalInterface" && args?.firstOrNull() == DESCRIPTOR) {
                return serviceProxy
            }
            return try {
                method.invoke(baseBinder, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private val INT_TYPES = setOf(Int::class.javaPrimitiveType, Int::class.javaObjectType)
}

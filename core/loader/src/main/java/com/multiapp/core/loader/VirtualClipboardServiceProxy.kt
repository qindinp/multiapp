package com.multiapp.core.loader

import android.content.Context
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

data class VirtualClipboardServiceProxyInstallResult(
    val managerPatched: Boolean,
    val serviceManagerPatched: Boolean
) {
    val installed: Boolean
        get() = managerPatched || serviceManagerPatched

    val complete: Boolean
        get() = managerPatched && serviceManagerPatched
}

/** Rewrites Clipboard caller identity while preserving clipboard payload semantics. */
object VirtualClipboardServiceProxy {
    private const val TAG = "MultiApp.Clipboard"
    private const val SERVICE_NAME = "clipboard"
    private const val DESCRIPTOR = "android.content.IClipboard"
    private val installLock = Any()

    fun install(
        context: Context?,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Boolean = installDetailed(context, sourcePackages, hostPackageName).complete

    fun installDetailed(
        context: Context?,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): VirtualClipboardServiceProxyInstallResult {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty()) {
            return VirtualClipboardServiceProxyInstallResult(
                managerPatched = false,
                serviceManagerPatched = false
            )
        }
        return synchronized(installLock) {
            val serviceManagerPatched = patchServiceManager(
                aliases = aliases,
                hostPackageName = hostPackageName
            )
            val managerPatched = patchManagerService(
                manager = runCatching {
                    context?.getSystemService(Context.CLIPBOARD_SERVICE)
                }.getOrNull(),
                aliases = aliases,
                hostPackageName = hostPackageName
            )
            val result = VirtualClipboardServiceProxyInstallResult(
                managerPatched = managerPatched,
                serviceManagerPatched = serviceManagerPatched
            )
            runCatching {
                Log.d(
                    TAG,
                    "clipboard proxy install manager=$managerPatched serviceManager=$serviceManagerPatched"
                )
            }
            result
        }
    }

    internal fun remapCallingPackageArgs(
        methodName: String,
        args: Array<Any?>,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Array<Any?> {
        if (hostPackageName.isBlank()) return args
        val packageIndex = CALLING_PACKAGE_INDEX[methodName] ?: return args
        if (packageIndex !in args.indices) return args
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (args[packageIndex] !in aliases) return args
        return args.copyOf().also { patched ->
            patched[packageIndex] = hostPackageName
            val attributionIndex = ATTRIBUTION_TAG_INDEX[methodName]
            if (attributionIndex != null &&
                attributionIndex in patched.indices &&
                (patched[attributionIndex] == null || patched[attributionIndex] is String)
            ) {
                patched[attributionIndex] = null
            }
        }
    }

    private fun patchManagerService(
        manager: Any?,
        aliases: Collection<String>,
        hostPackageName: String
    ): Boolean = runCatching {
        manager ?: return@runCatching false
        val serviceField = findServiceField(manager) ?: return@runCatching false
        val receiver = if (Modifier.isStatic(serviceField.modifiers)) null else manager
        val baseService = serviceField.get(receiver) ?: return@runCatching false
        if (Proxy.isProxyClass(baseService.javaClass)) {
            val handler = runCatching { Proxy.getInvocationHandler(baseService) }.getOrNull()
            if (handler is ClipboardInvocationHandler) {
                handler.addAliases(aliases, hostPackageName)
                return@runCatching true
            }
        }
        val serviceInterface = Class.forName(DESCRIPTOR)
        val serviceProxy = createServiceProxy(
            baseService = baseService,
            serviceInterface = serviceInterface,
            aliases = aliases,
            hostPackageName = hostPackageName
        )
        serviceField.set(receiver, serviceProxy)
        true
    }.onFailure(::logInstallFailure).getOrDefault(false)

    private fun patchServiceManager(
        aliases: Collection<String>,
        hostPackageName: String
    ): Boolean = runCatching {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val cacheField = serviceManagerClass.getDeclaredField("sCache").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as? MutableMap<String, Any?> ?: return@runCatching false
        var baseBinder = cache[SERVICE_NAME] as? IBinder
        if (baseBinder == null) {
            baseBinder = serviceManagerClass.getDeclaredMethod(
                "getService",
                String::class.java
            ).apply {
                isAccessible = true
            }.invoke(null, SERVICE_NAME) as? IBinder
        }
        baseBinder ?: return@runCatching false
        if (Proxy.isProxyClass(baseBinder.javaClass)) {
            val handler = runCatching { Proxy.getInvocationHandler(baseBinder) }.getOrNull()
            if (handler is ClipboardBinderInvocationHandler) {
                handler.addAliases(aliases, hostPackageName)
                return@runCatching true
            }
        }
        val serviceInterface = Class.forName(DESCRIPTOR)
        val stubClass = Class.forName("$DESCRIPTOR\$Stub")
        val baseService = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply {
            isAccessible = true
        }.invoke(null, baseBinder) ?: return@runCatching false
        val serviceHandler = ClipboardInvocationHandler(
            base = baseService,
            hostPackageName = hostPackageName,
            initialAliases = aliases
        )
        val serviceProxy = Proxy.newProxyInstance(
            serviceInterface.classLoader,
            arrayOf(serviceInterface),
            serviceHandler
        ) as IInterface
        val binderProxy = Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
            ClipboardBinderInvocationHandler(
                baseBinder = baseBinder,
                serviceProxy = serviceProxy,
                serviceHandler = serviceHandler,
                hostPackageName = hostPackageName,
                initialAliases = aliases
            )
        ) as IBinder
        cache[SERVICE_NAME] = binderProxy
        true
    }.onFailure(::logInstallFailure).getOrDefault(false)

    private fun createServiceProxy(
        baseService: Any,
        serviceInterface: Class<*>,
        aliases: Collection<String>,
        hostPackageName: String
    ): Any = Proxy.newProxyInstance(
        serviceInterface.classLoader,
        arrayOf(serviceInterface),
        ClipboardInvocationHandler(baseService, hostPackageName, aliases)
    )

    private fun findServiceField(manager: Any): java.lang.reflect.Field? {
        var current: Class<*>? = manager.javaClass
        while (current != null) {
            val field = runCatching { current.declaredFields.toList() }
                .getOrDefault(emptyList())
                .firstOrNull {
                    it.name == "mService" ||
                        it.name == "sService" ||
                        it.type.name == DESCRIPTOR
                }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private class ClipboardInvocationHandler(
        private val base: Any,
        private var hostPackageName: String,
        initialAliases: Collection<String>
    ) : InvocationHandler {
        private val aliases = linkedSetOf<String>()

        init {
            addAliases(initialAliases, hostPackageName)
        }

        fun addAliases(sourcePackages: Collection<String>, hostPackageName: String) {
            synchronized(aliases) {
                this.hostPackageName = hostPackageName
                aliases += sourcePackages.filter { it.isNotBlank() && it != hostPackageName }
            }
        }

        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return objectMethod(proxy, method.name, args)
            }
            val currentAliases = synchronized(aliases) { aliases.toSet() }
            val patchedArgs = args?.let {
                remapCallingPackageArgs(method.name, it, currentAliases, hostPackageName)
            }
            return try {
                method.invoke(base, *(patchedArgs ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private class ClipboardBinderInvocationHandler(
        private val baseBinder: IBinder,
        private val serviceProxy: IInterface,
        private val serviceHandler: ClipboardInvocationHandler,
        private var hostPackageName: String,
        initialAliases: Collection<String>
    ) : InvocationHandler {
        private val aliases = linkedSetOf<String>()

        init {
            addAliases(initialAliases, hostPackageName)
        }

        fun addAliases(sourcePackages: Collection<String>, hostPackageName: String) {
            synchronized(aliases) {
                this.hostPackageName = hostPackageName
                aliases += sourcePackages.filter { it.isNotBlank() && it != hostPackageName }
            }
            serviceHandler.addAliases(sourcePackages, hostPackageName)
        }

        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return objectMethod(proxy, method.name, args)
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

    private fun objectMethod(proxy: Any, methodName: String, args: Array<Any?>?): Any? =
        when (methodName) {
            "toString" -> "MultiAppClipboardProxy(${System.identityHashCode(proxy)})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }

    private fun logInstallFailure(error: Throwable) {
        runCatching {
            Log.w(TAG, "clipboard proxy install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private val CALLING_PACKAGE_INDEX = mapOf(
        "setPrimaryClip" to 1,
        "setPrimaryClipAsPackage" to 1,
        "clearPrimaryClip" to 0,
        "getPrimaryClip" to 0,
        "getPrimaryClipDescription" to 0,
        "hasPrimaryClip" to 0,
        "addPrimaryClipChangedListener" to 1,
        "removePrimaryClipChangedListener" to 1,
        "hasClipboardText" to 0,
        "getPrimaryClipSource" to 0
    )

    private val ATTRIBUTION_TAG_INDEX = mapOf(
        "setPrimaryClip" to 2,
        "setPrimaryClipAsPackage" to 2,
        "clearPrimaryClip" to 1,
        "getPrimaryClip" to 1,
        "getPrimaryClipDescription" to 1,
        "hasPrimaryClip" to 1,
        "addPrimaryClipChangedListener" to 2,
        "removePrimaryClipChangedListener" to 2,
        "hasClipboardText" to 1,
        "getPrimaryClipSource" to 1
    )
}

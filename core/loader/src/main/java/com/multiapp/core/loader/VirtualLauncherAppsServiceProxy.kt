package com.multiapp.core.loader

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Rewrites only LauncherApps caller identity before requests enter system_server. */
object VirtualLauncherAppsServiceProxy {
    private const val TAG = "MultiApp.LauncherApps"
    private const val SERVICE_NAME = "launcherapps"
    private const val DESCRIPTOR = "android.content.pm.ILauncherApps"
    private val installLock = Any()

    fun install(
        context: Context?,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Boolean {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty()) return false
        return synchronized(installLock) {
            val managerPatched = patchManagerService(
                manager = runCatching {
                    context?.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                }.getOrNull(),
                aliases = aliases,
                hostPackageName = hostPackageName
            )
            val serviceManagerPatched = patchServiceManager(
                aliases = aliases,
                hostPackageName = hostPackageName
            )
            managerPatched || serviceManagerPatched
        }
    }

    internal fun remapCallingPackageArgs(
        methodName: String,
        args: Array<Any?>,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Array<Any?> {
        if (methodName !in CALLING_PACKAGE_METHODS || hostPackageName.isBlank()) return args
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (aliases.isEmpty()) return args
        val callingPackageIndex = args.indices.firstOrNull { args[it] is String } ?: return args
        if (args[callingPackageIndex] !in aliases) return args
        return args.copyOf().also { it[callingPackageIndex] = hostPackageName }
    }

    private fun patchManagerService(
        manager: Any?,
        aliases: Collection<String>,
        hostPackageName: String
    ): Boolean = runCatching {
        manager ?: return@runCatching false
        val serviceField = findServiceField(manager) ?: return@runCatching false
        val baseService = serviceField.get(manager) ?: return@runCatching false
        if (Proxy.isProxyClass(baseService.javaClass)) {
            val handler = runCatching { Proxy.getInvocationHandler(baseService) }.getOrNull()
            if (handler is LauncherAppsInvocationHandler) {
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
        serviceField.set(manager, serviceProxy)
        true
    }.onFailure(::logInstallFailure).getOrDefault(false)

    // This compatibility hook is best-effort and remains non-PASS on API 37.
    @SuppressLint("SoonBlockedPrivateApi")
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
            if (handler is LauncherAppsBinderInvocationHandler) {
                handler.addAliases(aliases, hostPackageName)
                return@runCatching true
            }
        }
        val serviceInterface = Class.forName(DESCRIPTOR)
        val stubClass = Class.forName("$DESCRIPTOR\$Stub")
        val baseService = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply {
            isAccessible = true
        }.invoke(null, baseBinder) ?: return@runCatching false
        val serviceHandler = LauncherAppsInvocationHandler(
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
            LauncherAppsBinderInvocationHandler(
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
        LauncherAppsInvocationHandler(baseService, hostPackageName, aliases)
    )

    private fun findServiceField(manager: Any): java.lang.reflect.Field? {
        var current: Class<*>? = manager.javaClass
        while (current != null) {
            val field = runCatching { current.declaredFields.toList() }
                .getOrDefault(emptyList())
                .firstOrNull {
                    it.name == "mService" || it.type.name == DESCRIPTOR
                }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private class LauncherAppsInvocationHandler(
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

    private class LauncherAppsBinderInvocationHandler(
        private val baseBinder: IBinder,
        private val serviceProxy: IInterface,
        private val serviceHandler: LauncherAppsInvocationHandler,
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
            "toString" -> "MultiAppLauncherAppsProxy(${System.identityHashCode(proxy)})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }

    private fun logInstallFailure(error: Throwable) {
        runCatching {
            Log.w(TAG, "launcherapps proxy install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private val CALLING_PACKAGE_METHODS = setOf(
        "addOnAppsChangedListener",
        "getLauncherActivities",
        "resolveLauncherActivityInternal",
        "resolveActivity",
        "startSessionDetailsActivityAsUser",
        "startActivityAsUser",
        "showAppDetailsAsUser",
        "isPackageEnabled",
        "isActivityEnabled",
        "getApplicationInfo",
        "getAppUsageLimit",
        "getShortcuts",
        "getShortcutsAsync",
        "pinShortcuts",
        "startShortcut",
        "getShortcutIconResId",
        "getShortcutIconFd",
        "hasShortcutHostPermission",
        "getShortcutConfigActivities",
        "getShortcutConfigActivityIntent",
        "registerPackageInstallerCallback",
        "getAllSessions",
        "registerShortcutChangeCallback",
        "unregisterShortcutChangeCallback",
        "cacheShortcuts",
        "uncacheShortcuts",
        "getActivityOverrides",
        "getShortcutIntent"
    )
}

package com.multiapp.core.loader

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy

/**
 * Intent 重映射诊断 — 检测并修正跨包名 Intent 路由。
 *
 * 从 LoaderFactory 提取的 Intent remap 纯逻辑：
 * - Activity 启动参数中的包名重写
 * - 通知参数中的包名重写
 * - Instrumentation 包装安装
 * - ActivityTaskManager / ActivityManager Singleton 代理安装
 */
object IntentRemapDiagnostics {

    private const val TAG = "IntentRemapDiag"
    private const val APP_OPS_SERVICE_NAME = "appops"
    private const val APP_OPS_DESCRIPTOR = "com.android.internal.app.IAppOpsService"
    private val notificationProxyLock = Any()
    private val appOpsProxyLock = Any()

    /**
     * 重写 Intent 中的包名：component.packageName 和 intent.package。
     * 返回修改后的副本；若无变更则返回原对象。
     */
    fun remapActivityIntent(intent: Intent, originalPkg: String, stubPkg: String): Intent {
        var changed = false
        val copy = Intent(intent)

        fun rewriteOne(target: Intent) {
            val component = target.component
            if (component?.packageName == originalPkg) {
                val rewritten = ComponentName(stubPkg, component.className)
                target.component = rewritten
                changed = true
                Log.d(TAG, "Activity intent component remap: $component -> $rewritten")
            }
            if (target.`package` == originalPkg) {
                target.setPackage(stubPkg)
                changed = true
                Log.d(TAG, "Activity intent package remap: $originalPkg -> $stubPkg")
            }
        }

        rewriteOne(copy)
        copy.selector?.let { selector ->
            val selectorCopy = Intent(selector)
            rewriteOne(selectorCopy)
            if (selectorCopy != selector) {
                copy.selector = selectorCopy
            }
        }
        return if (changed) copy else intent
    }

    /**
     * 重写 startActivity 系列方法的参数数组中所有 Intent 的包名。
     * 直接修改 args 数组（与原 LoaderFactory 行为一致）。
     */
    fun remapStartActivityArgs(
        methodName: String,
        args: Array<Any?>,
        originalPkg: String,
        stubPkg: String
    ) {
        for (index in args.indices) {
            val arg = args[index]
            when (arg) {
                is Intent -> {
                    val remapped = remapActivityIntent(arg, originalPkg, stubPkg)
                    if (remapped !== arg) {
                        args[index] = remapped
                        Log.d(TAG, "ActivityTaskManager.$methodName remapped Intent argument #$index")
                    }
                }
                is Array<*> -> {
                    if (arg.any { it is Intent }) {
                        @Suppress("UNCHECKED_CAST")
                        val intents = arg as Array<Any?>
                        var changed = false
                        for (intentIndex in intents.indices) {
                            val intent = intents[intentIndex] as? Intent ?: continue
                            val remapped = remapActivityIntent(intent, originalPkg, stubPkg)
                            if (remapped !== intent) {
                                intents[intentIndex] = remapped
                                changed = true
                            }
                        }
                        if (changed) {
                            Log.d(TAG, "ActivityTaskManager.$methodName remapped Intent[] argument #$index")
                        }
                    }
                }
            }
        }
    }

    /**
     * 重写通知相关方法参数中的包名。
     * 返回修改后的数组副本；若无变更则返回原数组。
     */
    fun remapNotificationPackageArgs(
        methodName: String,
        args: Array<Any?>,
        originalPkg: String,
        stubPkg: String
    ): Array<Any?> = remapNotificationPackageArgs(methodName, args, setOf(originalPkg), stubPkg)

    fun remapNotificationPackageArgs(
        methodName: String,
        args: Array<Any?>,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Array<Any?> {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (aliases.isEmpty()) return args
        var changed = false
        val patched = args.copyOf()
        for (index in patched.indices) {
            if (patched[index] in aliases) {
                patched[index] = hostPackageName
                changed = true
            }
        }
        if (changed) {
            logDebug("NotificationManager.$methodName package remap: ${aliases.joinToString(",")} -> $hostPackageName")
        }
        return if (changed) patched else args
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /**
     * 安装 ActivityThread.mInstrumentation 包装器，拦截 execStartActivity 做 Intent 重映射。
     *
     * @return true 如果安装成功或已安装；false 如果安装失败
     */
    fun installIntentRemappingInstrumentation(
        activityThread: Any,
        originalPkg: String,
        stubPkg: String,
        beforeActivityLifecycle: ((android.app.Activity, String) -> Unit)? = null,
        afterActivityLifecycle: ((android.app.Activity, String) -> Unit)? = null
    ): Boolean {
        return try {
            val field = activityThread.javaClass
                .getDeclaredField("mInstrumentation")
                .apply { isAccessible = true }
            val current = field.get(activityThread) as? android.app.Instrumentation
            if (current == null) {
                Log.w(TAG, "Intent remap skipped: ActivityThread.mInstrumentation is null")
                return false
            }
            if (current is IntentRemappingInstrumentation) {
                Log.d(TAG, "Intent remap instrumentation already installed")
                return true
            }
            field.set(
                activityThread,
                IntentRemappingInstrumentation(
                    base = current,
                    originalPackageName = originalPkg,
                    stubPackageName = stubPkg,
                    beforeActivityLifecycle = beforeActivityLifecycle,
                    afterActivityLifecycle = afterActivityLifecycle
                )
            )
            Log.d(TAG, "Intent remap instrumentation installed: $originalPkg -> $stubPkg")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Intent remap instrumentation install failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 安装 IActivityTaskManager / IActivityManager Singleton 代理，
     * 拦截 startActivity 系列方法做 Intent 包名重写。
     *
     * @return true 如果至少一个代理安装成功
     */
    fun installActivityTaskManagerIntentProxy(
        originalPkg: String,
        stubPkg: String
    ): Boolean {
        var installed = false
        installed = installActivityManagerSingletonProxy(
            ownerClassName = "android.app.ActivityTaskManager",
            singletonFieldName = "IActivityTaskManagerSingleton",
            interfaceClassName = "android.app.IActivityTaskManager",
            originalPkg = originalPkg,
            stubPkg = stubPkg
        ) || installed

        installed = installActivityManagerSingletonProxy(
            ownerClassName = "android.app.ActivityManager",
            singletonFieldName = "IActivityManagerSingleton",
            interfaceClassName = "android.app.IActivityManager",
            originalPkg = originalPkg,
            stubPkg = stubPkg
        ) || installed

        if (installed) {
            Log.d(TAG, "Activity start intent proxy installed: $originalPkg -> $stubPkg")
        } else {
            Log.w(TAG, "Activity start intent proxy not installed")
        }
        return installed
    }

    /**
     * 安装 NotificationManager.sService 代理，重写通知参数中的包名。
     *
     * @return true 如果安装成功或已安装
     */
    fun installNotificationManagerPackageProxy(
        originalPkg: String,
        stubPkg: String
    ): Boolean = installNotificationManagerPackageProxy(setOf(originalPkg), stubPkg)

    fun installNotificationManagerPackageProxy(
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Boolean {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty()) {
            Log.w(TAG, "NotificationManager package proxy skipped: empty host or aliases")
            return false
        }
        return synchronized(notificationProxyLock) {
            try {
            val notificationManagerClass = Class.forName("android.app.NotificationManager")
            val serviceField = notificationManagerClass.getDeclaredField("sService").apply {
                isAccessible = true
            }
            var base = serviceField.get(null)
            if (base == null) {
                base = notificationManagerClass.getDeclaredMethod("getService").apply {
                    isAccessible = true
                }.invoke(null)
            }
            if (base == null) {
                Log.w(TAG, "NotificationManager package proxy skipped: service is null")
                return false
            }
            if (Proxy.isProxyClass(base.javaClass)) {
                val handler = runCatching { Proxy.getInvocationHandler(base) }.getOrNull()
                if (handler is NotificationPackageInvocationHandler) {
                    handler.addAliases(aliases, hostPackageName)
                    Log.d(TAG, "NotificationManager package proxy aliases updated: ${aliases.joinToString(",")} -> $hostPackageName")
                    return true
                }
            }

            val iface = Class.forName("android.app.INotificationManager")
            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                NotificationPackageInvocationHandler(base, hostPackageName, aliases)
            )
            serviceField.set(null, proxy)
            Log.d(TAG, "NotificationManager package proxy installed: ${aliases.joinToString(",")} -> $hostPackageName")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "NotificationManager package proxy failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        }
    }

    fun remapAppOpsPackageArgs(
        methodName: String,
        args: Array<Any?>,
        sourcePackages: Collection<String>,
        hostPackageName: String,
        runtimeUid: Int = Process.myUid()
    ): Array<Any?> {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty() || !isAppOpsPackageMethod(methodName)) {
            return args
        }
        var changed = false
        val patched = args.copyOf()
        for (index in patched.indices) {
            val value = patched[index]
            when (value) {
                is String -> {
                    if (value in aliases) {
                        patched[index] = hostPackageName
                        val uidIndex = appOpsUidIndexBeforePackage(methodName, patched, index)
                        if (uidIndex != null && patched[uidIndex] != runtimeUid) {
                            patched[uidIndex] = runtimeUid
                        }
                        changed = true
                    }
                }
                is Array<*> -> {
                    if (value.javaClass.componentType == String::class.java) {
                        @Suppress("UNCHECKED_CAST")
                        val strings = value as Array<String>
                        val rewritten = strings.copyOf()
                        var arrayChanged = false
                        for (itemIndex in rewritten.indices) {
                            if (rewritten[itemIndex] in aliases) {
                                rewritten[itemIndex] = hostPackageName
                                arrayChanged = true
                            }
                        }
                        if (arrayChanged) {
                            patched[index] = rewritten
                            changed = true
                        }
                    } else {
                        val remapped = remapAttributionSourceLike(value, aliases, hostPackageName, runtimeUid)
                        if (remapped.changed) {
                            patched[index] = remapped.value
                            changed = true
                        }
                    }
                }
                else -> {
                    val remapped = remapAttributionSourceLike(value, aliases, hostPackageName, runtimeUid)
                    if (remapped.changed) {
                        patched[index] = remapped.value
                        changed = true
                    }
                }
            }
        }
        if (changed) {
            safeLogD("AppOps.$methodName package args remapped to $hostPackageName")
        }
        return if (changed) patched else args
    }

    fun installAppOpsManagerPackageProxy(
        context: Context?,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Boolean {
        val appOpsManager = runCatching {
            context?.getSystemService(Context.APP_OPS_SERVICE)
        }.getOrNull() ?: return false
        return installAppOpsManagerPackageProxy(appOpsManager, sourcePackages, hostPackageName)
    }

    fun installAppOpsManagerPackageProxy(
        appOpsManager: Any?,
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Boolean {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (appOpsManager == null || hostPackageName.isBlank() || aliases.isEmpty()) {
            safeLogW("AppOps package proxy skipped: empty manager, host, or aliases")
            return false
        }
        return synchronized(appOpsProxyLock) {
            try {
                val serviceField = findAppOpsServiceField(appOpsManager)
                    ?: run {
                        safeLogW("AppOps package proxy skipped: IAppOpsService field not found")
                        return false
                    }
                val base = serviceField.get(appOpsManager)
                    ?: run {
                        safeLogW("AppOps package proxy skipped: service is null")
                        return false
                    }
                if (Proxy.isProxyClass(base.javaClass)) {
                    val handler = runCatching { Proxy.getInvocationHandler(base) }.getOrNull()
                    if (handler is AppOpsPackageInvocationHandler) {
                        handler.addAliases(aliases, hostPackageName)
                        safeLogD("AppOps package proxy aliases updated: ${aliases.joinToString(",")} -> $hostPackageName")
                        return true
                    }
                }

                val iface = Class.forName("com.android.internal.app.IAppOpsService")
                val proxy = Proxy.newProxyInstance(
                    iface.classLoader,
                    arrayOf(iface),
                    AppOpsPackageInvocationHandler(base, hostPackageName, aliases)
                )
                serviceField.set(appOpsManager, proxy)
                safeLogD("AppOps package proxy installed: ${aliases.joinToString(",")} -> $hostPackageName")
                true
            } catch (e: Throwable) {
                safeLogW("AppOps package proxy failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    fun installAppOpsServiceManagerPackageProxy(
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): Boolean {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty()) {
            safeLogW("AppOps ServiceManager proxy skipped: empty host or aliases")
            return false
        }
        return synchronized(appOpsProxyLock) {
            try {
                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val cacheField = serviceManagerClass.getDeclaredField("sCache").apply {
                    isAccessible = true
                }
                @Suppress("UNCHECKED_CAST")
                val cache = cacheField.get(null) as? MutableMap<String, Any?>
                    ?: run {
                        safeLogW("AppOps ServiceManager proxy skipped: sCache unavailable")
                        return false
                    }
                var baseBinder = cache[APP_OPS_SERVICE_NAME] as? IBinder
                if (baseBinder == null) {
                    baseBinder = serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply {
                        isAccessible = true
                    }.invoke(null, APP_OPS_SERVICE_NAME) as? IBinder
                }
                if (baseBinder == null) {
                    safeLogW("AppOps ServiceManager proxy skipped: base binder is null")
                    return false
                }
                if (Proxy.isProxyClass(baseBinder.javaClass)) {
                    val handler = runCatching { Proxy.getInvocationHandler(baseBinder) }.getOrNull()
                    if (handler is AppOpsServiceManagerBinderInvocationHandler) {
                        handler.addAliases(aliases, hostPackageName)
                        safeLogD("AppOps ServiceManager proxy aliases updated: ${aliases.joinToString(",")} -> $hostPackageName")
                        return true
                    }
                }
                val appOpsInterface = Class.forName(APP_OPS_DESCRIPTOR)
                val stubClass = Class.forName("$APP_OPS_DESCRIPTOR\$Stub")
                val baseService = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply {
                    isAccessible = true
                }.invoke(null, baseBinder)
                    ?: run {
                        safeLogW("AppOps ServiceManager proxy skipped: base service is null")
                        return false
                    }
                val proxyBinder = createAppOpsServiceManagerBinderProxy(
                    baseBinder = baseBinder,
                    baseService = baseService,
                    appOpsInterface = appOpsInterface,
                    sourcePackages = aliases,
                    hostPackageName = hostPackageName
                ) ?: return false
                cache[APP_OPS_SERVICE_NAME] = proxyBinder
                safeLogD("AppOps ServiceManager proxy installed: ${aliases.joinToString(",")} -> $hostPackageName")
                true
            } catch (e: Throwable) {
                safeLogW("AppOps ServiceManager proxy failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    internal fun createAppOpsServiceManagerBinderProxy(
        baseBinder: IBinder,
        baseService: Any,
        appOpsInterface: Class<*>,
        sourcePackages: Collection<String>,
        hostPackageName: String,
        runtimeUidProvider: () -> Int = { Process.myUid() }
    ): IBinder? {
        if (!IInterface::class.java.isAssignableFrom(appOpsInterface)) {
            safeLogW("AppOps ServiceManager proxy skipped: service interface is not IInterface")
            return null
        }
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty()) return null
        val serviceHandler = AppOpsPackageInvocationHandler(
            base = baseService,
            hostPackageName = hostPackageName,
            initialAliases = aliases,
            runtimeUidProvider = runtimeUidProvider
        )
        val serviceProxy = Proxy.newProxyInstance(
            appOpsInterface.classLoader,
            arrayOf(appOpsInterface),
            serviceHandler
        ) as IInterface
        return Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
            AppOpsServiceManagerBinderInvocationHandler(baseBinder, serviceProxy, serviceHandler, hostPackageName, aliases)
        ) as IBinder
    }

    private class NotificationPackageInvocationHandler(
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
            val currentAliases = synchronized(aliases) { aliases.toSet() }
            val patchedArgs = args?.let {
                remapNotificationPackageArgs(method.name, it, currentAliases, hostPackageName)
            }
            return try {
                method.invoke(base, *(patchedArgs ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private class AppOpsPackageInvocationHandler(
        private val base: Any,
        private var hostPackageName: String,
        initialAliases: Collection<String>,
        private val runtimeUidProvider: () -> Int = { Process.myUid() }
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
            val currentAliases = synchronized(aliases) { aliases.toSet() }
            val patchedArgs = args?.let {
                remapAppOpsPackageArgs(method.name, it, currentAliases, hostPackageName, runtimeUidProvider())
            }
            return try {
                method.invoke(base, *(patchedArgs ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private class AppOpsServiceManagerBinderInvocationHandler(
        private val baseBinder: IBinder,
        private val serviceProxy: IInterface,
        private val serviceHandler: AppOpsPackageInvocationHandler,
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
                return invokeObjectMethod(proxy, method, args)
            }
            if (method.name == "queryLocalInterface") {
                val descriptor = args?.firstOrNull() as? String
                if (descriptor == APP_OPS_DESCRIPTOR) {
                    return serviceProxy
                }
            }
            return try {
                method.invoke(baseBinder, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun findAppOpsServiceField(appOpsManager: Any): java.lang.reflect.Field? {
        var current: Class<*>? = appOpsManager.javaClass
        while (current != null) {
            val fields = runCatching { current.declaredFields.toList() }.getOrDefault(emptyList())
            fields.firstOrNull { field ->
                field.name == "mService" || field.type.name == "com.android.internal.app.IAppOpsService"
            }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun isAppOpsPackageMethod(methodName: String): Boolean {
        val lower = methodName.lowercase()
        return lower.contains("op") ||
            lower.contains("package") ||
            lower.contains("mode") ||
            lower.contains("watch")
    }

    private fun appOpsUidIndexBeforePackage(
        methodName: String,
        args: Array<Any?>,
        packageIndex: Int
    ): Int? {
        if (!appOpsMethodHasUidBeforePackage(methodName)) return null
        for (index in packageIndex - 1 downTo 0) {
            if (args[index] is Int) return index
        }
        return null
    }

    private fun appOpsMethodHasUidBeforePackage(methodName: String): Boolean {
        val lower = methodName.lowercase()
        if (lower.contains("watch")) return false
        return lower.contains("operation") ||
            lower.contains("package") ||
            lower.contains("mode") ||
            lower.contains("active")
    }

    private data class AttributionRemapResult(
        val value: Any?,
        val changed: Boolean
    )

    private fun remapAttributionSourceLike(
        value: Any?,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int,
        depth: Int = 0
    ): AttributionRemapResult {
        if (value == null || depth > 8) return AttributionRemapResult(value, changed = false)
        val type = value.javaClass
        return when {
            type.isArray -> remapAttributionArray(value, aliases, hostPackageName, runtimeUid, depth)
            type.name == "android.content.AttributionSource" ->
                remapPublicAttributionSource(value, aliases, hostPackageName, runtimeUid, depth)
            type.name == "android.content.AttributionSourceState" ||
                type.simpleName.endsWith("AttributionSourceState") ->
                remapAttributionSourceState(value, aliases, hostPackageName, runtimeUid, depth)
            else -> AttributionRemapResult(value, changed = false)
        }
    }

    private fun remapAttributionArray(
        value: Any,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int,
        depth: Int
    ): AttributionRemapResult {
        val length = ReflectArray.getLength(value)
        var copy: Any? = null
        var changed = false
        for (index in 0 until length) {
            val item = ReflectArray.get(value, index)
            val remapped = remapAttributionSourceLike(item, aliases, hostPackageName, runtimeUid, depth + 1)
            if (remapped.changed) {
                if (copy == null) {
                    copy = ReflectArray.newInstance(value.javaClass.componentType, length)
                    for (copyIndex in 0 until length) {
                        ReflectArray.set(copy, copyIndex, ReflectArray.get(value, copyIndex))
                    }
                }
                ReflectArray.set(copy, index, remapped.value)
                changed = true
            }
        }
        return AttributionRemapResult(copy ?: value, changed)
    }

    private fun remapPublicAttributionSource(
        value: Any,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int,
        depth: Int
    ): AttributionRemapResult {
        return runCatching {
            val sourceClass = value.javaClass
            val packageName = invokeNoArg(value, "getPackageName") as? String
            val next = invokeNoArg(value, "getNext")
            val remappedNext = remapAttributionSourceLike(next, aliases, hostPackageName, runtimeUid, depth + 1)
            val shouldRewriteSelf = packageName in aliases
            if (!shouldRewriteSelf && !remappedNext.changed) {
                return@runCatching AttributionRemapResult(value, changed = false)
            }
            val builderClass = Class.forName("android.content.AttributionSource\$Builder")
            val builder = builderClass.getDeclaredConstructor(sourceClass).apply {
                isAccessible = true
            }.newInstance(value)
            if (shouldRewriteSelf) {
                invokeSingleArg(builder, "setPackageName", hostPackageName)
                invokeSingleArg(builder, "setUid", runtimeUid)
            }
            if (remappedNext.changed && remappedNext.value != null) {
                invokeSingleArg(builder, "setNext", remappedNext.value)
            }
            val built = builderClass.getDeclaredMethod("build").apply {
                isAccessible = true
            }.invoke(builder)
            AttributionRemapResult(built ?: value, changed = true)
        }.getOrElse {
            AttributionRemapResult(value, changed = false)
        }
    }

    private fun remapAttributionSourceState(
        value: Any,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int,
        depth: Int
    ): AttributionRemapResult {
        return runCatching {
            val type = value.javaClass
            val target = newInstanceWithCopiedFields(value) ?: value
            var changed = false
            val packageField = findFieldInHierarchy(type, "packageName")
            val uidField = findFieldInHierarchy(type, "uid")
            val packageName = packageField?.get(value) as? String
            if (packageName in aliases) {
                packageField?.set(target, hostPackageName)
                uidField?.set(target, runtimeUid)
                changed = true
            }
            for (field in allInstanceFields(type)) {
                if (!field.isAttributionNestedField()) continue
                val nested = field.get(value) ?: continue
                val remapped = remapAttributionSourceLike(nested, aliases, hostPackageName, runtimeUid, depth + 1)
                if (remapped.changed) {
                    field.set(target, remapped.value)
                    changed = true
                }
            }
            AttributionRemapResult(if (changed) target else value, changed)
        }.getOrElse {
            AttributionRemapResult(value, changed = false)
        }
    }

    private fun newInstanceWithCopiedFields(source: Any): Any? {
        return runCatching {
            val constructor = source.javaClass.getDeclaredConstructor().apply {
                isAccessible = true
            }
            val target = constructor.newInstance()
            for (field in allInstanceFields(source.javaClass)) {
                field.set(target, field.get(source))
            }
            target
        }.getOrNull()
    }

    private fun java.lang.reflect.Field.isAttributionNestedField(): Boolean {
        val fieldType = type
        if (name == "next") return true
        if (fieldType.isArray) {
            val componentName = fieldType.componentType?.name.orEmpty()
            return componentName.contains("AttributionSource")
        }
        return fieldType.name.contains("AttributionSource")
    }

    private fun allInstanceFields(type: Class<*>): List<java.lang.reflect.Field> {
        val result = mutableListOf<java.lang.reflect.Field>()
        var current: Class<*>? = type
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                result += field
            }
            current = current.superclass
        }
        return result
    }

    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? =
        runCatching {
            target.javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.isEmpty()
            }?.invoke(target)
        }.getOrNull()

    private fun invokeSingleArg(target: Any, methodName: String, arg: Any): Boolean =
        runCatching {
            val method = target.javaClass.methods.firstOrNull { candidate ->
                candidate.name == methodName &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0].acceptsArgument(arg)
            } ?: return@runCatching false
            method.isAccessible = true
            method.invoke(target, arg)
            true
        }.getOrDefault(false)

    private fun Class<*>.acceptsArgument(arg: Any): Boolean =
        when {
            isPrimitive && this == Integer.TYPE -> arg is Int
            isPrimitive && this == java.lang.Long.TYPE -> arg is Long
            else -> isAssignableFrom(arg.javaClass)
        }

    private fun invokeObjectMethod(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? =
        when (method.name) {
            "toString" -> "MultiAppAppOpsServiceManagerBinderProxy(${System.identityHashCode(proxy)})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }

    private fun safeLogD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun safeLogW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun installActivityManagerSingletonProxy(
        ownerClassName: String,
        singletonFieldName: String,
        interfaceClassName: String,
        originalPkg: String,
        stubPkg: String
    ): Boolean {
        return try {
            val ownerClass = Class.forName(ownerClassName)
            val singletonField = ownerClass.getDeclaredField(singletonFieldName).apply {
                isAccessible = true
            }
            val singleton = singletonField.get(null) ?: return false
            val singletonClass = Class.forName("android.util.Singleton")
            val instanceField = singletonClass.getDeclaredField("mInstance").apply {
                isAccessible = true
            }
            var base = instanceField.get(singleton)
            if (base == null) {
                base = singleton.javaClass.getDeclaredMethod("get").apply {
                    isAccessible = true
                }.invoke(singleton)
            }
            if (base == null) {
                Log.w(TAG, "$ownerClassName.$singletonFieldName proxy skipped: base is null")
                return false
            }

            val iface = Class.forName(interfaceClassName)
            if (Proxy.isProxyClass(base.javaClass)) {
                Log.d(TAG, "$interfaceClassName already proxied")
                return true
            }

            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface)
            ) { _, method, args ->
                if (args != null && method.name.startsWith("startActiv")) {
                    remapStartActivityArgs(method.name, args, originalPkg, stubPkg)
                }
                try {
                    method.invoke(base, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
            instanceField.set(singleton, proxy)
            Log.d(TAG, "Installed $interfaceClassName proxy via $ownerClassName.$singletonFieldName")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "$ownerClassName.$singletonFieldName proxy failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}

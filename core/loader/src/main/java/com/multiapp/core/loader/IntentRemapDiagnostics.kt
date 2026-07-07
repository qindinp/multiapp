package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
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
    private val notificationProxyLock = Any()

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
            Log.d(TAG, "NotificationManager.$methodName package remap: ${aliases.joinToString(",")} -> $hostPackageName")
        }
        return if (changed) patched else args
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

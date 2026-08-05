package com.multiapp.core.loader

import android.os.Process
import android.util.Log
import com.multiapp.core.common.AndroidCompat
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

data class VirtualContentProviderIdentityProxyInstallResult(
    val activityManagerProxyInstalled: Boolean,
    val providerCacheInspected: Boolean,
    val cachedProviderRecordCount: Int,
    val cachedProviderPatchedCount: Int,
    val settingsProviderCacheInspectedCount: Int = 0,
    val settingsProviderCacheClearedCount: Int = 0,
    val failures: List<String> = emptyList()
) {
    val complete: Boolean
        get() = activityManagerProxyInstalled && providerCacheInspected && failures.isEmpty()
}

/**
 * Applies the real host identity only when a provider call leaves the process.
 * Guest Context APIs continue to expose the origin package.
 */
object VirtualContentProviderIdentityProxy {
    private const val TAG = "VirtualProviderIdentity"
    private const val CONTENT_PROVIDER_INTERFACE = "android.content.IContentProvider"
    private val installLock = Any()

    fun install(
        sourcePackages: Collection<String>,
        hostPackageName: String,
        runtimeUid: Int = Process.myUid()
    ): VirtualContentProviderIdentityProxyInstallResult {
        val aliases = sourcePackages
            .filter { it.isNotBlank() && it != hostPackageName }
            .toSet()
        if (hostPackageName.isBlank() || aliases.isEmpty()) {
            return VirtualContentProviderIdentityProxyInstallResult(
                activityManagerProxyInstalled = false,
                providerCacheInspected = false,
                cachedProviderRecordCount = 0,
                cachedProviderPatchedCount = 0,
                failures = listOf("HOST_OR_ALIAS_MISSING")
            )
        }

        return synchronized(installLock) {
            runCatching { AndroidCompat.bypassHiddenApis() }
            val failures = mutableListOf<String>()
            val providerInterface = runCatching { Class.forName(CONTENT_PROVIDER_INTERFACE) }
                .getOrElse { error ->
                    return@synchronized VirtualContentProviderIdentityProxyInstallResult(
                        activityManagerProxyInstalled = false,
                        providerCacheInspected = false,
                        cachedProviderRecordCount = 0,
                        cachedProviderPatchedCount = 0,
                        failures = listOf("I_CONTENT_PROVIDER_UNAVAILABLE:${error.javaClass.simpleName}")
                    )
                }
            val activityManagerInstalled = runCatching {
                installActivityManagerProxy(
                    providerInterface = providerInterface,
                    aliases = aliases,
                    hostPackageName = hostPackageName,
                    runtimeUid = runtimeUid
                )
            }.getOrElse { error ->
                failures += "ACTIVITY_MANAGER_PROXY_FAILED:${error.javaClass.simpleName}:${error.message.orEmpty()}"
                false
            }
            val cacheResult = inspectAndPatchActivityThreadProviderCache(
                providerInterface = providerInterface,
                aliases = aliases,
                hostPackageName = hostPackageName,
                runtimeUid = runtimeUid
            )
            val settingsCacheResult = clearSettingsProviderCaches()
            failures += cacheResult.failures
            failures += settingsCacheResult.failures
            VirtualContentProviderIdentityProxyInstallResult(
                activityManagerProxyInstalled = activityManagerInstalled,
                providerCacheInspected = cacheResult.inspected,
                cachedProviderRecordCount = cacheResult.recordCount,
                cachedProviderPatchedCount = cacheResult.patchedCount,
                settingsProviderCacheInspectedCount = settingsCacheResult.inspectedCount,
                settingsProviderCacheClearedCount = settingsCacheResult.clearedCount,
                failures = failures.distinct()
            ).also { result ->
                Log.i(
                    TAG,
                    "install complete=${result.complete} am=${result.activityManagerProxyInstalled} " +
                        "cacheInspected=${result.providerCacheInspected} " +
                        "cached=${result.cachedProviderRecordCount}/${result.cachedProviderPatchedCount} " +
                        "settingsCache=${result.settingsProviderCacheInspectedCount}/" +
                        "${result.settingsProviderCacheClearedCount} " +
                        "host=$hostPackageName aliases=${aliases.joinToString(",")} " +
                        "failures=${result.failures.joinToString("|")}"
                )
            }
        }
    }

    internal fun wrapProviderForInterface(
        provider: Any,
        providerInterface: Class<*>,
        sourcePackages: Collection<String>,
        hostPackageName: String,
        runtimeUid: Int
    ): Any {
        if (Proxy.isProxyClass(provider.javaClass)) {
            val currentHandler = runCatching { Proxy.getInvocationHandler(provider) }.getOrNull()
            if (currentHandler is ContentProviderIdentityInvocationHandler) {
                currentHandler.addAliases(sourcePackages, hostPackageName)
                return provider
            }
        }
        return Proxy.newProxyInstance(
            providerInterface.classLoader,
            arrayOf(providerInterface),
            ContentProviderIdentityInvocationHandler(
                base = provider,
                hostPackageName = hostPackageName,
                initialAliases = sourcePackages,
                runtimeUid = runtimeUid
            )
        )
    }

    internal fun patchHolderProviderForInterface(
        holder: Any?,
        providerInterface: Class<*>,
        sourcePackages: Collection<String>,
        hostPackageName: String,
        runtimeUid: Int
    ): ProviderHolderIdentityPatchResult {
        if (holder == null) return ProviderHolderIdentityPatchResult(found = false, patched = false)
        val providerField = allFields(holder.javaClass).firstOrNull { field ->
            providerInterface.isAssignableFrom(field.type) ||
                runCatching { providerInterface.isInstance(field.get(holder)) }.getOrDefault(false)
        } ?: return ProviderHolderIdentityPatchResult(found = false, patched = false)
        val provider = runCatching { providerField.get(holder) }.getOrNull()
            ?: return ProviderHolderIdentityPatchResult(found = true, patched = false)
        val proxy = runCatching {
            wrapProviderForInterface(
                provider = provider,
                providerInterface = providerInterface,
                sourcePackages = sourcePackages,
                hostPackageName = hostPackageName,
                runtimeUid = runtimeUid
            )
        }.getOrElse { error ->
            return ProviderHolderIdentityPatchResult(
                found = true,
                patched = false,
                failure = "PROXY_CREATE_FAILED:${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
        return runCatching {
            providerField.set(holder, proxy)
            ProviderHolderIdentityPatchResult(
                found = true,
                patched = providerField.get(holder) === proxy,
                failure = if (providerField.get(holder) === proxy) null else "PROVIDER_FIELD_VERIFY_FAILED"
            )
        }.getOrElse { error ->
            ProviderHolderIdentityPatchResult(
                found = true,
                patched = false,
                failure = "PROVIDER_FIELD_SET_FAILED:${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    internal fun clearCachedProviderHolder(holder: Any): Boolean {
        val providerField = findField(holder.javaClass, "mContentProvider") ?: return false
        return synchronized(holder) {
            runCatching {
                providerField.set(holder, null)
                providerField.get(holder) == null
            }.getOrDefault(false)
        }
    }

    private fun installActivityManagerProxy(
        providerInterface: Class<*>,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int
    ): Boolean {
        val ownerClass = Class.forName("android.app.ActivityManager")
        val singletonField = ownerClass.getDeclaredField("IActivityManagerSingleton").apply {
            isAccessible = true
        }
        val singleton = singletonField.get(null) ?: return false
        val singletonClass = Class.forName("android.util.Singleton")
        val instanceField = singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }
        var base = instanceField.get(singleton)
        if (base == null) {
            base = singleton.javaClass.getDeclaredMethod("get").apply { isAccessible = true }.invoke(singleton)
        }
        if (base == null) return false
        if (Proxy.isProxyClass(base.javaClass)) {
            val currentHandler = runCatching { Proxy.getInvocationHandler(base) }.getOrNull()
            if (currentHandler is ActivityManagerProviderInvocationHandler) {
                return currentHandler.addAliases(aliases, hostPackageName)
            }
        }
        val activityManagerInterface = Class.forName("android.app.IActivityManager")
        val proxy = Proxy.newProxyInstance(
            activityManagerInterface.classLoader,
            arrayOf(activityManagerInterface),
            ActivityManagerProviderInvocationHandler(
                base = base,
                providerInterface = providerInterface,
                hostPackageName = hostPackageName,
                initialAliases = aliases,
                runtimeUid = runtimeUid
            )
        )
        instanceField.set(singleton, proxy)
        return instanceField.get(singleton) === proxy
    }

    private fun inspectAndPatchActivityThreadProviderCache(
        providerInterface: Class<*>,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int
    ): ProviderCachePatchResult {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
                ?: return@runCatching ProviderCachePatchResult(
                    inspected = false,
                    recordCount = 0,
                    patchedCount = 0,
                    failures = listOf("ACTIVITY_THREAD_UNAVAILABLE")
                )
            val providerMapField = findField(currentThread.javaClass, "mProviderMap")
                ?: return@runCatching ProviderCachePatchResult(
                    inspected = false,
                    recordCount = 0,
                    patchedCount = 0,
                    failures = listOf("ACTIVITY_THREAD_PROVIDER_MAP_UNAVAILABLE")
                )
            val providerMap = providerMapField.get(currentThread) as? Map<*, *>
                ?: return@runCatching ProviderCachePatchResult(
                    inspected = false,
                    recordCount = 0,
                    patchedCount = 0,
                    failures = listOf("ACTIVITY_THREAD_PROVIDER_MAP_INVALID")
                )
            val failures = mutableListOf<String>()
            var patchedCount = 0
            synchronized(providerMap) {
                providerMap.values.filterNotNull().forEachIndexed { index, record ->
                    val patch = patchProviderClientRecord(
                        record = record,
                        providerInterface = providerInterface,
                        aliases = aliases,
                        hostPackageName = hostPackageName,
                        runtimeUid = runtimeUid
                    )
                    patchedCount += patch.patchedCount
                    failures += patch.failures.map { "CACHE_RECORD_$index:$it" }
                }
            }
            ProviderCachePatchResult(
                inspected = true,
                recordCount = providerMap.size,
                patchedCount = patchedCount,
                failures = failures
            )
        }.getOrElse { error ->
            ProviderCachePatchResult(
                inspected = false,
                recordCount = 0,
                patchedCount = 0,
                failures = listOf("PROVIDER_CACHE_INSPECTION_FAILED:${error.javaClass.simpleName}:${error.message.orEmpty()}")
            )
        }
    }

    private fun clearSettingsProviderCaches(): SettingsProviderCacheResetResult {
        var inspectedCount = 0
        var clearedCount = 0
        val failures = mutableListOf<String>()
        for (className in SETTINGS_PROVIDER_CACHE_CLASSES) {
            val settingsClass = runCatching { Class.forName(className) }.getOrNull() ?: continue
            for (field in declaredFields(settingsClass)) {
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val holder = runCatching { field.get(null) }.getOrNull() ?: continue
                if (!holder.javaClass.name.contains("Settings\$ContentProviderHolder")) continue
                val providerField = findField(holder.javaClass, "mContentProvider")
                if (providerField == null) {
                    failures += "$className:${field.name}:CONTENT_PROVIDER_FIELD_UNAVAILABLE"
                    continue
                }
                inspectedCount += 1
                synchronized(holder) {
                    runCatching {
                        if (providerField.get(holder) != null) {
                            if (clearCachedProviderHolder(holder)) {
                                clearedCount += 1
                            } else {
                                error("provider cache remained populated")
                            }
                        }
                    }.onFailure { error ->
                        failures += "$className:${field.name}:CACHE_CLEAR_FAILED:${error.javaClass.simpleName}"
                    }
                }
            }
        }
        if (inspectedCount == 0) failures += "SETTINGS_PROVIDER_CACHE_HOLDER_UNAVAILABLE"
        return SettingsProviderCacheResetResult(inspectedCount, clearedCount, failures)
    }

    private fun patchProviderClientRecord(
        record: Any,
        providerInterface: Class<*>,
        aliases: Set<String>,
        hostPackageName: String,
        runtimeUid: Int
    ): ProviderRecordPatchResult {
        var patchedCount = 0
        val failures = mutableListOf<String>()
        for (field in allFields(record.javaClass)) {
            val value = runCatching { field.get(record) }.getOrNull() ?: continue
            if (providerInterface.isInstance(value)) {
                val proxyResult = runCatching {
                    wrapProviderForInterface(value, providerInterface, aliases, hostPackageName, runtimeUid)
                }
                if (proxyResult.isFailure) {
                    val error = proxyResult.exceptionOrNull()
                    failures += "${field.name}:PROXY_CREATE_FAILED:${error?.javaClass?.simpleName.orEmpty()}"
                    continue
                }
                val proxy = proxyResult.getOrThrow()
                runCatching { field.set(record, proxy) }
                    .onSuccess { patchedCount += 1 }
                    .onFailure { error ->
                        failures += "${field.name}:FIELD_SET_FAILED:${error.javaClass.simpleName}"
                    }
                continue
            }
            if (field.name.contains("holder", ignoreCase = true)) {
                val holderPatch = patchHolderProviderForInterface(
                    holder = value,
                    providerInterface = providerInterface,
                    sourcePackages = aliases,
                    hostPackageName = hostPackageName,
                    runtimeUid = runtimeUid
                )
                if (holderPatch.patched) patchedCount += 1
                holderPatch.failure?.let { failures += "${field.name}:$it" }
            }
        }
        return ProviderRecordPatchResult(patchedCount, failures)
    }

    private fun allFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.declaredFields.toList() }.getOrDefault(emptyList()).forEach { field ->
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                    runCatching { field.isAccessible = true }
                    fields += field
                }
            }
            current = current.superclass
        }
        return fields
    }

    private fun declaredFields(type: Class<*>): List<Field> =
        runCatching { type.declaredFields.toList() }.getOrDefault(emptyList()).onEach { field ->
            runCatching { field.isAccessible = true }
        }

    private fun findField(type: Class<*>, name: String): Field? =
        allFields(type).firstOrNull { it.name == name }

    private class ActivityManagerProviderInvocationHandler(
        private val base: Any,
        private val providerInterface: Class<*>,
        private val hostPackageName: String,
        initialAliases: Collection<String>,
        private val runtimeUid: Int
    ) : InvocationHandler {
        private val aliases = linkedSetOf<String>()

        init {
            addAliases(initialAliases, hostPackageName)
        }

        fun addAliases(sourcePackages: Collection<String>, requestedHostPackageName: String): Boolean {
            if (requestedHostPackageName != hostPackageName) return false
            synchronized(aliases) {
                aliases += sourcePackages.filter { it.isNotBlank() && it != hostPackageName }
            }
            return true
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return objectMethod(proxy, method, args)
            val currentAliases = synchronized(aliases) { aliases.toSet() }
            // IActivityManager 权限门禁方法的容器侧安全默认值：
            // 虚拟进程 uid 无 signature 级权限（如 android.permission.DUMP）时，透传给系统 AMS
            // 会被拒绝（SecurityException 杀进程，微博 AqtsWrapper→SystemStateUtil 实测命中）。
            // 返回非 null 表示在代理层拦截返回安全默认值；null 表示不拦截、正常透传。
            amsPermissionGatedSafeDefault(method.name)?.let { safeDefault ->
                Log.w(TAG, "AMS ${method.name} intercepted -> permission-gated safe default (DUMP)")
                return safeDefault
            }
            val patchedArgs = args?.copyOf()
            if (
                method.name == "getContentProvider" &&
                patchedArgs != null &&
                patchedArgs.size > 1 &&
                patchedArgs[1] in currentAliases
            ) {
                patchedArgs[1] = hostPackageName
            }
            val result = invokeBase(base, method, patchedArgs)
            if (!method.name.startsWith("getContentProvider") || result == null) return result
            val patch = patchHolderProviderForInterface(
                holder = result,
                providerInterface = providerInterface,
                sourcePackages = currentAliases,
                hostPackageName = hostPackageName,
                runtimeUid = runtimeUid
            )
            if (patch.found && !patch.patched) {
                throw IllegalStateException(
                    "Unable to install IContentProvider identity proxy: ${patch.failure.orEmpty()}"
                )
            }
            return result
        }
    }

    private class ContentProviderIdentityInvocationHandler(
        private val base: Any,
        private val hostPackageName: String,
        initialAliases: Collection<String>,
        private val runtimeUid: Int
    ) : InvocationHandler {
        private val aliases = linkedSetOf<String>()

        init {
            addAliases(initialAliases, hostPackageName)
        }

        fun addAliases(sourcePackages: Collection<String>, requestedHostPackageName: String): Boolean {
            if (requestedHostPackageName != hostPackageName) return false
            synchronized(aliases) {
                aliases += sourcePackages.filter { it.isNotBlank() && it != hostPackageName }
            }
            return true
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return objectMethod(proxy, method, args)
            val currentAliases = synchronized(aliases) { aliases.toSet() }
            val patchedArgs = args?.let {
                IntentRemapDiagnostics.remapContentProviderIdentityArgs(
                    method = method,
                    args = it,
                    sourcePackages = currentAliases,
                    hostPackageName = hostPackageName,
                    runtimeUid = runtimeUid
                )
            }
            return invokeBase(base, method, patchedArgs)
        }
    }

    private fun invokeBase(base: Any, method: Method, args: Array<Any?>?): Any? = try {
        method.invoke(base, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    /**
     * IActivityManager 权限门禁方法的安全默认值。
     *
     * 背景（2026-08-05 Round 4 真机）：微博 AqtsWrapper 后台线程调
     * ActivityManager.getHistoricalProcessExitReasons()（API 30+，需 signature 级
     * android.permission.DUMP），虚拟进程 uid 无权限，透传系统 AMS 被拒 →
     * 未捕获 SecurityException 杀进程。容器侧在此拦截返回空列表，避免崩溃。
     *
     * 仅对确定无副作用、且容器无法合法取得数据的方法返回安全默认值；
     * 返回 null 表示不拦截。
     */
    internal fun amsPermissionGatedSafeDefault(methodName: String): Any? = when (methodName) {
        "getHistoricalProcessExitReasons" -> runCatching {
            // ParceledListSlice 为 @SystemApi，SDK android.jar 不含该类，需反射创建。
            // 返回类型必须匹配 IActivityManager.getHistoricalProcessExitReasons 的
            // ParceledListSlice<ProcessExitReason>，否则 AIDL 侧转换抛 ClassCastException
            // （Round 5 真机实测：返回 Kotlin EmptyList 触发
            //  "Couldn't convert result of type kotlin.collections.EmptyList to ParceledListSlice"）。
            val clazz = Class.forName("android.content.pm.ParceledListSlice")
            clazz.getConstructor(List::class.java).newInstance(emptyList<Any>())
        }.getOrNull()
        else -> null
    }

    private fun objectMethod(proxy: Any, method: Method, args: Array<Any?>?): Any? =
        when (method.name) {
            "toString" -> "MultiAppProviderIdentityProxy(${System.identityHashCode(proxy)})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }

    internal data class ProviderHolderIdentityPatchResult(
        val found: Boolean,
        val patched: Boolean,
        val failure: String? = null
    )

    private data class ProviderCachePatchResult(
        val inspected: Boolean,
        val recordCount: Int,
        val patchedCount: Int,
        val failures: List<String>
    )

    private data class ProviderRecordPatchResult(
        val patchedCount: Int,
        val failures: List<String>
    )

    private data class SettingsProviderCacheResetResult(
        val inspectedCount: Int,
        val clearedCount: Int,
        val failures: List<String>
    )

    private val SETTINGS_PROVIDER_CACHE_CLASSES = listOf(
        "android.provider.Settings\$System",
        "android.provider.Settings\$Secure",
        "android.provider.Settings\$Global",
        "android.provider.Settings\$Config"
    )
}

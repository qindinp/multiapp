package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.VersionedPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

interface VirtualPackageManagerProxyMarker {
    fun virtualPackageManagerOriginal(): Any
}

class VirtualPackageManagerInvocationHandler(
    private val originalPackageManager: Any,
    private val service: VirtualPackageService,
    private val runtimeUid: Int,
    private val virtualizeUidQueries: Boolean = false,
    private val serviceResolver: VirtualPackageManagerServiceResolver? = null
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        if (method.declaringClass == VirtualPackageManagerProxyMarker::class.java) {
            return originalPackageManager
        }
        if (method.declaringClass == Any::class.java || method.declaringClass == java.lang.Object::class.java) {
            return invokeObjectMethod(proxy, method, args)
        }

        val virtualResult = dispatchVirtual(method, args ?: emptyArray<Any?>())
        if (virtualResult.handled) return virtualResult.value

        return invokeOriginal(method, args)
    }

    private fun dispatchVirtual(method: Method, args: Array<Any?>): VirtualDispatchResult = when (method.name) {
        "getPackageInfo", "getPackageInfoVersioned" -> packageNameArg(args, 0)
            ?.let { packageName -> serviceForPackage(packageName)?.getPackageInfo(packageName)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getApplicationInfo" -> packageNameArg(args, 0)
            ?.let { packageName -> serviceForPackage(packageName)?.getApplicationInfo(packageName)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getActivityInfo" -> componentArg(args, 0)
            ?.let { component -> serviceForComponent(component)?.getActivityInfo(component)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getServiceInfo" -> componentArg(args, 0)
            ?.let { component -> serviceForComponent(component)?.getServiceInfo(component)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getReceiverInfo" -> componentArg(args, 0)
            ?.let { component -> serviceForComponent(component)?.getReceiverInfo(component)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getProviderInfo" -> componentArg(args, 0)
            ?.let { component -> serviceForComponent(component)?.getProviderInfo(component)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getComponentEnabledSetting" -> componentArg(args, 0)
            ?.let { component -> serviceForComponent(component)?.getComponentEnabledSetting(component)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "setComponentEnabledSetting" -> componentArg(args, 0)
            ?.let { component ->
                val newState = intArg(args, 1) ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                val flags = intArg(args, 2) ?: 0
                serviceForComponent(component)
                    ?.takeIf { it.setComponentEnabledSetting(component, newState, flags) }
                    ?.let { VirtualDispatchResult.Handled(null) }
            }
            ?: VirtualDispatchResult.NotHandled

        "resolveContentProvider" -> stringArg(args, 0)
            ?.let { authority -> serviceForAuthority(authority)?.resolveContentProvider(authority)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "queryIntentActivities" -> queryIntentActivities(method, args)

        "resolveIntent", "resolveActivity" -> intentArg(args, 0)
            ?.let { intent -> serviceForIntent(intent)?.resolveActivity(intent)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "queryIntentServices" -> queryIntent(args) { packageService, intent ->
            packageService.queryIntentServices(intent)
        }

        "resolveService" -> intentArg(args, 0)
            ?.let { intent -> serviceForIntent(intent)?.resolveService(intent)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "queryIntentReceivers" -> queryIntent(args) { packageService, intent ->
            packageService.queryBroadcastReceivers(intent)
        }

        "queryIntentContentProviders" -> queryIntent(args) { packageService, intent ->
            packageService.queryIntentContentProviders(intent)
        }

        "getInstalledPackages" -> installedPackages(method, args).handled()

        "getInstalledApplications" -> installedApplications(method, args).handled()

        "checkPermission" -> {
            val permissionName = stringArg(args, 0)
            val packageName = stringArg(args, 1)
            if (permissionName != null && packageName != null) {
                serviceForPackage(packageName)?.checkPermission(permissionName, packageName)?.handled()
                    ?: VirtualDispatchResult.NotHandled
            } else {
                VirtualDispatchResult.NotHandled
            }
        }

        "getPackageUid" -> packageNameArg(args, 0)
            ?.let { packageName -> serviceForPackage(packageName)?.getPackageUid(packageName, runtimeUid)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getPackagesForUid" -> intArg(args, 0)
            ?.let { uid -> packagesForUid(method, args, uid)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getNameForUid" -> intArg(args, 0)
            ?.let { uid -> nameForUid(method, args, uid)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "getPackagesHoldingPermissions" -> stringArrayArg(args, 0)
            ?.let { permissions -> service.getPackagesHoldingPermissions(permissions).takeIf { it.isNotEmpty() }?.handled() }
            ?: VirtualDispatchResult.NotHandled

        "queryContentProviders" -> queryContentProviders(args)?.handled()
            ?: VirtualDispatchResult.NotHandled

        "isInstantApp" -> packageNameArg(args, 0)
            ?.let { packageName -> serviceForPackage(packageName)?.isInstantApp(packageName)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        else -> VirtualDispatchResult.NotHandled
    }

    private fun packagesForUid(method: Method, args: Array<Any?>, uid: Int): Array<String>? {
        if (!virtualizeUidQueries || uid != runtimeUid) return null
        val basePackages = invokeOriginal(method, args) as? Array<*>
        val virtualPackages = service.getPackagesForUid(uid, runtimeUid).orEmpty()
        return (basePackages?.filterIsInstance<String>().orEmpty() + virtualPackages)
            .filter { it.isNotBlank() }
            .distinct()
            .toTypedArray()
    }

    private fun nameForUid(method: Method, args: Array<Any?>, uid: Int): String? {
        if (!virtualizeUidQueries || uid != runtimeUid) return null
        val baseName = invokeOriginal(method, args) as? String
        return baseName?.takeIf { it.isNotBlank() } ?: service.getNameForUid(uid, runtimeUid)
    }

    private fun queryContentProviders(args: Array<Any?>): List<Any>? {
        val uid = intArg(args, 1) ?: return null
        return if (uid == runtimeUid) service.queryContentProviders(stringArg(args, 0), uid, runtimeUid) else null
    }

    private fun installedPackages(method: Method, args: Array<Any?>): Any {
        val virtualPackages = service.getInstalledPackages()
        val originalResult = invokeOriginalOrNull(method, args)
        return mergeAggregateResult(
            originalResult = originalResult,
            virtualItems = virtualPackages,
            itemClass = PackageInfo::class.java,
            keyOf = { packageInfo: PackageInfo -> packageInfo.packageName }
        )
    }

    private fun installedApplications(method: Method, args: Array<Any?>): Any {
        val virtualApplications = service.getInstalledApplications()
        val originalResult = invokeOriginalOrNull(method, args)
        return mergeAggregateResult(
            originalResult = originalResult,
            virtualItems = virtualApplications,
            itemClass = ApplicationInfo::class.java,
            keyOf = { applicationInfo: ApplicationInfo -> applicationInfo.packageName }
        )
    }

    private fun queryIntentActivities(method: Method, args: Array<Any?>): VirtualDispatchResult {
        val intent = intentArg(args, 0) ?: return VirtualDispatchResult.NotHandled
        val packageService = serviceForIntent(intent) ?: service
        val virtualActivities = packageService.queryIntentActivities(intent)
        if (virtualActivities.isEmpty()) return VirtualDispatchResult.NotHandled
        if (!intent.isUnscopedLauncherIntent()) return virtualActivities.handled()

        return VirtualDispatchResult.Handled(
            mergeAggregateResult(
                originalResult = invokeOriginalOrNull(method, args),
                virtualItems = virtualActivities,
                itemClass = ResolveInfo::class.java,
                keyOf = { resolveInfo: ResolveInfo -> resolveInfo.activityInfo?.let { "${it.packageName}/${it.name}" } }
            )
        )
    }

    private fun queryIntent(
        args: Array<Any?>,
        query: (VirtualPackageService, Intent) -> List<Any>
    ): VirtualDispatchResult {
        val intent = intentArg(args, 0) ?: return VirtualDispatchResult.NotHandled
        val packageService = serviceForIntent(intent) ?: service
        return query(packageService, intent).takeIf { it.isNotEmpty() }?.handled()
            ?: VirtualDispatchResult.NotHandled
    }

    private fun serviceForPackage(packageName: String): VirtualPackageService? =
        serviceResolver?.serviceForPackage(packageName) ?: service.takeIf { service.getPackageInfo(packageName) != null }

    private fun serviceForComponent(component: ComponentName): VirtualPackageService? =
        serviceResolver?.serviceForComponent(component) ?: service.takeIf { service.getPackageInfo(component.packageName) != null }

    private fun serviceForAuthority(authority: String): VirtualPackageService? =
        serviceResolver?.serviceForAuthority(authority) ?: service.takeIf { service.resolveContentProvider(authority) != null }

    private fun serviceForIntent(intent: Intent): VirtualPackageService? =
        serviceResolver?.serviceForIntent(intent) ?: service.takeIf { it.resolveActivity(intent) != null || it.resolveService(intent) != null }

    private fun Intent.isUnscopedLauncherIntent(): Boolean {
        if (runCatching { component }.getOrNull() != null) return false
        if (runCatching { `package` }.getOrNull() != null) return false
        return runCatching {
            action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        }.getOrDefault(false)
    }

    private fun invokeOriginal(method: Method, args: Array<Any?>?): Any? = try {
        method.isAccessible = true
        method.invoke(originalPackageManager, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun invokeOriginalOrNull(method: Method, args: Array<Any?>?): Any? =
        runCatching { invokeOriginal(method, args) }.getOrNull()

    private fun <T : Any> mergeAggregateResult(
        originalResult: Any?,
        virtualItems: List<T>,
        itemClass: Class<T>,
        keyOf: (T) -> String?
    ): Any {
        val originalItems = originalResult.extractList(itemClass)
        val merged = mergeByKey(originalItems, virtualItems, keyOf)
        if (originalResult == null || originalResult is List<*>) return merged
        return rebuildListContainer(originalResult, merged) ?: merged
    }

    private fun <T : Any> mergeByKey(
        originalItems: List<T>,
        virtualItems: List<T>,
        keyOf: (T) -> String?
    ): List<T> {
        val seen = linkedSetOf<String>()
        val merged = mutableListOf<T>()
        (originalItems + virtualItems).forEach { item ->
            val key = keyOf(item)
            if (key.isNullOrBlank() || seen.add(key)) {
                merged += item
            }
        }
        return merged
    }

    private fun <T : Any> Any?.extractList(itemClass: Class<T>): List<T> {
        if (this == null) return emptyList()
        if (this is List<*>) return typedItems(itemClass)
        val getList = runCatching { javaClass.getMethod("getList") }.getOrNull()
        val listFromMethod = runCatching { getList?.invoke(this) as? List<*> }.getOrNull()
        if (listFromMethod != null) return listFromMethod.typedItems(itemClass)
        val listField = runCatching { javaClass.getDeclaredField("mList").apply { isAccessible = true } }.getOrNull()
        val listFromField = runCatching { listField?.get(this) as? List<*> }.getOrNull()
        return listFromField?.typedItems(itemClass).orEmpty()
    }

    private fun <T : Any> List<*>.typedItems(itemClass: Class<T>): List<T> {
        return mapNotNull { item ->
            if (itemClass.isInstance(item)) itemClass.cast(item) else null
        }
    }

    private fun <T : Any> rebuildListContainer(originalResult: Any, merged: List<T>): Any? {
        val listConstructor = runCatching { originalResult.javaClass.getConstructor(List::class.java) }.getOrNull()
        if (listConstructor != null) {
            return runCatching { listConstructor.newInstance(merged) }.getOrNull()
        }
        val listField = runCatching { originalResult.javaClass.getDeclaredField("mList").apply { isAccessible = true } }.getOrNull()
        if (listField != null) {
            return runCatching {
                listField.set(originalResult, merged.toMutableList())
                originalResult
            }.getOrNull()
        }
        return null
    }

    private fun invokeObjectMethod(proxy: Any, method: Method, args: Array<Any?>?): Any? = when (method.name) {
        "toString" -> "VirtualPackageManagerInvocationHandler(proxy=${System.identityHashCode(proxy)})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.getOrNull(0)
        else -> invokeOriginal(method, args)
    }

    private fun packageNameArg(args: Array<Any?>, index: Int): String? = when (val value = args.getOrNull(index)) {
        is String -> value
        is VersionedPackage -> value.packageName
        else -> null
    }

    private fun stringArg(args: Array<Any?>, index: Int): String? = args.getOrNull(index) as? String

    private fun stringArrayArg(args: Array<Any?>, index: Int): Array<String>? {
        val value = args.getOrNull(index) as? Array<*> ?: return null
        return value.filterIsInstance<String>().takeIf { it.size == value.size }?.toTypedArray()
    }

    private fun componentArg(args: Array<Any?>, index: Int): ComponentName? = args.getOrNull(index) as? ComponentName

    private fun intentArg(args: Array<Any?>, index: Int): Intent? = args.getOrNull(index) as? Intent

    private fun intArg(args: Array<Any?>, index: Int): Int? = args.getOrNull(index) as? Int

    private fun Any.handled(): VirtualDispatchResult = VirtualDispatchResult.Handled(this)

    private sealed class VirtualDispatchResult {
        abstract val handled: Boolean
        abstract val value: Any?

        data class Handled(override val value: Any?) : VirtualDispatchResult() {
            override val handled: Boolean = true
        }

        object NotHandled : VirtualDispatchResult() {
            override val handled: Boolean = false
            override val value: Any? = null
        }
    }
}

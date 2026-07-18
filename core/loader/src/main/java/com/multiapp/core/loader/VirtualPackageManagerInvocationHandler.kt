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

    init {
        require(runtimeUid > 0) { "runtimeUid must be a positive Android application UID" }
    }

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
            ?.let { packageName ->
                serviceForPackage(packageName)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.getPackageInfo(packageName, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "getApplicationInfo" -> packageNameArg(args, 0)
            ?.let { packageName ->
                serviceForPackage(packageName)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.getApplicationInfo(packageName, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "getActivityInfo" -> componentArg(args, 0)
            ?.let { component ->
                serviceForComponent(component)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.getActivityInfo(component, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "getServiceInfo" -> componentArg(args, 0)
            ?.let { component ->
                serviceForComponent(component)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.getServiceInfo(component, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "getReceiverInfo" -> componentArg(args, 0)
            ?.let { component ->
                serviceForComponent(component)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.getReceiverInfo(component, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "getProviderInfo" -> componentArg(args, 0)
            ?.let { component ->
                serviceForComponent(component)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.getProviderInfo(component, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "getApplicationEnabledSetting" -> packageNameArg(args, 0)
            ?.let { packageName ->
                serviceForPackage(packageName)?.getApplicationEnabledSetting(packageName)?.handled()
            }
            ?: VirtualDispatchResult.NotHandled

        "setApplicationEnabledSetting" -> packageNameArg(args, 0)
            ?.let { packageName ->
                val newState = intArg(args, 1) ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                val flags = intArg(args, 2) ?: 0
                serviceForPackage(packageName)
                    ?.takeIf { it.setApplicationEnabledSetting(packageName, newState, flags) }
                    ?.let { VirtualDispatchResult.Handled(null) }
            }
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
            ?.let { authority ->
                serviceForAuthority(authority)?.let { packageService ->
                    VirtualDispatchResult.Handled(packageService.resolveContentProvider(authority, queryFlags(args)))
                }
            }
            ?: VirtualDispatchResult.NotHandled

        "queryIntentActivities" -> queryIntentActivities(method, args)

        "resolveIntent", "resolveActivity" -> intentArg(args, 0)
            ?.let { intent ->
                val resolvedType = resolvedTypeArg(method, args)
                serviceForIntent(intent, resolvedType)
                    ?.resolveActivity(intent, queryFlags(args), resolvedType)
                    ?.handled()
            }
            ?: VirtualDispatchResult.NotHandled

        "queryIntentServices" -> queryIntent(method, args) { packageService, intent, resolvedType ->
            packageService.queryIntentServices(intent, queryFlags(args), resolvedType)
        }

        "resolveService" -> intentArg(args, 0)
            ?.let { intent ->
                val resolvedType = resolvedTypeArg(method, args)
                serviceForIntent(intent, resolvedType)
                    ?.resolveService(intent, queryFlags(args), resolvedType)
                    ?.handled()
            }
            ?: VirtualDispatchResult.NotHandled

        "queryIntentReceivers" -> queryIntent(method, args) { packageService, intent, resolvedType ->
            packageService.queryBroadcastReceivers(intent, queryFlags(args), resolvedType)
        }

        "queryIntentContentProviders" -> queryIntent(method, args) { packageService, intent, resolvedType ->
            packageService.queryIntentContentProviders(intent, queryFlags(args), resolvedType)
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

        "checkSignatures" -> checkSignatures(args)

        "checkUidSignatures" -> checkUidSignatures(args)

        "hasSigningCertificate" -> hasSigningCertificate(args)

        "hasUidSigningCertificate" -> hasUidSigningCertificate(args)

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
            ?.let { permissions ->
                service.getPackagesHoldingPermissions(permissions, queryFlags(args))
                    .takeIf { it.isNotEmpty() }
                    ?.let { packages -> shapeListResult(method, args, packages).handled() }
            }
            ?: VirtualDispatchResult.NotHandled

        "queryContentProviders" -> queryContentProviders(method, args)?.handled()
            ?: VirtualDispatchResult.NotHandled

        "isInstantApp" -> packageNameArg(args, 0)
            ?.let { packageName -> serviceForPackage(packageName)?.isInstantApp(packageName)?.handled() }
            ?: VirtualDispatchResult.NotHandled

        else -> VirtualDispatchResult.NotHandled
    }

    private fun checkSignatures(args: Array<Any?>): VirtualDispatchResult {
        val firstPackage = stringArg(args, 0)
        val secondPackage = stringArg(args, 1)
        if (firstPackage == null || secondPackage == null) return checkUidSignatures(args)
        val firstService = serviceForPackage(firstPackage)
        val secondService = serviceForPackage(secondPackage)
        if (firstService == null && secondService == null) return VirtualDispatchResult.NotHandled
        val firstSigningInfo = firstService?.signingInfoForPackage(firstPackage)
        val secondSigningInfo = secondService?.signingInfoForPackage(secondPackage)
        return VirtualDispatchResult.Handled(signatureComparison(firstSigningInfo, secondSigningInfo))
    }

    private fun checkUidSignatures(args: Array<Any?>): VirtualDispatchResult {
        val firstUid = intArg(args, 0) ?: return VirtualDispatchResult.NotHandled
        val secondUid = intArg(args, 1) ?: return VirtualDispatchResult.NotHandled
        if (firstUid != runtimeUid && secondUid != runtimeUid) return VirtualDispatchResult.NotHandled
        val firstSigningInfo = service.signingInfoForRuntimeUid(firstUid)
        val secondSigningInfo = service.signingInfoForRuntimeUid(secondUid)
        return VirtualDispatchResult.Handled(signatureComparison(firstSigningInfo, secondSigningInfo))
    }

    private fun hasSigningCertificate(args: Array<Any?>): VirtualDispatchResult {
        val packageName = stringArg(args, 0) ?: return hasUidSigningCertificate(args)
        val certificate = byteArrayArg(args, 1) ?: return VirtualDispatchResult.NotHandled
        val type = intArg(args, 2) ?: return VirtualDispatchResult.NotHandled
        val packageService = serviceForPackage(packageName) ?: return VirtualDispatchResult.NotHandled
        return VirtualDispatchResult.Handled(
            packageService.hasSigningCertificate(packageName, certificate, type) ?: false
        )
    }

    private fun hasUidSigningCertificate(args: Array<Any?>): VirtualDispatchResult {
        val uid = intArg(args, 0) ?: return VirtualDispatchResult.NotHandled
        if (uid != runtimeUid) return VirtualDispatchResult.NotHandled
        val certificate = byteArrayArg(args, 1) ?: return VirtualDispatchResult.NotHandled
        val type = intArg(args, 2) ?: return VirtualDispatchResult.NotHandled
        return VirtualDispatchResult.Handled(
            service.hasRuntimeUidSigningCertificate(uid, certificate, type) ?: false
        )
    }

    private fun signatureComparison(
        first: VirtualPackageSigningInfo?,
        second: VirtualPackageSigningInfo?
    ): Int = if (first?.matches(second) == true) {
        PackageManager.SIGNATURE_MATCH
    } else {
        PackageManager.SIGNATURE_NO_MATCH
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

    private fun queryContentProviders(method: Method, args: Array<Any?>): Any? {
        val uid = intArg(args, 1) ?: return null
        return if (uid == runtimeUid) {
            val providers = service.queryContentProviders(
                stringArg(args, 0),
                uid,
                runtimeUid,
                queryFlags(args, 2)
            )
            shapeListResult(method, args, providers)
        } else {
            null
        }
    }

    private fun installedPackages(method: Method, args: Array<Any?>): Any {
        val virtualPackages = service.getInstalledPackages(queryFlags(args, 0))
        val originalResult = invokeOriginalOrNull(method, args)
        return mergeAggregateResult(
            originalResult = originalResult,
            virtualItems = virtualPackages,
            itemClass = PackageInfo::class.java,
            keyOf = { packageInfo: PackageInfo -> packageInfo.packageName }
        )
    }

    private fun installedApplications(method: Method, args: Array<Any?>): Any {
        val virtualApplications = service.getInstalledApplications(queryFlags(args, 0))
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
        val resolvedType = resolvedTypeArg(method, args)
        val packageService = serviceForIntent(intent, resolvedType) ?: service
        val virtualActivities = packageService.queryIntentActivities(intent, queryFlags(args), resolvedType)
        if (virtualActivities.isEmpty()) return VirtualDispatchResult.NotHandled
        if (!intent.isUnscopedLauncherIntent()) {
            return shapeListResult(method, args, virtualActivities).handled()
        }

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
        method: Method,
        args: Array<Any?>,
        query: (VirtualPackageService, Intent, String?) -> List<Any>
    ): VirtualDispatchResult {
        val intent = intentArg(args, 0) ?: return VirtualDispatchResult.NotHandled
        val resolvedType = resolvedTypeArg(method, args)
        val packageService = serviceForIntent(intent, resolvedType) ?: service
        return query(packageService, intent, resolvedType)
            .takeIf { it.isNotEmpty() }
            ?.let { items -> shapeListResult(method, args, items).handled() }
            ?: VirtualDispatchResult.NotHandled
    }

    private fun serviceForPackage(packageName: String): VirtualPackageService? =
        serviceResolver?.serviceForPackage(packageName) ?: service.takeIf { it.handlesPackage(packageName) }

    private fun serviceForComponent(component: ComponentName): VirtualPackageService? =
        serviceResolver?.serviceForComponent(component) ?: service.takeIf { it.handlesPackage(component.packageName) }

    private fun serviceForAuthority(authority: String): VirtualPackageService? =
        serviceResolver?.serviceForAuthority(authority) ?: service.takeIf { service.resolveContentProvider(authority) != null }

    private fun serviceForIntent(intent: Intent, resolvedType: String? = null): VirtualPackageService? =
        serviceResolver?.serviceForIntent(intent) ?: service.takeIf {
            it.resolveActivity(intent, VirtualPackageQueryFlags.NONE, resolvedType) != null ||
                it.resolveService(intent, VirtualPackageQueryFlags.NONE, resolvedType) != null
        }

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

    /** Preserve hidden IPackageManager list-container return types on Binder calls. */
    private fun <T : Any> shapeListResult(
        method: Method,
        args: Array<Any?>,
        virtualItems: List<T>
    ): Any {
        if (List::class.java.isAssignableFrom(method.returnType)) return virtualItems

        // Use the system result only as a ParceledListSlice-shaped template. Its
        // items belong to the real package namespace and must not leak into a
        // package-scoped virtual query.
        val originalContainer = invokeOriginalOrNull(method, args)
        if (originalContainer != null && originalContainer !is List<*>) {
            rebuildListContainer(originalContainer, virtualItems)?.let { return it }
        }
        return rebuildListContainer(method.returnType, virtualItems) ?: virtualItems
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
        val listConstructor = listContainerConstructor(originalResult.javaClass)
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

    private fun <T : Any> rebuildListContainer(containerType: Class<*>, items: List<T>): Any? {
        val constructor = listContainerConstructor(containerType) ?: return null
        return runCatching { constructor.newInstance(items) }.getOrNull()
    }

    private fun listContainerConstructor(containerType: Class<*>): java.lang.reflect.Constructor<*>? =
        runCatching { containerType.getConstructor(List::class.java) }.getOrNull()
            ?: runCatching {
                containerType.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            }.getOrNull()

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

    private fun byteArrayArg(args: Array<Any?>, index: Int): ByteArray? = args.getOrNull(index) as? ByteArray

    private fun componentArg(args: Array<Any?>, index: Int): ComponentName? = args.getOrNull(index) as? ComponentName

    private fun intentArg(args: Array<Any?>, index: Int): Intent? = args.getOrNull(index) as? Intent

    private fun resolvedTypeArg(method: Method, args: Array<Any?>, intentIndex: Int = 0): String? {
        val resolvedTypeIndex = intentIndex + 1
        if (method.parameterTypes.getOrNull(resolvedTypeIndex) != String::class.java) return null
        return args.getOrNull(resolvedTypeIndex) as? String
    }

    private fun intArg(args: Array<Any?>, index: Int): Int? = args.getOrNull(index) as? Int

    private fun queryFlags(args: Array<Any?>, startIndex: Int = 1): Long {
        val value = args.asSequence()
            .drop(startIndex)
            .filterIsInstance<Number>()
            .firstOrNull()
        return when (value) {
            is Int -> VirtualPackageQueryFlags.fromInt(value)
            null -> VirtualPackageQueryFlags.NONE
            else -> value.toLong()
        }
    }

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

package com.multiapp.core.loader

import android.content.Context
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

data class VirtualStorageManagerProxyInstallResult(
    val managerPatched: Boolean,
    val serviceManagerPatched: Boolean,
    val reason: String = ""
) {
    val installed: Boolean
        get() = managerPatched || serviceManagerPatched

    val complete: Boolean
        get() = managerPatched && serviceManagerPatched
}

internal data class VirtualStorageManagerRoute(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val hostPackageName: String,
    val dataRoot: String,
    val processSlot: String?
) {
    val guestPackageNames: Set<String> = setOf(originPackageName, virtualPackageName)
        .filterTo(linkedSetOf()) { it.isNotBlank() && it != hostPackageName }

    fun resolveGuestAppScopedPath(path: String): VirtualStoragePathResolution {
        if (path.isBlank() || guestPackageNames.isEmpty()) return VirtualStoragePathResolution.NotGuest
        val normalized = path.replace('\\', '/')
        val match = guestPackageNames.firstNotNullOfOrNull { packageName ->
            matchGuestExternalPath(normalized, packageName)
        } ?: return VirtualStoragePathResolution.NotGuest
        if (normalized.indexOf('\u0000') >= 0 || normalized.split('/').any { it == ".." }) {
            return VirtualStoragePathResolution.Rejected("PATH_TRAVERSAL_REJECTED")
        }

        val root = runCatching { File(dataRoot).canonicalFile }.getOrElse {
            return VirtualStoragePathResolution.Rejected("DATA_ROOT_UNRESOLVED")
        }
        val targetBase = when (match.kind) {
            VirtualExternalPathKind.DATA_FILES -> File(root, VirtualContextStorage.EXTERNAL_FILES_DIR)
            VirtualExternalPathKind.DATA_CACHE -> File(root, VirtualContextStorage.EXTERNAL_CACHE_DIR)
            VirtualExternalPathKind.DATA_OTHER -> File(root, "external_data")
            VirtualExternalPathKind.OBB -> File(root, "obb")
        }
        val target = runCatching {
            val cleanRemainder = match.remainder.trim('/')
            (if (cleanRemainder.isBlank()) targetBase else File(targetBase, cleanRemainder)).canonicalFile
        }.getOrElse {
            return VirtualStoragePathResolution.Rejected("TARGET_UNRESOLVED")
        }
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        if (target.absolutePath != rootPath && !target.absolutePath.startsWith(rootPath + File.separator)) {
            return VirtualStoragePathResolution.Rejected("TARGET_ESCAPES_DATA_ROOT")
        }
        return VirtualStoragePathResolution.Mapped(target)
    }

    private fun matchGuestExternalPath(path: String, packageName: String): VirtualExternalPathMatch? {
        val roots = listOf(
            Regex("^/storage/emulated/[0-9]+/Android/(data|obb)/${Regex.escape(packageName)}(?:/(.*))?$"),
            Regex("^/(?:sdcard|mnt/sdcard)/Android/(data|obb)/${Regex.escape(packageName)}(?:/(.*))?$"),
            Regex("^/storage/self/primary/Android/(data|obb)/${Regex.escape(packageName)}(?:/(.*))?$")
        )
        val match = roots.firstNotNullOfOrNull { it.matchEntire(path) } ?: return null
        val namespace = match.groupValues[1]
        val remainder = match.groupValues.getOrNull(2).orEmpty()
        if (namespace == "obb") {
            return VirtualExternalPathMatch(VirtualExternalPathKind.OBB, remainder)
        }
        return when {
            remainder == "files" -> VirtualExternalPathMatch(VirtualExternalPathKind.DATA_FILES, "")
            remainder.startsWith("files/") ->
                VirtualExternalPathMatch(VirtualExternalPathKind.DATA_FILES, remainder.removePrefix("files/"))
            remainder == "cache" -> VirtualExternalPathMatch(VirtualExternalPathKind.DATA_CACHE, "")
            remainder.startsWith("cache/") ->
                VirtualExternalPathMatch(VirtualExternalPathKind.DATA_CACHE, remainder.removePrefix("cache/"))
            else -> VirtualExternalPathMatch(VirtualExternalPathKind.DATA_OTHER, remainder)
        }
    }
}

internal sealed interface VirtualStoragePathResolution {
    data object NotGuest : VirtualStoragePathResolution
    data class Mapped(val target: File) : VirtualStoragePathResolution
    data class Rejected(val reason: String) : VirtualStoragePathResolution
}

internal data class VirtualStorageMkdirsInterception(
    val success: Boolean,
    val originalPath: String,
    val redirectedPath: String?,
    val reason: String,
    val returnValue: Any?
)

private data class VirtualExternalPathMatch(
    val kind: VirtualExternalPathKind,
    val remainder: String
)

private enum class VirtualExternalPathKind {
    DATA_FILES,
    DATA_CACHE,
    DATA_OTHER,
    OBB
}

/**
 * VirtualApp-style mount service adapter. Guest app-scoped external mkdirs are
 * handled inside the instance data root instead of crossing into vold with a
 * package name that does not own the host UID.
 */
object VirtualStorageManagerServiceProxy {
    private const val TAG = "MultiApp.Storage"
    private const val SERVICE_NAME = "mount"
    private const val DESCRIPTOR = "android.os.storage.IStorageManager"
    private val installLock = Any()

    fun installDetailed(
        context: Context?,
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        hostPackageName: String,
        dataRoot: String,
        processSlot: String?
    ): VirtualStorageManagerProxyInstallResult {
        if (context == null || instanceId.isBlank() || hostPackageName.isBlank() || dataRoot.isBlank()) {
            return VirtualStorageManagerProxyInstallResult(false, false, "ROUTE_INPUT_INCOMPLETE")
        }
        val route = VirtualStorageManagerRoute(
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            hostPackageName = hostPackageName,
            dataRoot = dataRoot,
            processSlot = processSlot
        )
        if (route.guestPackageNames.isEmpty()) {
            return VirtualStorageManagerProxyInstallResult(false, false, "GUEST_PACKAGE_ALIASES_EMPTY")
        }
        return synchronized(installLock) {
            val serviceManagerPatched = patchServiceManager(route)
            val managerPatched = patchManagerService(
                manager = runCatching { context.getSystemService(Context.STORAGE_SERVICE) }.getOrNull(),
                route = route
            )
            val reason = when {
                managerPatched && serviceManagerPatched -> ""
                !managerPatched && !serviceManagerPatched -> "MANAGER_AND_SERVICE_MANAGER_PATCH_FAILED"
                !managerPatched -> "MANAGER_PATCH_FAILED"
                else -> "SERVICE_MANAGER_PATCH_FAILED"
            }
            VirtualStorageManagerProxyInstallResult(managerPatched, serviceManagerPatched, reason)
        }
    }

    internal fun interceptMkdirs(
        method: Method,
        args: Array<Any?>,
        route: VirtualStorageManagerRoute
    ): VirtualStorageMkdirsInterception? {
        if (method.name != "mkdirs") return null
        val path = args.filterIsInstance<String>().firstOrNull { it.startsWith('/') } ?: return null
        return when (val resolution = route.resolveGuestAppScopedPath(path)) {
            VirtualStoragePathResolution.NotGuest -> null
            is VirtualStoragePathResolution.Rejected -> VirtualStorageMkdirsInterception(
                success = false,
                originalPath = path,
                redirectedPath = null,
                reason = resolution.reason,
                returnValue = mkdirsReturnValue(method.returnType, false)
            )
            is VirtualStoragePathResolution.Mapped -> {
                val success = runCatching {
                    resolution.target.isDirectory || resolution.target.mkdirs() || resolution.target.isDirectory
                }.getOrDefault(false)
                VirtualStorageMkdirsInterception(
                    success = success,
                    originalPath = path,
                    redirectedPath = resolution.target.absolutePath,
                    reason = if (success) "INSTANCE_DIRECTORY_READY" else "INSTANCE_DIRECTORY_CREATE_FAILED",
                    returnValue = mkdirsReturnValue(method.returnType, success)
                )
            }
        }
    }

    private fun patchManagerService(manager: Any?, route: VirtualStorageManagerRoute): Boolean = runCatching {
        manager ?: return@runCatching false
        val serviceField = findServiceField(manager) ?: return@runCatching false
        val receiver = if (Modifier.isStatic(serviceField.modifiers)) null else manager
        val baseService = serviceField.get(receiver) ?: return@runCatching false
        if (Proxy.isProxyClass(baseService.javaClass)) {
            val handler = runCatching { Proxy.getInvocationHandler(baseService) }.getOrNull()
            if (handler is StorageInvocationHandler) return@runCatching handler.bind(route)
        }
        val serviceInterface = Class.forName(DESCRIPTOR)
        serviceField.set(
            receiver,
            Proxy.newProxyInstance(
                serviceInterface.classLoader,
                arrayOf(serviceInterface),
                StorageInvocationHandler(baseService, route)
            )
        )
        true
    }.onFailure(::logInstallFailure).getOrDefault(false)

    private fun patchServiceManager(route: VirtualStorageManagerRoute): Boolean = runCatching {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val cacheField = serviceManagerClass.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as? MutableMap<String, Any?> ?: return@runCatching false
        var baseBinder = cache[SERVICE_NAME] as? IBinder
        if (baseBinder == null) {
            baseBinder = serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply {
                isAccessible = true
            }.invoke(null, SERVICE_NAME) as? IBinder
        }
        baseBinder ?: return@runCatching false
        if (Proxy.isProxyClass(baseBinder.javaClass)) {
            val handler = runCatching { Proxy.getInvocationHandler(baseBinder) }.getOrNull()
            if (handler is StorageBinderInvocationHandler) return@runCatching handler.bind(route)
        }

        val serviceInterface = Class.forName(DESCRIPTOR)
        val stubClass = Class.forName("$DESCRIPTOR\$Stub")
        val baseService = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply {
            isAccessible = true
        }.invoke(null, baseBinder) ?: return@runCatching false
        val serviceHandler = StorageInvocationHandler(baseService, route)
        val serviceProxy = Proxy.newProxyInstance(
            serviceInterface.classLoader,
            arrayOf(serviceInterface),
            serviceHandler
        ) as IInterface
        cache[SERVICE_NAME] = Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
            StorageBinderInvocationHandler(baseBinder, serviceProxy, serviceHandler, route)
        ) as IBinder
        true
    }.onFailure(::logInstallFailure).getOrDefault(false)

    private fun findServiceField(manager: Any): java.lang.reflect.Field? {
        var current: Class<*>? = manager.javaClass
        while (current != null) {
            val field = runCatching { current.declaredFields.toList() }.getOrDefault(emptyList())
                .firstOrNull {
                    it.name in setOf("mStorageManager", "mService", "sStorageManager", "sService") ||
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

    private class StorageInvocationHandler(
        private val base: Any,
        route: VirtualStorageManagerRoute
    ) : InvocationHandler {
        @Volatile
        private var route: VirtualStorageManagerRoute = route

        fun bind(candidate: VirtualStorageManagerRoute): Boolean {
            if (route.instanceId != candidate.instanceId || route.processSlot != candidate.processSlot) return false
            route = candidate
            return true
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return objectMethod(proxy, method.name, args)
            val currentArgs = args ?: emptyArray()
            interceptMkdirs(method, currentArgs, route)?.let { interception ->
                Log.d(
                    TAG,
                    "IStorageManager.mkdirs ${interception.reason}: " +
                        "${interception.originalPath} -> ${interception.redirectedPath.orEmpty()}"
                )
                return interception.returnValue
            }
            val patched = currentArgs.copyOf()
            for (index in patched.indices) {
                if (patched[index] in route.guestPackageNames) patched[index] = route.hostPackageName
            }
            return try {
                method.invoke(base, *patched)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private class StorageBinderInvocationHandler(
        private val baseBinder: IBinder,
        private val serviceProxy: IInterface,
        private val serviceHandler: StorageInvocationHandler,
        route: VirtualStorageManagerRoute
    ) : InvocationHandler {
        @Volatile
        private var route: VirtualStorageManagerRoute = route

        fun bind(candidate: VirtualStorageManagerRoute): Boolean {
            if (route.instanceId != candidate.instanceId || route.processSlot != candidate.processSlot) return false
            route = candidate
            return serviceHandler.bind(candidate)
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return objectMethod(proxy, method.name, args)
            if (method.name == "queryLocalInterface" && args?.firstOrNull() == DESCRIPTOR) return serviceProxy
            return try {
                method.invoke(baseBinder, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private fun mkdirsReturnValue(returnType: Class<*>, success: Boolean): Any? = when (returnType) {
        java.lang.Void.TYPE, java.lang.Void::class.java -> null
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> success
        java.lang.Long.TYPE, java.lang.Long::class.java -> if (success) 0L else -1L
        else -> if (success) 0 else -1
    }

    private fun objectMethod(proxy: Any, methodName: String, args: Array<Any?>?): Any? = when (methodName) {
        "toString" -> "MultiAppStorageProxy(${System.identityHashCode(proxy)})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> null
    }

    private fun logInstallFailure(error: Throwable) {
        runCatching { Log.w(TAG, "Storage manager proxy install failed", error) }
    }
}

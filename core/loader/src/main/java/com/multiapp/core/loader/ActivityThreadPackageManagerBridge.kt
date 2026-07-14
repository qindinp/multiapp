package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.lang.reflect.Field
import java.lang.reflect.Proxy

interface ActivityThreadPackageManagerBridge {
    fun readCurrentPackageManager(): ActivityThreadPackageManagerReadResult

    fun createProxy(
        originalPackageManager: Any,
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int
    ): Any

    fun writePackageManager(proxy: Any): ActivityThreadPackageManagerPatchResult

    fun patchApplicationPackageManagers(
        hostContext: Context?,
        proxy: Any
    ): List<ActivityThreadPackageManagerPatchResult>
}

class ReflectionActivityThreadPackageManagerBridge : ActivityThreadPackageManagerBridge {
    override fun readCurrentPackageManager(): ActivityThreadPackageManagerReadResult {
        val interfaceName = runCatching { iPackageManagerClass().name }.getOrNull()
        val field = activityThreadPackageManagerField()
            ?: return ActivityThreadPackageManagerReadResult(
                packageManager = null,
                interfaceName = interfaceName,
                packageManagerClassName = null,
                skippedReason = "S_PACKAGE_MANAGER_FIELD_NOT_FOUND"
            )
        val packageManager = runCatching { field.get(null) }.getOrNull()
        return ActivityThreadPackageManagerReadResult(
            packageManager = packageManager,
            interfaceName = interfaceName,
            packageManagerClassName = packageManager?.javaClass?.name,
            skippedReason = if (packageManager == null) "S_PACKAGE_MANAGER_NULL" else null
        )
    }

    override fun createProxy(
        originalPackageManager: Any,
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int
    ): Any {
        val interfaceClass = iPackageManagerClass()
        val original = originalPackageManager.unwrapVirtualPackageManagerProxy()
        return Proxy.newProxyInstance(
            VirtualPackageManagerProxyMarker::class.java.classLoader,
            arrayOf(interfaceClass, VirtualPackageManagerProxyMarker::class.java),
            VirtualPackageManagerInvocationHandler(
                originalPackageManager = original,
                service = VirtualPackageManagerServiceRegistry.serviceForPackage(snapshot.virtualPackageName)
                    ?: VirtualPackageService(snapshot, runtimeUid),
                runtimeUid = runtimeUid,
                virtualizeUidQueries = true,
                serviceResolver = VirtualPackageManagerServiceRegistry
            )
        )
    }

    override fun writePackageManager(proxy: Any): ActivityThreadPackageManagerPatchResult {
        val field = activityThreadPackageManagerField()
            ?: return ActivityThreadPackageManagerPatchResult(
                target = "ActivityThread.sPackageManager",
                patched = false,
                skippedReason = "S_PACKAGE_MANAGER_FIELD_NOT_FOUND"
            )
        return runCatching {
            field.set(null, proxy)
            ActivityThreadPackageManagerPatchResult(
                target = "ActivityThread.sPackageManager",
                patched = true
            )
        }.getOrElse { error ->
            ActivityThreadPackageManagerPatchResult(
                target = "ActivityThread.sPackageManager",
                patched = false,
                skippedReason = "S_PACKAGE_MANAGER_SET_FAILED:${error.javaClass.simpleName}"
            )
        }
    }

    override fun patchApplicationPackageManagers(
        hostContext: Context?,
        proxy: Any
    ): List<ActivityThreadPackageManagerPatchResult> {
        val targets = mutableListOf<Pair<String, Any?>>()
        targets += "hostContext.packageManager" to runCatching { hostContext?.packageManager }.getOrNull()
        targets += "hostApplicationContext.packageManager" to runCatching { hostContext?.applicationContext?.packageManager }.getOrNull()
        val activityThread = runCatching { ActivityThreadCompat.currentActivityThread() }.getOrNull()
        targets += "currentApplication.packageManager" to currentApplicationPackageManager(activityThread)

        return targets.distinctBy { it.second }.map { (target, packageManager) ->
            patchApplicationPackageManager(target, packageManager, proxy)
        }
    }

    private fun currentApplicationPackageManager(activityThread: Any?): Any? = runCatching {
        if (activityThread == null) return@runCatching null
        ActivityThreadCompat.currentApplication(activityThread).packageManager
    }.getOrNull()

    private fun patchApplicationPackageManager(
        target: String,
        packageManager: Any?,
        proxy: Any
    ): ActivityThreadPackageManagerPatchResult {
        if (packageManager == null) {
            return ActivityThreadPackageManagerPatchResult(target, patched = false, skippedReason = "PACKAGE_MANAGER_UNAVAILABLE")
        }
        val field = findFieldInHierarchy(packageManager.javaClass, "mPM")
            ?: return ActivityThreadPackageManagerPatchResult(target, patched = false, skippedReason = "MPM_FIELD_NOT_FOUND")
        return runCatching {
            field.isAccessible = true
            field.set(packageManager, proxy)
            ActivityThreadPackageManagerPatchResult(target, patched = true)
        }.getOrElse { error ->
            ActivityThreadPackageManagerPatchResult(
                target = target,
                patched = false,
                skippedReason = "MPM_SET_FAILED:${error.javaClass.simpleName}"
            )
        }
    }

    private fun activityThreadPackageManagerField(): Field? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        activityThreadClass.getDeclaredField("sPackageManager").apply { isAccessible = true }
    }.getOrNull()

    private fun iPackageManagerClass(): Class<*> = Class.forName("android.content.pm.IPackageManager")

    private fun findMethodInHierarchy(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun findFieldInHierarchy(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun Any.unwrapVirtualPackageManagerProxy(): Any =
        (this as? VirtualPackageManagerProxyMarker)?.virtualPackageManagerOriginal() ?: this
}

data class ActivityThreadPackageManagerReadResult(
    val packageManager: Any?,
    val interfaceName: String?,
    val packageManagerClassName: String?,
    val skippedReason: String? = null
)

data class ActivityThreadPackageManagerPatchResult(
    val target: String,
    val patched: Boolean,
    val skippedReason: String? = null
)

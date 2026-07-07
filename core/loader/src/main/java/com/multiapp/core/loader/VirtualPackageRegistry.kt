package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry for hosted package snapshots.
 *
 * This is the first service-like layer in the hosted runtime. Later phases can
 * replace it with a Binder-backed VPMS, but guest self package queries should
 * already route through this registry instead of ad-hoc Context fields.
 */
class VirtualPackageRegistry {
    private val byInstanceId = ConcurrentHashMap<String, VirtualPackageSnapshot>()
    private val byVirtualPackageName = ConcurrentHashMap<String, String>()
    private val byOriginPackageName = ConcurrentHashMap<String, MutableSet<String>>()

    fun register(snapshot: VirtualPackageSnapshot): VirtualPackageSnapshot {
        byInstanceId[snapshot.instanceId] = snapshot
        byVirtualPackageName[snapshot.virtualPackageName] = snapshot.instanceId
        byOriginPackageName.compute(snapshot.originPackageName) { _, existing ->
            (existing ?: linkedSetOf()).apply { add(snapshot.instanceId) }
        }
        return snapshot
    }

    fun getByInstanceId(instanceId: String): VirtualPackageSnapshot? = byInstanceId[instanceId]

    fun getByPackageName(packageName: String?): VirtualPackageSnapshot? {
        if (packageName.isNullOrBlank()) return null
        byVirtualPackageName[packageName]?.let { instanceId -> return byInstanceId[instanceId] }
        return getUniqueByOriginPackageName(packageName)
    }

    fun getUniqueByOriginPackageName(originPackageName: String?): VirtualPackageSnapshot? {
        if (originPackageName.isNullOrBlank()) return null
        val instanceIds = byOriginPackageName[originPackageName].orEmpty()
        if (instanceIds.size != 1) return null
        return byInstanceId[instanceIds.first()]
    }

    fun clear() {
        byInstanceId.clear()
        byVirtualPackageName.clear()
        byOriginPackageName.clear()
    }

    companion object {
        val global: VirtualPackageRegistry = VirtualPackageRegistry()
    }
}

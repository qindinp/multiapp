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
    private val byPackageName = ConcurrentHashMap<String, String>()

    fun register(snapshot: VirtualPackageSnapshot): VirtualPackageSnapshot {
        byInstanceId[snapshot.instanceId] = snapshot
        byPackageName[snapshot.originPackageName] = snapshot.instanceId
        byPackageName[snapshot.virtualPackageName] = snapshot.instanceId
        return snapshot
    }

    fun getByInstanceId(instanceId: String): VirtualPackageSnapshot? = byInstanceId[instanceId]

    fun getByPackageName(packageName: String?): VirtualPackageSnapshot? {
        if (packageName.isNullOrBlank()) return null
        return byPackageName[packageName]?.let { byInstanceId[it] }
    }

    fun clear() {
        byInstanceId.clear()
        byPackageName.clear()
    }

    companion object {
        val global: VirtualPackageRegistry = VirtualPackageRegistry()
    }
}

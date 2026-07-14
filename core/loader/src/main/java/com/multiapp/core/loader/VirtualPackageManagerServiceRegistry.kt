package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.concurrent.ConcurrentHashMap

interface VirtualPackageManagerServiceResolver {
    fun serviceForPackage(packageName: String?): VirtualPackageService?
    fun serviceForComponent(component: ComponentName?): VirtualPackageService?
    fun serviceForAuthority(authority: String?): VirtualPackageService?
    fun serviceForIntent(intent: Intent?): VirtualPackageService?
}

object VirtualPackageManagerServiceRegistry : VirtualPackageManagerServiceResolver {
    private val servicesByPackage = ConcurrentHashMap<String, VirtualPackageService>()
    private val servicesByAuthority = ConcurrentHashMap<String, VirtualPackageService>()

    fun register(
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int,
        packageManager: PackageManager? = null
    ): VirtualPackageService {
        val service = VirtualPackageService(
            snapshot = snapshot,
            runtimeUid = runtimeUid,
            packageSigningInfo = VirtualPackageArchiveSigningResolver.resolve(packageManager, snapshot)
        )
        service.packageAliases().forEach { packageName ->
            servicesByPackage[packageName] = service
        }
        snapshot.providers
            .flatMap { it.authorities }
            .filter { it.isNotBlank() }
            .forEach { authority -> registerAuthority(authority, service) }
        return service
    }

    private fun registerAuthority(authority: String, service: VirtualPackageService) {
        servicesByAuthority.putIfAbsent(authority, service)
    }

    override fun serviceForPackage(packageName: String?): VirtualPackageService? =
        packageName?.let { servicesByPackage[it] }

    override fun serviceForComponent(component: ComponentName?): VirtualPackageService? =
        serviceForPackage(component?.packageName)

    override fun serviceForAuthority(authority: String?): VirtualPackageService? =
        authority?.let { servicesByAuthority[it] }

    override fun serviceForIntent(intent: Intent?): VirtualPackageService? {
        if (intent == null) return null
        intent.component?.let { component -> serviceForComponent(component)?.let { return it } }
        intent.safePackageName()?.let { packageName -> serviceForPackage(packageName)?.let { return it } }
        return null
    }
}

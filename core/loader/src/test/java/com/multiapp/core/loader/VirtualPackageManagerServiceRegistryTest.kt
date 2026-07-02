package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualPackageManagerServiceRegistryTest {

    @Test
    fun `register keeps first authority owner when a later snapshot declares same authority`() {
        val authority = "com.test.registry.conflict"
        val first = snapshot(
            instanceId = "registry-first",
            originPackageName = "com.test.registry.first",
            virtualPackageName = "com.multiapp.instance.registry.first",
            authority = authority
        )
        val second = snapshot(
            instanceId = "registry-second",
            originPackageName = "com.test.registry.second",
            virtualPackageName = "com.multiapp.instance.registry.second",
            authority = authority
        )

        VirtualPackageManagerServiceRegistry.register(first)
        VirtualPackageManagerServiceRegistry.register(second)

        assertEquals(
            "com.test.registry.first.Provider",
            VirtualPackageManagerServiceRegistry.serviceForAuthority(authority)
                ?.resolveContentProvider(authority)
                ?.name
        )
        assertEquals(
            "com.test.registry.second",
            VirtualPackageManagerServiceRegistry.serviceForPackage("com.test.registry.second")
                ?.getPackageInfo("com.test.registry.second")
                ?.packageName
        )
    }

    private fun snapshot(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        authority: String
    ) = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        applicationLabel = originPackageName,
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        sourceDir = "/data/apks/$originPackageName.apk",
        dataDir = "/data/instances/$instanceId",
        providers = listOf(
            ResolvedComponent(
                name = "$originPackageName.Provider",
                exported = false,
                authorities = listOf(authority)
            )
        )
    )
}

package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VirtualPackageRegistryTest {

    @Test
    fun `origin package resolves only when a single instance is registered`() {
        val registry = VirtualPackageRegistry()
        registry.register(snapshot(instanceId = "inst-001", virtualPackageName = "com.multiapp.instance.one"))

        assertEquals("inst-001", registry.getByPackageName("com.test.minimal")?.instanceId)
        assertEquals("inst-001", registry.getUniqueByOriginPackageName("com.test.minimal")?.instanceId)
    }

    @Test
    fun `virtual package remains exact when origin package has multiple instances`() {
        val registry = VirtualPackageRegistry()
        registry.register(snapshot(instanceId = "inst-001", virtualPackageName = "com.multiapp.instance.one"))
        registry.register(snapshot(instanceId = "inst-002", virtualPackageName = "com.multiapp.instance.two"))

        assertNull(registry.getByPackageName("com.test.minimal"))
        assertNull(registry.getUniqueByOriginPackageName("com.test.minimal"))
        assertEquals("inst-001", registry.getByPackageName("com.multiapp.instance.one")?.instanceId)
        assertEquals("inst-002", registry.getByPackageName("com.multiapp.instance.two")?.instanceId)
    }

    private fun snapshot(
        instanceId: String,
        virtualPackageName: String,
        originPackageName: String = "com.test.minimal"
    ): VirtualPackageSnapshot = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        applicationLabel = "Minimal",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 23,
        sourceDir = "/tmp/minimal.apk",
        dataDir = "/tmp/$instanceId"
    )
}

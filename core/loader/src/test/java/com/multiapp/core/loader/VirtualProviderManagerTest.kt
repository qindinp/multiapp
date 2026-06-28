package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualProviderManagerTest {

    @Test
    fun `resolve maps guest authority to stable proxy authority`() {
        val manager = VirtualProviderManager("com.multiapp.app")

        val resolution = manager.resolve(snapshot(), "com.test.minimal.probe")

        assertNotNull(resolution)
        assertEquals("inst-001", resolution.instanceId)
        assertEquals("com.test.minimal", resolution.originPackageName)
        assertEquals("com.multiapp.instance.abc", resolution.virtualPackageName)
        assertEquals("com.test.minimal.probe", resolution.guestAuthority)
        assertEquals("com.multiapp.app.multiapp.provider.stub", resolution.proxyAuthority)
        assertEquals("com.test.minimal.ProbeProvider", resolution.providerClassName)
        assertEquals("com.test.minimal.ProbeProvider", resolution.providerInfo.name)
        assertEquals("com.test.minimal.probe", resolution.providerInfo.authority)
    }

    @Test
    fun `resolve produces deterministic proxy authority for same snapshot and authority`() {
        val manager = VirtualProviderManager("com.multiapp.app")

        val first = manager.resolve(snapshot(), "com.test.minimal.probe")
        val second = manager.resolve(snapshot(), "com.test.minimal.probe")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.proxyAuthority, second.proxyAuthority)
    }

    @Test
    fun `authority rewriter maps guest authority to stable proxy authority`() {
        val result = VirtualProviderUriRewriter("com.multiapp.app")
            .rewriteAuthority(snapshot(), "com.test.minimal.probe")

        assertNotNull(result)
        assertEquals("com.test.minimal.probe", result.originalAuthority)
        assertEquals("com.multiapp.app.multiapp.provider.stub", result.proxyAuthority)
        assertEquals("com.test.minimal.ProbeProvider", result.resolution.providerClassName)
    }

    @Test
    fun `authority map factory exports all guest provider authorities`() {
        val map = VirtualProviderAuthorityMapFactory("com.multiapp.app").create(snapshot())

        assertEquals(
            mapOf("com.test.minimal.probe" to "com.multiapp.app.multiapp.provider.stub"),
            map
        )
    }

    @Test
    fun `unknown authority does not resolve or rewrite authority`() {
        val manager = VirtualProviderManager("com.multiapp.app")

        assertNull(manager.resolve(snapshot(), "missing.authority"))
        assertIs<VirtualProviderOpenResult.NotFound>(manager.openProvider(snapshot(), "missing.authority"))
        assertNull(VirtualProviderUriRewriter("com.multiapp.app").rewriteAuthority(snapshot(), "missing.authority"))
    }

    @Test
    fun `provider acquisition evidence records resolved and not found paths`() {
        val manager = VirtualProviderManager("com.multiapp.app")

        val resolved = VirtualProviderEvidence.acquisition(
            manager.openProvider(snapshot(), "com.test.minimal.probe")
        )
        val notFound = VirtualProviderEvidence.acquisition(
            manager.openProvider(snapshot(), "missing.authority")
        )

        assertEquals(VirtualProviderEvidence.Operation.ACQUIRE_PROVIDER, resolved.operation)
        assertEquals("inst-001", resolved.instanceId)
        assertEquals("com.test.minimal.probe", resolved.guestAuthority)
        assertEquals("com.multiapp.app.multiapp.provider.stub", resolved.proxyAuthority)
        assertEquals("com.test.minimal.ProbeProvider", resolved.providerClassName)
        assertEquals(true, resolved.success)
        assertNull(resolved.reason)
        assertEquals("missing.authority", notFound.guestAuthority)
        assertEquals("PROVIDER_NOT_FOUND", notFound.reason)
    }

    @Test
    fun `notifyChange evidence records rewritten and missing authorities`() {
        val rewriter = VirtualProviderUriRewriter("com.multiapp.app")
        val rewrite = rewriter.rewriteAuthority(snapshot(), "com.test.minimal.probe")

        val success = VirtualProviderEvidence.notifyChange(
            rewrite = rewrite?.let {
                VirtualProviderUriRewrite(
                    originalUri = mockk(relaxed = true),
                    rewrittenUri = mockk(relaxed = true),
                    resolution = it.resolution
                )
            },
            originalAuthority = "com.test.minimal.probe"
        )
        val missing = VirtualProviderEvidence.notifyChange(null, "missing.authority")

        assertEquals(VirtualProviderEvidence.Operation.NOTIFY_CHANGE, success.operation)
        assertTrue(success.success)
        assertEquals("com.test.minimal.probe", success.guestAuthority)
        assertEquals("com.multiapp.app.multiapp.provider.stub", success.proxyAuthority)
        assertEquals(false, missing.success)
        assertEquals("PROVIDER_NOT_FOUND", missing.reason)
    }

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = "/data/inst",
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                authorities = listOf("com.test.minimal.probe")
            )
        )
    )
}

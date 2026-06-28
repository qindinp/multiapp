package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualProviderRoutingPlanTest {

    @Test
    fun `create disables routing when guest has no providers`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(providers = emptyList()),
            hostPackageName = "com.multiapp.app"
        )

        assertFalse(plan.enabled)
        assertEquals("NO_GUEST_PROVIDERS", plan.reason)
        assertEquals(ProviderRoutingStrategy.NONE, plan.primaryStrategy)
        assertEquals(0, plan.authorityMap.size)
    }

    @Test
    fun `create uses pass-through hook with acquisition proxy fallback when host is available`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(),
            hostPackageName = "com.multiapp.app"
        )

        assertTrue(plan.enabled)
        assertEquals("AUTHORITY_MAP_READY", plan.reason)
        assertEquals(
            ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK,
            plan.primaryStrategy
        )
        assertEquals(
            ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
            plan.fallbackStrategy
        )
        assertEquals(
            mapOf("com.test.minimal.probe" to "com.multiapp.app.multiapp.provider.stub"),
            plan.authorityMap
        )
    }

    @Test
    fun `create reports host package missing for provider app without host context`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(),
            hostPackageName = null
        )

        assertFalse(plan.enabled)
        assertEquals("HOST_PACKAGE_UNAVAILABLE", plan.reason)
        assertEquals(
            ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
            plan.primaryStrategy
        )
        assertEquals(1, plan.providerCount)
        assertEquals(1, plan.authorityCount)
    }

    @Test
    fun `toEvidence includes provider routing strategy and counts`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(),
            hostPackageName = "com.multiapp.app"
        )
        val evidence = plan.toEvidence().associate { it.key to it.value }

        assertEquals("true", evidence["providerRoutingEnabled"])
        assertEquals("AUTHORITY_MAP_READY", evidence["providerRoutingReason"])
        assertEquals("CONTENT_RESOLVER_PASS_THROUGH_HOOK", evidence["providerRoutingPrimary"])
        assertEquals("ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY", evidence["providerRoutingFallback"])
        assertEquals("1", evidence["providerCount"])
        assertEquals("1", evidence["providerAuthorityCount"])
        assertEquals("1", evidence["providerAuthorityMapSize"])
        assertEquals("com.multiapp.app", evidence["providerHostPackage"])
    }

    private fun snapshot(
        providers: List<ResolvedComponent> = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                authorities = listOf("com.test.minimal.probe")
            )
        )
    ) = VirtualPackageSnapshot(
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
        providers = providers
    )
}

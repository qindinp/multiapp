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
    fun `create uses acquisition proxy as default instance scoped route when host is available`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(),
            hostPackageName = "com.multiapp.app"
        )

        assertTrue(plan.enabled)
        assertEquals("AUTHORITY_MAP_READY", plan.reason)
        assertEquals(
            ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
            plan.primaryStrategy
        )
        assertEquals(
            ProviderRoutingStrategy.NONE,
            plan.fallbackStrategy
        )
        assertEquals(
            mapOf("com.test.minimal.probe" to "com.multiapp.app.multiapp.provider.stub"),
            plan.authorityMap
        )
    }

    @Test
    fun `create can opt into pass-through hook with acquisition proxy fallback`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(),
            hostPackageName = "com.multiapp.app",
            passThroughHookAllowed = true
        )

        assertTrue(plan.enabled)
        assertEquals(
            ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK,
            plan.primaryStrategy
        )
        assertEquals(
            ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
            plan.fallbackStrategy
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
        assertEquals("ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY", evidence["providerRoutingPrimary"])
        assertEquals("NONE", evidence["providerRoutingFallback"])
        assertEquals("1", evidence["providerCount"])
        assertEquals("1", evidence["providerAuthorityCount"])
        assertEquals("1", evidence["providerAuthorityMapSize"])
        assertEquals("com.multiapp.app", evidence["providerHostPackage"])
        assertEquals("INSTANCE", evidence["providerRoutingScope"])
        assertEquals("false", evidence["processWideProviderHook"])
        assertEquals("VirtualContentResolver", evidence["authorityRewriteEntry"])
        assertEquals("1", evidence["providerPolicyPermissionCount"])
        assertEquals("1", evidence["providerPolicyGrantUriPermissionCount"])
        assertEquals("0", evidence["providerPolicyExportedCount"])
        assertEquals("0", evidence["providerPolicyUnguardedExportedCount"])
        assertEquals("INTERNAL_ONLY", evidence["providerPolicyStatuses"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationQueryStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationInsertStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationUpdateStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationDeleteStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationCallStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationBulkInsertStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationOpenFileStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationOpenAssetFileStatus"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationOpenTypedAssetFileStatus"])
        assertEquals("UNSUPPORTED", evidence["providerOperationNotifyChangeStatus"])
        assertEquals("CONTENT_RESOLVER_NOTIFY_CHANGE_NOT_VIRTUALIZED", evidence["providerOperationNotifyChangeReason"])
        assertEquals("UNSUPPORTED", evidence["providerOperationContentObserverStatus"])
        assertEquals("CONTENT_OBSERVER_REGISTRATION_NOT_VIRTUALIZED", evidence["providerOperationContentObserverReason"])
        assertEquals("UNSUPPORTED", evidence["providerOperationGrantUriPermissionStatus"])
        assertEquals("URI_PERMISSION_GRANT_NOT_VIRTUALIZED", evidence["providerOperationGrantUriPermissionReason"])
    }

    @Test
    fun `toEvidence marks routed operations as disabled when provider routing is unavailable`() {
        val plan = VirtualProviderRoutingPlanFactory().create(
            snapshot = snapshot(providers = emptyList()),
            hostPackageName = "com.multiapp.app"
        )
        val evidence = plan.toEvidence().associate { it.key to it.value }

        assertEquals("false", evidence["providerRoutingEnabled"])
        assertEquals("ROUTING_DISABLED", evidence["providerOperationQueryStatus"])
        assertEquals("ROUTING_DISABLED", evidence["providerOperationOpenTypedAssetFileStatus"])
        assertEquals("UNSUPPORTED", evidence["providerOperationNotifyChangeStatus"])
        assertEquals("UNSUPPORTED", evidence["providerOperationContentObserverStatus"])
        assertEquals("UNSUPPORTED", evidence["providerOperationGrantUriPermissionStatus"])
    }

    private fun snapshot(
        providers: List<ResolvedComponent> = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                authorities = listOf("com.test.minimal.probe"),
                permission = "com.test.minimal.permission.PROBE",
                grantUriPermissions = true
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

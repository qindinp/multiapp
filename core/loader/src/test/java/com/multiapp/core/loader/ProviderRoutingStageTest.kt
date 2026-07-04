package com.multiapp.core.loader

import com.multiapp.core.hook.HookEngine
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRoutingStageTest {

    @Test
    fun `execute creates provider routing plan and skipped hook evidence when profile disabled`() {
        val snapshot = snapshotWithProvider()
        val stage = ProviderRoutingStage(
            hostPackageName = "com.multiapp.app",
            providerHookInstallEnabled = false,
            providerHookInstaller = VirtualProviderHookInstaller(),
            clock = fixedClock(100L, 108L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot)
        )

        assertEquals(RuntimeStage.GUEST_CONTEXT, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(8L, output.result.durationMs)
        val plan = assertNotNull(output.context.providerRoutingPlan)
        assertTrue(plan.enabled)
        assertEquals("AUTHORITY_MAP_READY", plan.reason)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["providerRoutingEnabled"])
        assertEquals("AUTHORITY_MAP_READY", evidence["providerRoutingReason"])
        assertEquals("ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY", evidence["providerRoutingPrimary"])
        assertEquals("NONE", evidence["providerRoutingFallback"])
        assertEquals("INSTANCE", evidence["providerRoutingScope"])
        assertEquals("false", evidence["processWideProviderHook"])
        assertEquals("VirtualContentResolver", evidence["authorityRewriteEntry"])
        assertEquals("SKIPPED", evidence["providerHookInstallStatus"])
        assertEquals("PROFILE_DISABLED", evidence["providerHookInstallReason"])
    }

    @Test
    fun `execute installs provider hook when profile enabled`() {
        val snapshot = snapshotWithProvider()
        val hookEngine = mockk<HookEngine>(relaxed = true)
        val stage = ProviderRoutingStage(
            hostPackageName = "com.multiapp.app",
            providerHookInstallEnabled = true,
            providerHookInstaller = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine }),
            clock = fixedClock(200L, 209L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot)
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("INSTALLED", evidence["providerHookInstallStatus"])
        assertEquals("1", evidence["providerHookInstallAuthorityMapSize"])
        assertEquals("CONTENT_RESOLVER_PASS_THROUGH_HOOK", evidence["providerRoutingPrimary"])
        assertEquals("ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY", evidence["providerRoutingFallback"])
        verify(atLeast = 1) {
            hookEngine.hookMethodPassThrough(any(), any(), any())
        }
    }

    @Test
    fun `execute fails terminally when package snapshot is missing`() {
        val stage = ProviderRoutingStage(
            hostPackageName = "com.multiapp.app",
            providerHookInstallEnabled = false,
            providerHookInstaller = VirtualProviderHookInstaller(),
            clock = fixedClock(300L, 304L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.GUEST_CONTEXT, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Package snapshot is required before provider routing", output.result.message)
        assertEquals(4L, output.result.durationMs)
        assertNull(output.context.providerRoutingPlan)
        assertTrue(output.isTerminalFailure)
    }

    private fun snapshotWithProvider() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        applicationLabel = "Example App",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/artifact/com.example.app.apk",
        dataDir = "/data/instances/inst-001",
        providers = listOf(
            ResolvedComponent(
                name = "com.example.app.Provider",
                exported = false,
                authorities = listOf("com.example.app.provider"),
                permission = "com.example.app.permission.PROBE",
                grantUriPermissions = true
            )
        )
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

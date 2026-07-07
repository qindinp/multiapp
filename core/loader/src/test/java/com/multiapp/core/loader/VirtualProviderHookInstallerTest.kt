package com.multiapp.core.loader

import com.multiapp.core.hook.HookEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VirtualProviderHookInstallerTest {

    @Test
    fun `install applies content provider hook for pass-through routing plan`() {
        val hookEngine = mockk<HookEngine>(relaxed = true)
        every { hookEngine.initLsplant(any()) } returns true
        every { hookEngine.hookMethodPassThrough(any(), any(), any()) } returns true
        val installer = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine })

        val result = installer.install(enabledPlan())

        val installed = assertIs<VirtualProviderHookInstallResult.Installed>(result)
        assertEquals(1, installed.authorityMapSize)
        assertTrue(installed.installedMethodCount > 0)
        verify(atLeast = 1) {
            hookEngine.hookMethodPassThrough(any(), any(), any())
        }
    }

    @Test
    fun `install skips disabled plan`() {
        val hookEngine = mockk<HookEngine>(relaxed = true)
        val installer = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine })

        val result = installer.install(enabledPlan().copy(enabled = false, reason = "NO_GUEST_PROVIDERS"))

        val skipped = assertIs<VirtualProviderHookInstallResult.Skipped>(result)
        assertEquals("NO_GUEST_PROVIDERS", skipped.reason)
        verify(exactly = 0) {
            hookEngine.hookMethodPassThrough(any(), any(), any())
        }
    }

    @Test
    fun `install skips acquisition proxy primary plan`() {
        val hookEngine = mockk<HookEngine>(relaxed = true)
        val installer = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine })

        val result = installer.install(
            enabledPlan().copy(
                primaryStrategy = ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
                fallbackStrategy = ProviderRoutingStrategy.NONE
            )
        )

        val skipped = assertIs<VirtualProviderHookInstallResult.Skipped>(result)
        assertEquals(
            "PRIMARY_STRATEGY_NOT_PASS_THROUGH:ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY",
            skipped.reason
        )
        verify(exactly = 0) {
            hookEngine.hookMethodPassThrough(any(), any(), any())
        }
    }

    @Test
    fun `install result evidence records installed status`() {
        val hookEngine = mockk<HookEngine>(relaxed = true)
        every { hookEngine.initLsplant(any()) } returns true
        every { hookEngine.hookMethodPassThrough(any(), any(), any()) } returns true
        val installer = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine })

        val result = installer.install(enabledPlan())
        val evidence = result.toEvidence().associate { it.key to it.value }

        assertEquals("INSTALLED", evidence["providerHookInstallStatus"])
        assertEquals("1", evidence["providerHookInstallAuthorityMapSize"])
        assertTrue((evidence["providerHookInstallMethodCount"]?.toIntOrNull() ?: 0) > 0)
        assertEquals("AUTHORITY_MAP_READY", evidence["providerHookInstallReason"])
    }

    @Test
    fun `install skips when LSPlant pass-through hooks do not install`() {
        val hookEngine = mockk<HookEngine>(relaxed = true)
        every { hookEngine.initLsplant(any()) } returns false
        every { hookEngine.hookMethodPassThrough(any(), any(), any()) } returns false
        val installer = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine })

        val result = installer.install(enabledPlan())

        val skipped = assertIs<VirtualProviderHookInstallResult.Skipped>(result)
        assertTrue(skipped.reason.startsWith("NO_CONTENT_RESOLVER_HOOK_INSTALLED"))
    }

    private fun enabledPlan() = VirtualProviderRoutingPlan(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        hostPackageName = "com.multiapp.app",
        providerCount = 1,
        authorityCount = 1,
        authorityMap = mapOf("com.test.minimal.probe" to "com.multiapp.app.multiapp.provider.stub"),
        primaryStrategy = ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK,
        fallbackStrategy = ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
        enabled = true,
        reason = "AUTHORITY_MAP_READY"
    )
}

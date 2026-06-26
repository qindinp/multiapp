package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedAppBaselineModeTest {

    @Test
    fun `strict protected baseline disables hook runtime`() {
        val mode = ProtectedAppBaselineMode.strict()

        assertTrue(mode.isHookFree())
        assertFalse(mode.allowsHookRuntime())
        assertFalse(mode.lsPlantEnabled)
        assertFalse(mode.xposedModulesEnabled)
        assertFalse(mode.businessNativeStubsEnabled)
        assertFalse(mode.methodReplacementPatchesEnabled)
        assertFalse(mode.noOpPatchesEnabled)
        assertTrue(mode.requiresContainerVirtualization())
    }

    @Test
    fun `diagnostic baseline remains hook-free`() {
        val mode = ProtectedAppBaselineMode.diagnostic()

        assertTrue(mode.nativeDiagnosticsEnabled)
        assertTrue(mode.isHookFree())
        assertFalse(mode.allowsHookRuntime())
    }

    @Test
    fun `baseline native hook policy is not invasive`() {
        val policy = NativeHookPolicy.baseline()

        assertFalse(policy.isInvasive())
        assertTrue(policy.isHookFreeBaselineCompatible())
    }

    @Test
    fun `diagnostic native policy enables logging without business stubs`() {
        val policy = NativeHookPolicy.diagnostic()

        assertTrue(policy.registerNativesLogger)
        assertTrue(policy.findClassLogger)
        assertFalse(policy.businessNativeStubs)
        assertFalse(policy.isInvasive())
        assertFalse(policy.isHookFreeBaselineCompatible())
    }

    @Test
    fun `compatibility native policy is explicitly invasive`() {
        val policy = NativeHookPolicy.compatibility()

        assertTrue(policy.isInvasive())
        assertFalse(policy.businessNativeStubs)
        assertFalse(policy.isHookFreeBaselineCompatible())
    }
}

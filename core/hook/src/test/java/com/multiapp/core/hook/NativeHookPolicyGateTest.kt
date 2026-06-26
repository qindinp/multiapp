package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHookPolicyGateTest {

    @Test
    fun `strict baseline denies every invasive startup capability with evidence`() {
        val policy = NativeHookPolicy.baseline()
        val deniedCapabilities = listOf(
            NativeHookCapability.LSPLANT_METHOD_HOOKS,
            NativeHookCapability.XPOSED_MODULES,
            NativeHookCapability.BUSINESS_NATIVE_STUBS,
            NativeHookCapability.METHOD_REPLACEMENT,
            NativeHookCapability.NO_OP_PATCHES
        )

        deniedCapabilities.forEach { capability ->
            val decision = NativeHookPolicyGate.evaluate(
                policy = policy,
                capability = capability,
                component = "baseline-startup"
            )

            assertFalse(decision.allowed, "strict baseline must deny $capability")
            assertEquals("SKIPPED", decision.status)
            assertEquals(policy.mode.name, decision.evidence["policyMode"])
            assertEquals(capability.name, decision.evidence["capability"])
            assertEquals("baseline-startup", decision.evidence["component"])
            assertTrue(decision.evidence["reason"].orEmpty().contains("disabled by NativeHookPolicy"))
        }
    }

    @Test
    fun `strict baseline exposes startup evidence for disabled hook families`() {
        val evidence = NativeHookPolicyGate.baselineEvidence(NativeHookPolicy.baseline())

        assertEquals("BASELINE", evidence["policyMode"])
        assertEquals("false", evidence["lsPlantEnabled"])
        assertEquals("false", evidence["xposedModulesEnabled"])
        assertEquals("false", evidence["businessNativeStubsEnabled"])
        assertEquals("false", evidence["methodReplacementEnabled"])
        assertEquals("false", evidence["noOpPatchesEnabled"])
        assertEquals("true", evidence["containerIdentityVirtualizationEnabled"])
        assertEquals("true", evidence["packageManagerVirtualizationEnabled"])
        assertEquals("true", evidence["pathVirtualizationEnabled"])
    }

    @Test
    fun `strict baseline loader app-specific helper emits xposed business method and noop skip evidence`() {
        val decisions = NativeHookPolicyGate.decisionsForComponents(
            policy = NativeHookPolicy.baseline(),
            components = mapOf(
                NativeHookCapability.XPOSED_MODULES to "LoaderFactory.loadXposedModules",
                NativeHookCapability.BUSINESS_NATIVE_STUBS to "LoaderFactory.onlineChapterFullFallbackStubs",
                NativeHookCapability.METHOD_REPLACEMENT to "LoaderFactory.installAppSpecificPostLoadHooks.methodReplacement",
                NativeHookCapability.NO_OP_PATCHES to "LoaderFactory.installAppSpecificPostLoadHooks.noOpPatches"
            )
        )
        val nativeLoadDecision = NativeHookPolicyGate.evaluate(
            NativeHookPolicy.baseline(),
            NativeHookCapability.METHOD_REPLACEMENT,
            "LoaderFactory.installNativeLoadHookIfAvailable.nativeLoadHook"
        )
        val registerLoggerDecision = NativeHookPolicyGate.evaluate(
            NativeHookPolicy.baseline(),
            NativeHookCapability.REGISTER_NATIVES_LOGGING,
            "LoaderFactory.installNativeLoadHookIfAvailable.registerNativesLogger"
        )
        val attachedStubFallbackDecision = NativeHookPolicyGate.evaluate(
            NativeHookPolicy.baseline(),
            NativeHookCapability.BUSINESS_NATIVE_STUBS,
            "LoaderFactory.tryRunQqReaderStubAppAttachedLoad.stubFallback"
        )
        val originalInterface11Decision = NativeHookPolicyGate.evaluate(
            NativeHookPolicy.baseline(),
            NativeHookCapability.BUSINESS_NATIVE_STUBS,
            "LoaderFactory.maybeRunQqReaderOriginalInterface11.businessNativeStubs"
        )

        assertEquals(4, decisions.size)
        decisions.forEach { decision ->
            assertFalse(decision.allowed)
            assertEquals("SKIPPED", decision.status)
        }
        assertFalse(nativeLoadDecision.allowed)
        assertEquals("SKIPPED", nativeLoadDecision.status)
        assertEquals(
            "LoaderFactory.installNativeLoadHookIfAvailable.nativeLoadHook",
            nativeLoadDecision.evidence["component"]
        )
        assertFalse(registerLoggerDecision.allowed)
        assertEquals("SKIPPED", registerLoggerDecision.status)
        assertEquals(
            "LoaderFactory.installNativeLoadHookIfAvailable.registerNativesLogger",
            registerLoggerDecision.evidence["component"]
        )
        assertEquals(
            "LoaderFactory.installAppSpecificPostLoadHooks.methodReplacement",
            decisions.first { it.evidence["capability"] == "METHOD_REPLACEMENT" }.evidence["component"]
        )
        assertEquals(
            "LoaderFactory.installAppSpecificPostLoadHooks.noOpPatches",
            decisions.first { it.evidence["capability"] == "NO_OP_PATCHES" }.evidence["component"]
        )
        assertFalse(attachedStubFallbackDecision.allowed)
        assertEquals("SKIPPED", attachedStubFallbackDecision.status)
        assertEquals(
            "LoaderFactory.tryRunQqReaderStubAppAttachedLoad.stubFallback",
            attachedStubFallbackDecision.evidence["component"]
        )
        assertFalse(originalInterface11Decision.allowed)
        assertEquals("SKIPPED", originalInterface11Decision.status)
        assertEquals(
            "LoaderFactory.maybeRunQqReaderOriginalInterface11.businessNativeStubs",
            originalInterface11Decision.evidence["component"]
        )
    }
}

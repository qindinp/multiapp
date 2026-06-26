package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHookPolicyTest {

    @Test
    fun `strict baseline is hook free and only keeps container virtualization`() {
        val policy = NativeHookPolicy.baseline()

        assertTrue(policy.isHookFree())
        assertTrue(policy.isEnabled(NativeHookCapability.CONTAINER_IDENTITY_VIRTUALIZATION))
        assertTrue(policy.isEnabled(NativeHookCapability.PACKAGE_MANAGER_VIRTUALIZATION))
        assertTrue(policy.isEnabled(NativeHookCapability.PATH_VIRTUALIZATION))

        assertFalse(policy.isEnabled(NativeHookCapability.LSPLANT_METHOD_HOOKS))
        assertFalse(policy.isEnabled(NativeHookCapability.XPOSED_MODULES))
        assertFalse(policy.isEnabled(NativeHookCapability.BUSINESS_NATIVE_STUBS))
        assertFalse(policy.isEnabled(NativeHookCapability.METHOD_REPLACEMENT))
        assertFalse(policy.isEnabled(NativeHookCapability.NO_OP_PATCHES))
        assertFalse(policy.isEnabled(NativeHookCapability.DIAGNOSTIC_LOGGING))
        assertFalse(policy.isEnabled(NativeHookCapability.CLASS_LOAD_LOGGING))
        assertFalse(policy.isEnabled(NativeHookCapability.REGISTER_NATIVES_LOGGING))
    }

    @Test
    fun `diagnostic policy enables logging but keeps business stubs disabled`() {
        val policy = NativeHookPolicy.diagnostic()

        assertTrue(policy.isHookFree())
        assertTrue(policy.isEnabled(NativeHookCapability.CONTAINER_IDENTITY_VIRTUALIZATION))
        assertTrue(policy.isEnabled(NativeHookCapability.PACKAGE_MANAGER_VIRTUALIZATION))
        assertTrue(policy.isEnabled(NativeHookCapability.PATH_VIRTUALIZATION))
        assertTrue(policy.isEnabled(NativeHookCapability.DIAGNOSTIC_LOGGING))
        assertTrue(policy.isEnabled(NativeHookCapability.CLASS_LOAD_LOGGING))
        assertTrue(policy.isEnabled(NativeHookCapability.REGISTER_NATIVES_LOGGING))

        assertFalse(policy.isEnabled(NativeHookCapability.LSPLANT_METHOD_HOOKS))
        assertFalse(policy.isEnabled(NativeHookCapability.XPOSED_MODULES))
        assertFalse(policy.isEnabled(NativeHookCapability.BUSINESS_NATIVE_STUBS))
        assertFalse(policy.isEnabled(NativeHookCapability.METHOD_REPLACEMENT))
        assertFalse(policy.isEnabled(NativeHookCapability.NO_OP_PATCHES))
    }

    @Test
    fun `strict baseline rejects hook and diagnostic capabilities`() {
        val forbidden = setOf(
            NativeHookCapability.LSPLANT_METHOD_HOOKS,
            NativeHookCapability.XPOSED_MODULES,
            NativeHookCapability.BUSINESS_NATIVE_STUBS,
            NativeHookCapability.METHOD_REPLACEMENT,
            NativeHookCapability.NO_OP_PATCHES,
            NativeHookCapability.DIAGNOSTIC_LOGGING,
            NativeHookCapability.CLASS_LOAD_LOGGING,
            NativeHookCapability.REGISTER_NATIVES_LOGGING
        )

        forbidden.forEach { capability ->
            assertFailsWith<IllegalArgumentException> {
                NativeHookPolicy.fromCapabilities(
                    NativeHookPolicyMode.BASELINE,
                    setOf(capability)
                )
            }
        }
    }

    @Test
    fun `diagnostic policy rejects business stubs and active hook capabilities`() {
        val forbidden = setOf(
            NativeHookCapability.LSPLANT_METHOD_HOOKS,
            NativeHookCapability.XPOSED_MODULES,
            NativeHookCapability.BUSINESS_NATIVE_STUBS,
            NativeHookCapability.METHOD_REPLACEMENT,
            NativeHookCapability.NO_OP_PATCHES
        )

        forbidden.forEach { capability ->
            assertFailsWith<IllegalArgumentException> {
                NativeHookPolicy.fromCapabilities(
                    NativeHookPolicyMode.DIAGNOSTIC,
                    setOf(capability)
                )
            }
        }
    }
}

package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityProfilePolicyTest {

    @Test
    fun `baseline disables hook and spoof capabilities`() {
        val decision = CompatibilityProfilePolicy().evaluate(
            originPackageName = "com.test.app",
            instanceId = "instance-1",
            profile = EngineProfile.BASELINE
        )

        assertTrue(decision.allowed)
        assertTrue(decision.providerRoutingEnabled)
        assertFalse(decision.lsplantEnabled)
        assertFalse(decision.xposedEnabled)
        assertFalse(decision.procMapsSpoofEnabled)
        assertFalse(decision.signatureFakeEnabled)
        assertFalse(decision.businessNativeWrappersEnabled)
        assertFalse(decision.noOpPatchesEnabled)
    }

    @Test
    fun `compat hook requires package instance profile allow list`() {
        val policy = CompatibilityProfilePolicy(
            allowList = setOf(
                EngineProfileAllowKey("com.test.app", "instance-1", EngineProfile.COMPAT_HOOK)
            )
        )

        assertTrue(policy.evaluate("com.test.app", "instance-1", EngineProfile.COMPAT_HOOK).allowed)
        assertFalse(policy.evaluate("com.test.app", "instance-2", EngineProfile.COMPAT_HOOK).allowed)
    }
}

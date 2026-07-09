package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineHookRuntimeTest {

    @Test
    fun `baseline profile evidence keeps all hook capabilities disabled`() {
        val decision = CompatibilityProfilePolicy().evaluate(
            originPackageName = "com.example.app",
            instanceId = "inst-001",
            profile = EngineProfile.BASELINE
        )

        val evidence = EngineHookRuntimeEvidence.profileEvidence(
            decision = decision,
            hookEngineTouched = false,
            hookCount = "not_touched"
        )

        assertEquals(EngineResultStatus.PASS, evidence.verdict)
        assertEquals("hook-profile", evidence.component)
        assertEquals("profile-gate", evidence.operation)
        assertEquals("BASELINE", evidence.entries["profile"])
        assertEquals("true", evidence.entries["allowed"])
        assertEquals("false", evidence.entries["lsplantEnabled"])
        assertEquals("false", evidence.entries["xposedEnabled"])
        assertEquals("false", evidence.entries["procMapsSpoofEnabled"])
        assertEquals("false", evidence.entries["signatureFakeEnabled"])
        assertEquals("false", evidence.entries["businessNativeWrappersEnabled"])
        assertEquals("false", evidence.entries["noOpPatchesEnabled"])
        assertEquals("core:engine", evidence.entries["hookRuntimeOwner"])
        assertEquals("false", evidence.entries["hookEngineTouched"])
        assertEquals("not_touched", evidence.entries["hookCount"])
    }

    @Test
    fun `compat hook evidence is partial because hook initialization is deferred to runtime`() {
        val decision = CompatibilityProfilePolicy(
            allowList = setOf(
                EngineProfileAllowKey(
                    originPackageName = "com.example.app",
                    instanceId = "inst-001",
                    profile = EngineProfile.COMPAT_HOOK
                )
            )
        ).evaluate(
            originPackageName = "com.example.app",
            instanceId = "inst-001",
            profile = EngineProfile.COMPAT_HOOK
        )

        val evidence = EngineHookRuntimeEvidence.profileEvidence(
            decision = decision,
            hookEngineTouched = true,
            hookCount = "0"
        )

        assertEquals(EngineResultStatus.PARTIAL, evidence.verdict)
        assertEquals("COMPAT_HOOK", evidence.entries["profile"])
        assertEquals("true", evidence.entries["lsplantEnabled"])
        assertEquals("true", evidence.entries["nativeHookEnhancementEnabled"])
        assertEquals("true", evidence.entries["hookEngineTouched"])
        assertEquals("PARTIAL", evidence.entries["hookRuntimeVerdict"])
    }
}

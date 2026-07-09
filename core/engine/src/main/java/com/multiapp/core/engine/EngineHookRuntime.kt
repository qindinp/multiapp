package com.multiapp.core.engine

import com.multiapp.core.hook.HookEngine
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import javax.inject.Inject
import javax.inject.Singleton

interface EngineHookRuntime {
    fun profileEvidence(decision: EngineProfileDecision): EngineOperationEvidence

    companion object {
        val NO_OP: EngineHookRuntime = object : EngineHookRuntime {
            override fun profileEvidence(decision: EngineProfileDecision): EngineOperationEvidence =
                EngineHookRuntimeEvidence.profileEvidence(
                    decision = decision,
                    hookEngineTouched = false,
                    hookCount = "not_touched"
                )
        }
    }
}

@Singleton
class DefaultEngineHookRuntime @Inject constructor() : EngineHookRuntime {
    override fun profileEvidence(decision: EngineProfileDecision): EngineOperationEvidence {
        val shouldTouchHookEngine =
            decision.lsplantEnabled || decision.xposedEnabled || decision.nativeHookEnhancementEnabled
        val hookCount = if (shouldTouchHookEngine) {
            runCatching { HookEngine.getInstance().getHookCount().toString() }
                .getOrElse { error -> "unavailable:${error.javaClass.simpleName}" }
        } else {
            "not_touched"
        }
        return EngineHookRuntimeEvidence.profileEvidence(
            decision = decision,
            hookEngineTouched = shouldTouchHookEngine,
            hookCount = hookCount
        )
    }
}

object EngineHookRuntimeEvidence {
    fun profileEvidence(
        decision: EngineProfileDecision,
        hookEngineTouched: Boolean,
        hookCount: String
    ): EngineOperationEvidence {
        val verdict = when {
            !decision.allowed -> EngineResultStatus.UNSUPPORTED
            decision.lsplantEnabled || decision.xposedEnabled || decision.nativeHookEnhancementEnabled ->
                EngineResultStatus.PARTIAL
            else -> EngineResultStatus.PASS
        }
        return EngineOperationEvidence(
            component = "hook-profile",
            operation = "profile-gate",
            verdict = verdict,
            entries = linkedMapOf(
                "profile" to decision.profile.name,
                "allowed" to decision.allowed.toString(),
                "reason" to decision.reason,
                "providerRoutingEnabled" to decision.providerRoutingEnabled.toString(),
                "lsplantEnabled" to decision.lsplantEnabled.toString(),
                "xposedEnabled" to decision.xposedEnabled.toString(),
                "procMapsSpoofEnabled" to decision.procMapsSpoofEnabled.toString(),
                "signatureFakeEnabled" to decision.signatureFakeEnabled.toString(),
                "businessNativeWrappersEnabled" to decision.businessNativeWrappersEnabled.toString(),
                "noOpPatchesEnabled" to decision.noOpPatchesEnabled.toString(),
                "nativeHookEnhancementEnabled" to decision.nativeHookEnhancementEnabled.toString(),
                "diagnosticsObserveOnlyEnabled" to decision.diagnosticsObserveOnlyEnabled.toString(),
                "hookRuntimeOwner" to "core:engine",
                "hookEngineTouched" to hookEngineTouched.toString(),
                "hookCount" to hookCount,
                "hookRuntimeVerdict" to verdict.name
            )
        )
    }
}

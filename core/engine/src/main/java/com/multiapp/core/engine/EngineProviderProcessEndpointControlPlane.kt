package com.multiapp.core.engine

import android.os.IBinder

data class EngineProviderProcessEndpointAuthorityDecision(
    val allowed: Boolean,
    val expectedIdentity: EngineProviderProcessEndpointIdentity?,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(allowed == (expectedIdentity != null)) {
            "allowed authority must carry exactly one expected identity"
        }
    }
}

fun interface EngineProviderProcessEndpointRuntimeAuthority {
    /** Returns the exact engine-owned endpoint identity expected for the current generation. */
    fun authorize(
        candidate: EngineProviderProcessEndpointIdentity
    ): EngineProviderProcessEndpointAuthorityDecision
}

/**
 * Validates process callers before delegating liveness and generation ownership
 * to [EngineProviderProcessEndpointRegistry]. This is intentionally not wired
 * into Provider dispatch yet, so custom-process Provider remains unsupported.
 */
class EngineProviderProcessEndpointControlPlane(
    private val runtimeAuthority: EngineProviderProcessEndpointRuntimeAuthority,
    private val registry: EngineProviderProcessEndpointRegistry =
        EngineProviderProcessEndpointRegistry(),
    private val onEndpointDeath: (EngineProviderProcessEndpointIdentity) -> Unit = {}
) {
    fun register(
        identity: EngineProviderProcessEndpointIdentity,
        endpointBinder: IBinder,
        callingPid: Int,
        callingProcessName: String?
    ): EngineProviderProcessEndpointRegistrationResult {
        validateCaller(identity, callingPid, callingProcessName)?.let { reason ->
            return registrationRejected(identity, reason)
        }
        validateAuthority(identity)?.let { reason ->
            return registrationRejected(identity, reason)
        }
        return registry.register(identity, endpointBinder, onEndpointDeath)
    }

    fun queryAuthoritative(
        identity: EngineProviderProcessEndpointIdentity
    ): EngineProviderProcessEndpointQueryResult {
        validateAuthority(identity)?.let { reason ->
            return EngineProviderProcessEndpointQueryResult(
                found = false,
                identity = identity,
                endpointBinder = null,
                reason = reason
            )
        }
        return registry.query(identity)
    }

    fun unregister(
        identity: EngineProviderProcessEndpointIdentity,
        endpointBinder: IBinder,
        callingPid: Int,
        callingProcessName: String?
    ): EngineProviderProcessEndpointRemovalResult {
        validateCaller(identity, callingPid, callingProcessName)?.let { reason ->
            return EngineProviderProcessEndpointRemovalResult(
                removed = false,
                identity = identity,
                reason = reason
            )
        }
        return registry.unregister(identity, endpointBinder)
    }

    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Int = registry.revokeGeneration(instanceId, runtimeEpoch, engineSessionId)

    fun handleBinderDeath(endpointBinder: IBinder): Int = registry.handleBinderDeath(endpointBinder)

    fun revokeInstance(instanceId: String): Int = registry.revokeInstance(instanceId)

    internal fun activeEndpointCount(): Int = registry.activeCount()

    private fun validateCaller(
        identity: EngineProviderProcessEndpointIdentity,
        callingPid: Int,
        callingProcessName: String?
    ): String? = when {
        callingPid <= 0 || callingPid != identity.processId -> "endpoint_calling_pid_mismatch"
        callingProcessName != identity.processSlot -> "endpoint_calling_process_slot_mismatch"
        else -> null
    }

    private fun validateAuthority(identity: EngineProviderProcessEndpointIdentity): String? {
        val decision = runCatching { runtimeAuthority.authorize(identity) }
            .getOrElse { return "endpoint_runtime_authority_unavailable" }
        if (!decision.allowed) return decision.reason
        if (decision.expectedIdentity != identity) {
            return "endpoint_runtime_authority_identity_mismatch"
        }
        return null
    }

    private fun registrationRejected(
        identity: EngineProviderProcessEndpointIdentity,
        reason: String
    ) = EngineProviderProcessEndpointRegistrationResult(
        accepted = false,
        idempotent = false,
        replacedGeneration = false,
        identity = identity,
        reason = reason
    )
}

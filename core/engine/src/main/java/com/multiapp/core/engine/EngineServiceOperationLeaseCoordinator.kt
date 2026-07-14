package com.multiapp.core.engine

import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState

/**
 * Adds a live authoritative-runtime check around lease transitions. Lifecycle owners still call
 * revokeGeneration/revokeInstance so records are removed eagerly on process death or replacement.
 */
class EngineServiceOperationLeaseCoordinator(
    private val authoritativeRuntime: (String) -> VirtualInstanceRuntime?,
    private val leases: EngineServiceOperationLeaseRegistry = EngineServiceOperationLeaseRegistry()
) {
    constructor(
        runtimeRegistry: EngineRuntimeRegistry,
        leases: EngineServiceOperationLeaseRegistry = EngineServiceOperationLeaseRegistry()
    ) : this(runtimeRegistry::get, leases)

    fun issue(
        runtime: VirtualInstanceRuntime,
        callingPid: Int,
        operation: VirtualServiceOperation,
        component: String,
        processSlot: String
    ): EngineServiceOperationLeaseIdentity {
        val current = authoritativeRuntime(runtime.instanceId)
            ?: error("cannot issue a Service operation lease without an authoritative runtime")
        check(current.hasSameLeaseRuntimeIdentity(runtime)) {
            "cannot issue a Service operation lease from a stale runtime snapshot"
        }
        return leases.issue(
            authoritativeRuntime = current,
            callingPid = callingPid,
            operation = operation,
            component = component,
            processSlot = processSlot
        )
    }

    fun authorize(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): EngineServiceOperationLeaseDecision = transitionWithCurrentRuntime(
        identity = identity,
        callingPid = callingPid,
        transition = leases::authorize
    )

    fun commit(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): EngineServiceOperationLeaseDecision = transitionWithCurrentRuntime(
        identity = identity,
        callingPid = callingPid,
        transition = leases::commit
    )

    fun commit(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int,
        commitAction: () -> Boolean
    ): EngineServiceOperationLeaseDecision = transitionWithCurrentRuntime(
        identity = identity,
        callingPid = callingPid,
        transition = { currentIdentity, currentPid ->
            leases.commit(currentIdentity, currentPid, commitAction)
        }
    )

    fun abort(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): EngineServiceOperationLeaseDecision = transitionWithCurrentRuntime(
        identity = identity,
        callingPid = callingPid,
        transition = leases::abort
    )

    fun revokeGeneration(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Int =
        leases.revokeGeneration(instanceId, runtimeEpoch, engineSessionId)

    fun revokeInstance(instanceId: String): Int = leases.revokeInstance(instanceId)

    private fun transitionWithCurrentRuntime(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int,
        transition: (EngineServiceOperationLeaseIdentity, Int) -> EngineServiceOperationLeaseDecision
    ): EngineServiceOperationLeaseDecision {
        if (callingPid <= 0 || callingPid != identity.processId) {
            return EngineServiceOperationLeaseDecision(
                accepted = false,
                idempotent = false,
                state = EngineServiceOperationLeaseState.REJECTED,
                reason = "service_operation_lease_process_id_mismatch"
            )
        }
        val current = authoritativeRuntime(identity.instanceId)
        if (current == null || !current.matches(identity) || current.state !in LIVE_RUNTIME_STATES) {
            leases.revokeGeneration(
                instanceId = identity.instanceId,
                runtimeEpoch = identity.runtimeEpoch,
                engineSessionId = identity.engineSessionId
            )
            return EngineServiceOperationLeaseDecision(
                accepted = false,
                idempotent = false,
                state = EngineServiceOperationLeaseState.REVOKED,
                reason = when {
                    current == null -> "service_operation_lease_runtime_not_found"
                    current.state !in LIVE_RUNTIME_STATES -> "service_operation_lease_runtime_not_live"
                    else -> "service_operation_lease_runtime_generation_mismatch"
                }
            )
        }
        return transition(identity, callingPid)
    }

    private fun VirtualInstanceRuntime.hasSameLeaseRuntimeIdentity(other: VirtualInstanceRuntime): Boolean =
        instanceId == other.instanceId &&
            runtimeEpoch == other.runtimeEpoch &&
            engineSessionId == other.engineSessionId &&
            processSlot == other.processSlot &&
            processId == other.processId &&
            processName == other.processName

    private fun VirtualInstanceRuntime.matches(identity: EngineServiceOperationLeaseIdentity): Boolean =
        instanceId == identity.instanceId &&
            runtimeEpoch == identity.runtimeEpoch &&
            engineSessionId == identity.engineSessionId &&
            processSlot == identity.processSlot &&
            processId == identity.processId &&
            (processName == null || processName == identity.processSlot)

    private companion object {
        val LIVE_RUNTIME_STATES = setOf(
            VirtualRuntimeState.CREATED,
            VirtualRuntimeState.PREWARMED,
            VirtualRuntimeState.RUNNING
        )
    }
}

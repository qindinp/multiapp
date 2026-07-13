package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualActivityState
import java.util.UUID

data class EngineProcessClientIdentity(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processId > 0) { "processId must be positive" }
    }
}

enum class EngineProcessAttachOperation {
    ATTACH_CLIENT,
    PROCESS_RESTARTED
}

data class EngineProcessClientAttachResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val liveAuthority: Boolean,
    val operation: EngineProcessAttachOperation,
    val identity: EngineProcessClientIdentity?,
    val runtimeState: VirtualRuntimeState?,
    val reason: String,
    val restoreCapabilityStatus: EngineRecentsRestoreCapabilityStatus =
        EngineRecentsRestoreCapabilityStatus.NOT_REQUESTED
)

enum class EngineRecentsRestoreCapabilityStatus {
    NOT_REQUESTED,
    RESTORE_RECORD_SELECTION_REQUIRED,
    ISSUED,
    REJECTED
}

data class EngineRecentsRestoreCapabilityResult(
    val accepted: Boolean,
    val status: EngineRecentsRestoreCapabilityStatus,
    val identity: EngineActivityLaunchIdentity?,
    val restoreActivityId: String?,
    val reusedPersistedSystemActivityToken: Boolean,
    val reason: String
)

data class EngineProcessAuthorityDecision(
    val allowed: Boolean,
    val identity: EngineProcessClientIdentity?,
    val reason: String
)

data class EngineProcessPrewarmResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val runtimeState: VirtualRuntimeState?,
    val reason: String
)

/**
 * Owns the ephemeral guest-process authority. Durable runtime state is only an
 * identity expectation; it never proves that a guest process is alive.
 */
class EngineProcessControlPlane(
    private val runtimeRegistry: EngineRuntimeRegistry,
    private val deathRegistry: EngineProcessDeathRegistry = EngineProcessDeathRegistry(),
    private val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
        EngineActivityLaunchCapabilityRegistry.global,
    private val engineSessionFactory: () -> String = { UUID.randomUUID().toString() },
    private val evidenceSessionFactory: () -> String = { UUID.randomUUID().toString() }
) {
    fun attachClient(
        identity: EngineProcessClientIdentity,
        clientToken: IBinder,
        callingPid: Int,
        callingProcessName: String? = identity.processSlot
    ): EngineProcessClientAttachResult = registerClient(
        operation = EngineProcessAttachOperation.ATTACH_CLIENT,
        identity = identity,
        clientToken = clientToken,
        callingPid = callingPid,
        callingProcessName = callingProcessName
    )

    fun processRestarted(
        identity: EngineProcessClientIdentity,
        clientToken: IBinder,
        callingPid: Int,
        callingProcessName: String? = identity.processSlot
    ): EngineProcessClientAttachResult {
        val operation = EngineProcessAttachOperation.PROCESS_RESTARTED
        if (callingPid <= 0 || callingPid != identity.processId) {
            return rejected(operation, identity, "calling_pid_mismatch")
        }
        if (callingProcessName != identity.processSlot) {
            return rejected(operation, identity, "calling_process_slot_mismatch")
        }
        val current = runtimeRegistry.get(identity.instanceId)
            ?: return rejected(operation, identity, "runtime_not_found")
        if (
            current.runtimeEpoch != identity.runtimeEpoch ||
            current.engineSessionId != identity.engineSessionId
        ) {
            return rejected(operation, identity, "runtime_generation_mismatch", current.state)
        }
        if (current.processSlot != identity.processSlot) {
            return rejected(operation, identity, "process_slot_mismatch", current.state)
        }
        if (current.state != VirtualRuntimeState.DEAD) {
            return rejected(operation, identity, "runtime_not_restartable:${current.state.name}", current.state)
        }
        if (current.runtimeEpoch == Long.MAX_VALUE) {
            return rejected(operation, identity, "runtime_epoch_exhausted", current.state)
        }

        val successorIdentity = identity.copy(
            runtimeEpoch = current.runtimeEpoch + 1L,
            engineSessionId = engineSessionFactory().takeIf { it.isNotBlank() }
                ?: return rejected(operation, identity, "successor_engine_session_blank", current.state)
        )
        val successorEvidenceSessionId = evidenceSessionFactory().takeIf { it.isNotBlank() }
            ?: return rejected(operation, identity, "successor_evidence_session_blank", current.state)
        val deathRegistration = deathRegistry.register(successorIdentity, clientToken) {
            onClientDeath(successorIdentity)
        }
        if (!deathRegistration.accepted) {
            return rejected(operation, identity, deathRegistration.reason, current.state)
        }
        val restart = runtimeRegistry.restartDeadProcessIfCurrent(
            expectedInstanceId = identity.instanceId,
            expectedRuntimeEpoch = identity.runtimeEpoch,
            expectedEngineSessionId = identity.engineSessionId,
            expectedProcessSlot = identity.processSlot,
            successorEngineSessionId = successorIdentity.engineSessionId,
            successorEvidenceSessionId = successorEvidenceSessionId
        )
        if (!restart.accepted) {
            deathRegistry.rollback(successorIdentity, clientToken)
            return rejected(operation, identity, restart.reason, restart.runtime?.state)
        }
        val binding = runtimeRegistry.bindProcessClientIfCurrent(successorIdentity)
        if (!binding.accepted) {
            deathRegistry.rollback(successorIdentity, clientToken)
            return rejected(operation, successorIdentity, binding.reason, binding.state)
        }
        val authority = authorize(successorIdentity.instanceId, successorIdentity.processId)
        if (!authority.allowed || authority.identity != successorIdentity) {
            deathRegistry.rollback(successorIdentity, clientToken)
            runtimeRegistry.markDeadIfCurrent(successorIdentity)
            return rejected(operation, successorIdentity, authority.reason, binding.state)
        }
        recordEvidence(
            operation = operation,
            identity = successorIdentity,
            accepted = true,
            idempotent = false,
            reason = "restart_generation_allocated_and_client_attached"
        )
        return accepted(operation, successorIdentity, binding.state, idempotent = false)
    }

    fun authorize(instanceId: String, callingPid: Int): EngineProcessAuthorityDecision {
        if (instanceId.isBlank() || callingPid <= 0) {
            return EngineProcessAuthorityDecision(false, null, "invalid_process_authority_request")
        }
        val runtime = runtimeRegistry.get(instanceId)
            ?: return EngineProcessAuthorityDecision(false, null, "runtime_not_found")
        val runtimeProcessId = runtime.processId
            ?: return EngineProcessAuthorityDecision(false, null, "runtime_process_not_bound")
        if (runtimeProcessId != callingPid) {
            return EngineProcessAuthorityDecision(false, null, "process_id_mismatch")
        }
        if (runtime.processName != runtime.processSlot) {
            return EngineProcessAuthorityDecision(false, null, "process_slot_mismatch")
        }
        if (runtime.state !in LIVE_RUNTIME_STATES) {
            return EngineProcessAuthorityDecision(
                false,
                null,
                "runtime_state_${runtime.state.name.lowercase()}"
            )
        }
        val identity = EngineProcessClientIdentity(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            processId = runtimeProcessId
        )
        if (!deathRegistry.isAuthoritative(identity)) {
            return EngineProcessAuthorityDecision(false, identity, "live_client_authority_missing")
        }
        return EngineProcessAuthorityDecision(true, identity, "live_client_authority_confirmed")
    }

    fun markPrewarmed(
        identity: EngineProcessClientIdentity,
        callingPid: Int,
        callingProcessName: String? = identity.processSlot
    ): EngineProcessPrewarmResult {
        if (callingPid <= 0 || callingPid != identity.processId) {
            return prewarmRejected(null, "calling_pid_mismatch")
        }
        if (callingProcessName != identity.processSlot) {
            return prewarmRejected(null, "calling_process_slot_mismatch")
        }
        val authority = authorize(identity.instanceId, callingPid)
        if (!authority.allowed || authority.identity != identity) {
            return prewarmRejected(
                runtimeRegistry.get(identity.instanceId)?.state,
                authority.reason
            )
        }
        val current = runtimeRegistry.get(identity.instanceId)
            ?: return prewarmRejected(null, "runtime_not_found")
        if (current.state == VirtualRuntimeState.PREWARMED || current.state == VirtualRuntimeState.RUNNING) {
            return EngineProcessPrewarmResult(
                accepted = true,
                idempotent = true,
                runtimeState = current.state,
                reason = "runtime_already_${current.state.name.lowercase()}"
            )
        }
        if (current.state != VirtualRuntimeState.CREATED) {
            return prewarmRejected(current.state, "runtime_not_prewarmable:${current.state.name}")
        }
        val updated = runtimeRegistry.markPrewarmedIfCurrent(
            instanceId = identity.instanceId,
            runtimeEpoch = identity.runtimeEpoch,
            engineSessionId = identity.engineSessionId,
            processId = identity.processId,
            processName = identity.processSlot
        ) ?: return prewarmRejected(
            runtimeRegistry.get(identity.instanceId)?.state,
            "runtime_changed_during_prewarm"
        )
        runtimeRegistry.registerOperationEvidence(
            identity.instanceId,
            EngineOperationEvidence(
                component = "runtime",
                operation = "process-prewarmed",
                verdict = EngineResultStatus.PASS,
                entries = identity.toEvidenceEntries() + mapOf(
                    "accepted" to "true",
                    "idempotent" to "false",
                    "reason" to "guest_application_bound_and_runtime_prewarmed"
                )
            )
        )
        return EngineProcessPrewarmResult(
            accepted = true,
            idempotent = false,
            runtimeState = updated.state,
            reason = "guest_application_bound_and_runtime_prewarmed"
        )
    }

    fun remove(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Boolean =
        deathRegistry.remove(instanceId, runtimeEpoch, engineSessionId)

    private fun registerClient(
        operation: EngineProcessAttachOperation,
        identity: EngineProcessClientIdentity,
        clientToken: IBinder,
        callingPid: Int,
        callingProcessName: String?
    ): EngineProcessClientAttachResult {
        if (callingPid <= 0 || callingPid != identity.processId) {
            return rejected(operation, identity, "calling_pid_mismatch")
        }
        if (callingProcessName != identity.processSlot) {
            return rejected(operation, identity, "calling_process_slot_mismatch")
        }
        val runtime = runtimeRegistry.get(identity.instanceId)
            ?: return rejected(operation, identity, "runtime_not_found")
        val identityMismatch = runtime.runtimeEpoch != identity.runtimeEpoch ||
            runtime.engineSessionId != identity.engineSessionId
        if (identityMismatch) {
            return rejected(operation, identity, "runtime_generation_mismatch", runtime.state)
        }
        if (runtime.processSlot != identity.processSlot) {
            return rejected(operation, identity, "process_slot_mismatch", runtime.state)
        }
        if (runtime.processId != null && runtime.processId != identity.processId) {
            return rejected(operation, identity, "process_id_mismatch", runtime.state)
        }
        if (runtime.processName != null && runtime.processName != identity.processSlot) {
            return rejected(operation, identity, "process_name_mismatch", runtime.state)
        }

        val alreadyAuthoritative = deathRegistry.isAuthoritative(identity, clientToken)
        if (alreadyAuthoritative) {
            return accepted(operation, identity, runtime.state, idempotent = true)
        }
        // A missing live authority may only be established while the engine-owned
        // generation is still CREATED. PREWARMED/RUNNING records cannot reconnect
        // after an engine-server restart by replaying durable state.
        if (runtime.state != VirtualRuntimeState.CREATED) {
            return rejected(
                operation,
                identity,
                "runtime_not_attachable:${runtime.state.name}",
                runtime.state
            )
        }

        val deathRegistration = deathRegistry.register(identity, clientToken) {
            onClientDeath(identity)
        }
        if (!deathRegistration.accepted) {
            return rejected(operation, identity, deathRegistration.reason, runtime.state)
        }
        val binding = runtimeRegistry.bindProcessClientIfCurrent(identity)
        if (!binding.accepted) {
            deathRegistry.rollback(identity, clientToken)
            return rejected(operation, identity, binding.reason, binding.state)
        }
        val authority = authorize(identity.instanceId, identity.processId)
        if (!authority.allowed) {
            deathRegistry.rollback(identity, clientToken)
            return rejected(operation, identity, authority.reason, binding.state)
        }
        recordEvidence(
            operation = operation,
            identity = identity,
            accepted = true,
            idempotent = deathRegistration.idempotent,
            reason = deathRegistration.reason
        )
        return accepted(
            operation = operation,
            identity = identity,
            state = binding.state,
            idempotent = deathRegistration.idempotent
        )
    }

    private fun onClientDeath(identity: EngineProcessClientIdentity) {
        val markedDead = runtimeRegistry.markDeadIfCurrent(identity)
        if (!markedDead) return
        activityLaunchCapabilities.revokeGeneration(
            instanceId = identity.instanceId,
            runtimeEpoch = identity.runtimeEpoch,
            engineSessionId = identity.engineSessionId
        )
        runtimeRegistry.registerOperationEvidence(
            identity.instanceId,
            EngineOperationEvidence(
                component = "runtime",
                operation = "process-client-death",
                verdict = EngineResultStatus.FAIL,
                entries = identity.toEvidenceEntries() + mapOf(
                    "reason" to "attached_client_binder_died"
                )
            )
        )
    }

    private fun accepted(
        operation: EngineProcessAttachOperation,
        identity: EngineProcessClientIdentity,
        state: VirtualRuntimeState?,
        idempotent: Boolean
    ) = EngineProcessClientAttachResult(
        accepted = true,
        idempotent = idempotent,
        liveAuthority = true,
        operation = operation,
        identity = identity,
        runtimeState = state,
        reason = if (idempotent) "client_already_attached" else "client_attached",
        restoreCapabilityStatus = if (operation == EngineProcessAttachOperation.PROCESS_RESTARTED) {
            EngineRecentsRestoreCapabilityStatus.RESTORE_RECORD_SELECTION_REQUIRED
        } else {
            EngineRecentsRestoreCapabilityStatus.NOT_REQUESTED
        }
    )

    private fun rejected(
        operation: EngineProcessAttachOperation,
        identity: EngineProcessClientIdentity?,
        reason: String,
        state: VirtualRuntimeState? = null
    ): EngineProcessClientAttachResult {
        identity?.let {
            recordEvidence(operation, it, accepted = false, idempotent = false, reason = reason)
        }
        return EngineProcessClientAttachResult(
            accepted = false,
            idempotent = false,
            liveAuthority = false,
            operation = operation,
            identity = identity,
            runtimeState = state,
            reason = reason,
            restoreCapabilityStatus = if (operation == EngineProcessAttachOperation.PROCESS_RESTARTED) {
                EngineRecentsRestoreCapabilityStatus.REJECTED
            } else {
                EngineRecentsRestoreCapabilityStatus.NOT_REQUESTED
            }
        )
    }

    private fun recordEvidence(
        operation: EngineProcessAttachOperation,
        identity: EngineProcessClientIdentity,
        accepted: Boolean,
        idempotent: Boolean,
        reason: String
    ) {
        runtimeRegistry.registerOperationEvidence(
            identity.instanceId,
            EngineOperationEvidence(
                component = "runtime",
                operation = operation.name.lowercase().replace('_', '-'),
                verdict = if (accepted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
                entries = identity.toEvidenceEntries() + mapOf(
                    "accepted" to accepted.toString(),
                    "idempotent" to idempotent.toString(),
                    "liveBinderAuthority" to accepted.toString(),
                    "reason" to reason
                )
            )
        )
    }

    private fun EngineProcessClientIdentity.toEvidenceEntries(): Map<String, String> = mapOf(
        "instanceId" to instanceId,
        "runtimeEpoch" to runtimeEpoch.toString(),
        "engineSessionId" to engineSessionId,
        "processSlot" to processSlot,
        "processId" to processId.toString()
    )

    private fun prewarmRejected(
        state: VirtualRuntimeState?,
        reason: String
    ) = EngineProcessPrewarmResult(
        accepted = false,
        idempotent = false,
        runtimeState = state,
        reason = reason
    )

    private companion object {
        val LIVE_RUNTIME_STATES = setOf(
            VirtualRuntimeState.CREATED,
            VirtualRuntimeState.PREWARMED,
            VirtualRuntimeState.RUNNING
        )
    }
}

class EngineRecentsRestoreCapabilityIssuer(
    private val runtimeRegistry: EngineRuntimeRegistry,
    private val processControlPlane: EngineProcessControlPlane,
    private val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry,
    private val taskStateProvider: (String) -> VirtualActivityTaskState
) {
    fun issue(
        identity: EngineProcessClientIdentity,
        restoreActivityId: String,
        callingPid: Int
    ): EngineRecentsRestoreCapabilityResult {
        if (restoreActivityId.isBlank()) return rejected("restore_activity_id_blank")
        if (callingPid != identity.processId) return rejected("calling_pid_mismatch")
        val authority = processControlPlane.authorize(identity.instanceId, callingPid)
        if (!authority.allowed || authority.identity != identity) {
            return rejected(authority.reason)
        }
        val runtime = runtimeRegistry.get(identity.instanceId)
            ?: return rejected("runtime_not_found")
        val restoreRecord = taskStateProvider(identity.instanceId)
            .tasks
            .asSequence()
            .flatMap { it.activities.asSequence() }
            .singleOrNull { record ->
                record.instanceId == identity.instanceId && record.activityId == restoreActivityId
            }
            ?: return rejected("authoritative_restore_record_not_found")
        if (restoreRecord.state in TERMINAL_ACTIVITY_STATES) {
            return rejected("restore_record_terminal:${restoreRecord.state.name}")
        }
        val restoreProcessSlot = EngineProxyActivitySlots.processSlotForClassName(
            hostPackageName = runtime.hostPackageName,
            className = restoreRecord.proxyActivityClassName
        )
        if (restoreProcessSlot != runtime.processSlot) {
            return rejected("restore_record_process_slot_mismatch")
        }
        val guestActivityKnown = runtime.packageSnapshot.activities.any { component ->
            component.name == restoreRecord.guestActivityClassName ||
                component.targetActivityName == restoreRecord.guestActivityClassName
        }
        if (!guestActivityKnown) return rejected("restore_record_guest_activity_not_found")

        // The persisted record token belongs to the old framework launch. Only
        // proxy/guest identity is reused; issue() always allocates a fresh token.
        val launchIdentity = runCatching {
            activityLaunchCapabilities.issue(
                runtime = runtime,
                processId = identity.processId,
                proxyActivityClassName = restoreRecord.proxyActivityClassName,
                guestActivityClassName = restoreRecord.guestActivityClassName
            )
        }.getOrElse { error ->
            return rejected("restore_capability_issue_failed:${error.javaClass.simpleName}")
        }
        runtimeRegistry.registerOperationEvidence(
            identity.instanceId,
            EngineOperationEvidence(
                component = "activity-foreground",
                operation = "recents-restore-capability",
                verdict = EngineResultStatus.PASS,
                entries = mapOf(
                    "restoreActivityId" to restoreActivityId,
                    "runtimeEpoch" to identity.runtimeEpoch.toString(),
                    "processId" to identity.processId.toString(),
                    "proxyActivityClassName" to restoreRecord.proxyActivityClassName,
                    "guestActivityClassName" to restoreRecord.guestActivityClassName,
                    "persistedSystemActivityTokenReused" to "false",
                    "capabilityGeneration" to "fresh"
                )
            )
        )
        return EngineRecentsRestoreCapabilityResult(
            accepted = true,
            status = EngineRecentsRestoreCapabilityStatus.ISSUED,
            identity = launchIdentity,
            restoreActivityId = restoreActivityId,
            reusedPersistedSystemActivityToken = false,
            reason = "fresh_restore_capability_issued"
        )
    }

    private fun rejected(reason: String) = EngineRecentsRestoreCapabilityResult(
        accepted = false,
        status = EngineRecentsRestoreCapabilityStatus.REJECTED,
        identity = null,
        restoreActivityId = null,
        reusedPersistedSystemActivityToken = false,
        reason = reason
    )

    private companion object {
        val TERMINAL_ACTIVITY_STATES = setOf(
            VirtualActivityState.FINISHED,
            VirtualActivityState.DESTROYED
        )
    }
}

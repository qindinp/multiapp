package com.multiapp.core.engine

import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Primitive-only identity that can be encoded into a Bundle and echoed by a guest process.
 * The engine always compares the complete identity with its server-side record.
 */
data class EngineServiceOperationLeaseIdentity(
    val leaseToken: String,
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int,
    val operation: String,
    val component: String,
    val issuedAtNanos: Long,
    val expiresAtNanos: Long
) {
    init {
        validateLeaseText("leaseToken", leaseToken)
        validateLeaseText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateLeaseText("engineSessionId", engineSessionId)
        validateLeaseText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        require(runCatching { VirtualServiceOperation.valueOf(operation) }.isSuccess) {
            "operation must name a VirtualServiceOperation"
        }
        validateLeaseText("component", component)
        require(expiresAtNanos != issuedAtNanos) { "lease expiry must differ from issue time" }
    }

    val operationType: VirtualServiceOperation
        get() = VirtualServiceOperation.valueOf(operation)
}

enum class EngineServiceOperationLeaseState {
    ISSUED,
    AUTHORIZED,
    COMMITTED,
    ABORTED,
    EXPIRED,
    REVOKED,
    REJECTED
}

data class EngineServiceOperationLeaseDecision(
    val accepted: Boolean,
    val idempotent: Boolean,
    val state: EngineServiceOperationLeaseState,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

class EngineServiceOperationLeaseRegistry(
    private val clockNanos: () -> Long = System::nanoTime,
    private val tokenFactory: () -> String = ::secureServiceLeaseToken,
    private val ttlNanos: Long = DEFAULT_TTL_NANOS
) {
    private val records = linkedMapOf<String, LeaseRecord>()
    private val generations = linkedMapOf<String, LeaseGeneration>()

    init {
        require(ttlNanos > 0L) { "ttlNanos must be positive" }
    }

    /**
     * Issues a lease only for the process binding already recorded in the authoritative runtime.
     * Callers should authorize the returned identity before allowing guest execution.
     */
    @Synchronized
    fun issue(
        authoritativeRuntime: VirtualInstanceRuntime,
        callingPid: Int,
        operation: VirtualServiceOperation,
        component: String,
        processSlot: String
    ): EngineServiceOperationLeaseIdentity {
        validateIssueBinding(authoritativeRuntime, callingPid, component, processSlot)
        val now = clockNanos()
        pruneExpiredLocked(now)
        val processId = checkNotNull(authoritativeRuntime.processId)
        val requestedGeneration = LeaseGeneration(
            runtimeEpoch = authoritativeRuntime.runtimeEpoch,
            engineSessionId = authoritativeRuntime.engineSessionId,
            processSlot = processSlot,
            processId = processId
        )
        val currentGeneration = generations[authoritativeRuntime.instanceId]
        when {
            currentGeneration == null -> generations[authoritativeRuntime.instanceId] = requestedGeneration
            requestedGeneration.runtimeEpoch < currentGeneration.runtimeEpoch -> {
                error("cannot issue a Service operation lease for a stale runtimeEpoch")
            }
            requestedGeneration.runtimeEpoch == currentGeneration.runtimeEpoch -> {
                check(requestedGeneration.hasSameBinding(currentGeneration)) {
                    "Service operation lease binding changed without a runtimeEpoch advance"
                }
                check(!currentGeneration.revoked) {
                    "cannot issue a Service operation lease for a revoked runtime generation"
                }
            }
            else -> {
                check(currentGeneration.engineSessionId != requestedGeneration.engineSessionId) {
                    "Service operation lease engineSessionId must change with runtimeEpoch"
                }
                records.entries.removeAll { (_, record) ->
                    record.identity.instanceId == authoritativeRuntime.instanceId
                }
                generations[authoritativeRuntime.instanceId] = requestedGeneration
            }
        }

        val identity = EngineServiceOperationLeaseIdentity(
            leaseToken = uniqueTokenLocked(),
            instanceId = authoritativeRuntime.instanceId,
            runtimeEpoch = authoritativeRuntime.runtimeEpoch,
            engineSessionId = authoritativeRuntime.engineSessionId,
            processSlot = processSlot,
            processId = processId,
            operation = operation.name,
            component = component,
            issuedAtNanos = now,
            expiresAtNanos = saturatingAdd(now, ttlNanos)
        )
        records[identity.leaseToken] = LeaseRecord(identity)
        return identity
    }

    @Synchronized
    fun authorize(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): EngineServiceOperationLeaseDecision {
        val lookup = lookupForTransition(identity, callingPid)
        val record = lookup.record ?: return checkNotNull(lookup.rejection)
        return when (record.state) {
            EngineServiceOperationLeaseState.ISSUED -> {
                record.state = EngineServiceOperationLeaseState.AUTHORIZED
                accepted(record.state, "service_operation_lease_authorized")
            }
            EngineServiceOperationLeaseState.AUTHORIZED -> rejected(
                EngineServiceOperationLeaseState.AUTHORIZED,
                "service_operation_lease_replayed"
            )
            EngineServiceOperationLeaseState.COMMITTED -> rejected(
                EngineServiceOperationLeaseState.COMMITTED,
                "service_operation_lease_already_committed"
            )
            EngineServiceOperationLeaseState.ABORTED -> rejected(
                EngineServiceOperationLeaseState.ABORTED,
                "service_operation_lease_already_aborted"
            )
            else -> rejected(record.state, "service_operation_lease_invalid_state")
        }
    }

    @Synchronized
    fun commit(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): EngineServiceOperationLeaseDecision = commit(identity, callingPid) { true }

    @Synchronized
    fun commit(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int,
        commitAction: () -> Boolean
    ): EngineServiceOperationLeaseDecision {
        val lookup = lookupForTransition(identity, callingPid)
        val record = lookup.record ?: return checkNotNull(lookup.rejection)
        return when (record.state) {
            EngineServiceOperationLeaseState.AUTHORIZED -> {
                if (!runCatching(commitAction).getOrDefault(false)) {
                    return rejected(
                        EngineServiceOperationLeaseState.AUTHORIZED,
                        "service_operation_lease_commit_action_failed"
                    )
                }
                record.state = EngineServiceOperationLeaseState.COMMITTED
                accepted(record.state, "service_operation_lease_committed")
            }
            EngineServiceOperationLeaseState.COMMITTED -> accepted(
                state = EngineServiceOperationLeaseState.COMMITTED,
                reason = "service_operation_lease_commit_idempotent",
                idempotent = true
            )
            EngineServiceOperationLeaseState.ISSUED -> rejected(
                EngineServiceOperationLeaseState.ISSUED,
                "service_operation_lease_not_authorized"
            )
            EngineServiceOperationLeaseState.ABORTED -> rejected(
                EngineServiceOperationLeaseState.ABORTED,
                "service_operation_lease_already_aborted"
            )
            else -> rejected(record.state, "service_operation_lease_invalid_state")
        }
    }

    @Synchronized
    fun abort(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): EngineServiceOperationLeaseDecision {
        val lookup = lookupForTransition(identity, callingPid)
        val record = lookup.record ?: return checkNotNull(lookup.rejection)
        return when (record.state) {
            EngineServiceOperationLeaseState.ISSUED,
            EngineServiceOperationLeaseState.AUTHORIZED -> {
                record.state = EngineServiceOperationLeaseState.ABORTED
                accepted(record.state, "service_operation_lease_aborted")
            }
            EngineServiceOperationLeaseState.ABORTED -> accepted(
                state = EngineServiceOperationLeaseState.ABORTED,
                reason = "service_operation_lease_abort_idempotent",
                idempotent = true
            )
            EngineServiceOperationLeaseState.COMMITTED -> rejected(
                EngineServiceOperationLeaseState.COMMITTED,
                "service_operation_lease_already_committed"
            )
            else -> rejected(record.state, "service_operation_lease_invalid_state")
        }
    }

    @Synchronized
    fun revokeGeneration(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Int {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        val before = records.size
        records.entries.removeAll { (_, record) ->
            record.identity.instanceId == instanceId &&
                record.identity.runtimeEpoch == runtimeEpoch &&
                record.identity.engineSessionId == engineSessionId
        }
        val current = generations[instanceId]
        when {
            current == null -> generations[instanceId] = LeaseGeneration(
                runtimeEpoch = runtimeEpoch,
                engineSessionId = engineSessionId,
                processSlot = null,
                processId = null,
                revoked = true
            )
            current.runtimeEpoch < runtimeEpoch -> generations[instanceId] = LeaseGeneration(
                runtimeEpoch = runtimeEpoch,
                engineSessionId = engineSessionId,
                processSlot = null,
                processId = null,
                revoked = true
            )
            current.runtimeEpoch == runtimeEpoch && current.engineSessionId == engineSessionId -> {
                current.revoked = true
            }
        }
        return before - records.size
    }

    @Synchronized
    fun revokeInstance(instanceId: String): Int {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        val before = records.size
        records.entries.removeAll { (_, record) -> record.identity.instanceId == instanceId }
        generations[instanceId]?.revoked = true
        return before - records.size
    }

    @Synchronized
    internal fun size(): Int {
        pruneExpiredLocked()
        return records.size
    }

    @Synchronized
    internal fun stateOf(identity: EngineServiceOperationLeaseIdentity): EngineServiceOperationLeaseState? {
        val record = records[identity.leaseToken] ?: return null
        if (isExpired(record.identity, clockNanos())) {
            records.remove(identity.leaseToken)
            return EngineServiceOperationLeaseState.EXPIRED
        }
        return record.state.takeIf { record.identity == identity }
    }

    private fun validateIssueBinding(
        runtime: VirtualInstanceRuntime,
        callingPid: Int,
        component: String,
        processSlot: String
    ) {
        validateLeaseText("component", component)
        validateLeaseText("processSlot", processSlot)
        require(callingPid > 0) { "callingPid must be positive" }
        require(runtime.processId != null) { "authoritative runtime must have a processId" }
        require(runtime.processId == callingPid) {
            "callingPid must match the authoritative runtime processId"
        }
        require(runtime.processSlot == processSlot) {
            "processSlot must match the authoritative runtime processSlot"
        }
        require(runtime.processName == null || runtime.processName == processSlot) {
            "processSlot must match the authoritative runtime processName"
        }
        require(runtime.state in LIVE_RUNTIME_STATES) {
            "cannot issue a Service operation lease for runtime state ${runtime.state.name}"
        }
    }

    private fun lookupForTransition(
        identity: EngineServiceOperationLeaseIdentity,
        callingPid: Int
    ): LeaseLookup {
        if (callingPid <= 0 || callingPid != identity.processId) {
            return LeaseLookup(rejection = rejected(
                EngineServiceOperationLeaseState.REJECTED,
                "service_operation_lease_process_id_mismatch"
            ))
        }
        val record = records[identity.leaseToken]
            ?: return LeaseLookup(rejection = missingLeaseDecision(identity))
        val now = clockNanos()
        if (isExpired(record.identity, now)) {
            records.remove(identity.leaseToken)
            pruneExpiredLocked(now)
            return LeaseLookup(rejection = rejected(
                EngineServiceOperationLeaseState.EXPIRED,
                "service_operation_lease_expired"
            ))
        }
        pruneExpiredLocked(now, exceptToken = identity.leaseToken)
        if (record.identity != identity) {
            return LeaseLookup(rejection = rejected(
                EngineServiceOperationLeaseState.REJECTED,
                "service_operation_lease_identity_mismatch"
            ))
        }
        val generation = generations[identity.instanceId]
        if (generation == null || generation.isNewerThan(identity)) {
            records.remove(identity.leaseToken)
            return LeaseLookup(rejection = rejected(
                EngineServiceOperationLeaseState.REVOKED,
                "service_operation_lease_stale_generation"
            ))
        }
        if (!generation.matches(identity) || generation.revoked) {
            records.remove(identity.leaseToken)
            return LeaseLookup(rejection = rejected(
                EngineServiceOperationLeaseState.REVOKED,
                "service_operation_lease_generation_revoked"
            ))
        }
        return LeaseLookup(record = record)
    }

    private fun missingLeaseDecision(
        identity: EngineServiceOperationLeaseIdentity
    ): EngineServiceOperationLeaseDecision {
        val generation = generations[identity.instanceId]
        return when {
            generation?.isNewerThan(identity) == true -> rejected(
                EngineServiceOperationLeaseState.REVOKED,
                "service_operation_lease_stale_generation"
            )
            generation?.matches(identity) == true && generation.revoked -> rejected(
                EngineServiceOperationLeaseState.REVOKED,
                "service_operation_lease_generation_revoked"
            )
            else -> rejected(
                EngineServiceOperationLeaseState.REJECTED,
                "service_operation_lease_not_found"
            )
        }
    }

    private fun uniqueTokenLocked(): String {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenFactory().takeIf { it.isNotBlank() }
                ?: error("Service operation lease token factory returned blank")
            validateLeaseText("leaseToken", token)
            if (token !in records) return token
        }
        error("unable to allocate a unique Service operation lease token")
    }

    private fun pruneExpiredLocked(
        now: Long = clockNanos(),
        exceptToken: String? = null
    ) {
        records.entries.removeAll { (token, record) ->
            token != exceptToken && isExpired(record.identity, now)
        }
    }

    private fun isExpired(identity: EngineServiceOperationLeaseIdentity, now: Long): Boolean =
        now - identity.expiresAtNanos >= 0L

    private data class LeaseRecord(
        val identity: EngineServiceOperationLeaseIdentity,
        var state: EngineServiceOperationLeaseState = EngineServiceOperationLeaseState.ISSUED
    )

    private data class LeaseGeneration(
        val runtimeEpoch: Long,
        val engineSessionId: String,
        val processSlot: String?,
        val processId: Int?,
        var revoked: Boolean = false
    ) {
        fun hasSameBinding(other: LeaseGeneration): Boolean =
            engineSessionId == other.engineSessionId &&
                processSlot == other.processSlot &&
                processId == other.processId

        fun matches(identity: EngineServiceOperationLeaseIdentity): Boolean =
            runtimeEpoch == identity.runtimeEpoch &&
                engineSessionId == identity.engineSessionId &&
                (processSlot == null || processSlot == identity.processSlot) &&
                (processId == null || processId == identity.processId)

        fun isNewerThan(identity: EngineServiceOperationLeaseIdentity): Boolean =
            runtimeEpoch > identity.runtimeEpoch ||
                runtimeEpoch == identity.runtimeEpoch && engineSessionId != identity.engineSessionId
    }

    private data class LeaseLookup(
        val record: LeaseRecord? = null,
        val rejection: EngineServiceOperationLeaseDecision? = null
    )

    companion object {
        private const val MAX_TOKEN_ATTEMPTS = 8
        private val DEFAULT_TTL_NANOS = TimeUnit.MINUTES.toNanos(2)
        private val LIVE_RUNTIME_STATES = setOf(
            VirtualRuntimeState.CREATED,
            VirtualRuntimeState.PREWARMED,
            VirtualRuntimeState.RUNNING
        )

    }
}

private fun accepted(
    state: EngineServiceOperationLeaseState,
    reason: String,
    idempotent: Boolean = false
) = EngineServiceOperationLeaseDecision(
    accepted = true,
    idempotent = idempotent,
    state = state,
    reason = reason
)

private fun rejected(
    state: EngineServiceOperationLeaseState,
    reason: String
) = EngineServiceOperationLeaseDecision(
    accepted = false,
    idempotent = false,
    state = state,
    reason = reason
)

private fun validateLeaseText(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value == value.trim()) { "$name must be trimmed" }
    require(value.length <= MAX_LEASE_IDENTITY_LENGTH) {
        "$name must be at most $MAX_LEASE_IDENTITY_LENGTH characters"
    }
    require('\u0000' !in value) { "$name must not contain NUL" }
}

private fun secureServiceLeaseToken(): String {
    val bytes = ByteArray(32)
    ServiceLeaseSecureRandom.instance.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private const val MAX_LEASE_IDENTITY_LENGTH = 512

private object ServiceLeaseSecureRandom {
    val instance = SecureRandom()
}

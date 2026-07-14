package com.multiapp.core.engine

import android.os.SystemClock
import java.util.UUID

data class EngineComponentProcessLaunchIdentity(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processEpoch: Long,
    val clientSessionId: String,
    val attachCapability: String,
    val effectiveGuestProcessName: String,
    val processSlot: String,
    val issuedAtNanos: Long,
    val expiresAtNanos: Long
) {
    val key: EngineComponentProcessClientKey
        get() = EngineComponentProcessClientKey(instanceId, effectiveGuestProcessName)

    init {
        EngineComponentProcessClientKey(instanceId, effectiveGuestProcessName)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank() && engineSessionId == engineSessionId.trim()) {
            "engineSessionId must be non-blank and trimmed"
        }
        require(processEpoch > 0L) { "processEpoch must be positive" }
        require(clientSessionId.isNotBlank() && clientSessionId == clientSessionId.trim()) {
            "clientSessionId must be non-blank and trimmed"
        }
        require(attachCapability.length >= MIN_CAPABILITY_LENGTH && attachCapability == attachCapability.trim()) {
            "attachCapability must be an unguessable trimmed token"
        }
        require(processSlot.isNotBlank() && processSlot == processSlot.trim()) {
            "processSlot must be non-blank and trimmed"
        }
        require(issuedAtNanos >= 0L) { "issuedAtNanos must not be negative" }
        require(expiresAtNanos > issuedAtNanos) { "expiresAtNanos must be after issuedAtNanos" }
    }

    fun toClientIdentity(processId: Int, processStartTicks: Long) = EngineComponentProcessClientIdentity(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processEpoch = processEpoch,
        clientSessionId = clientSessionId,
        effectiveGuestProcessName = effectiveGuestProcessName,
        processSlot = processSlot,
        processId = processId,
        processStartTicks = processStartTicks
    )

    private companion object {
        const val MIN_CAPABILITY_LENGTH = 32
    }
}

data class EngineComponentProcessLaunchCapabilityResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val identity: EngineComponentProcessLaunchIdentity?,
    val reason: String
)

/** One-time engine-issued authority for attaching a newly started custom guest process. */
class EngineComponentProcessLaunchCapabilityRegistry(
    private val nanoTime: () -> Long = {
        runCatching { SystemClock.elapsedRealtimeNanos() }
            .getOrElse { System.nanoTime().coerceAtLeast(0L) }
    },
    private val clientSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val capabilityFactory: () -> String = {
        UUID.randomUUID().toString() + UUID.randomUUID().toString()
    },
    private val ttlNanos: Long = DEFAULT_TTL_NANOS
) {
    private val states = linkedMapOf<EngineComponentProcessClientKey, CapabilityState>()
    private val generations = linkedMapOf<String, RuntimeGeneration>()

    init {
        require(ttlNanos > 0L) { "ttlNanos must be positive" }
    }

    @Synchronized
    fun issue(
        assignment: EngineComponentProcessSlotAssignment
    ): EngineComponentProcessLaunchCapabilityResult {
        val key = EngineComponentProcessClientKey(
            assignment.instanceId,
            assignment.guestProcessName
        )
        val generation = RuntimeGeneration(
            assignment.runtimeEpoch,
            assignment.engineSessionId,
            revoked = false
        )
        val generationFailure = enterGenerationLocked(key.instanceId, generation)
        if (generationFailure != null) return rejected(generationFailure)

        val now = nanoTime()
        val state = states.getOrPut(key, ::CapabilityState)
        state.pending?.takeIf { pending -> now < pending.expiresAtNanos }?.let { pending ->
            return accepted(pending, idempotent = true, "component_process_launch_capability_reused")
        }
        state.pending = null
        val nextEpoch = state.lastProcessEpoch + 1L
        val identity = EngineComponentProcessLaunchIdentity(
            instanceId = assignment.instanceId,
            runtimeEpoch = assignment.runtimeEpoch,
            engineSessionId = assignment.engineSessionId,
            processEpoch = nextEpoch,
            clientSessionId = clientSessionIdFactory().also { sessionId ->
                require(sessionId.isNotBlank() && sessionId == sessionId.trim()) {
                    "clientSessionIdFactory returned an invalid value"
                }
            },
            attachCapability = capabilityFactory().also { capability ->
                require(capability.length >= MIN_CAPABILITY_LENGTH && capability == capability.trim()) {
                    "capabilityFactory returned an invalid value"
                }
                require(states.values.none { state -> state.pending?.attachCapability == capability }) {
                    "capabilityFactory returned a duplicate value"
                }
            },
            effectiveGuestProcessName = assignment.guestProcessName,
            processSlot = assignment.processSlot,
            issuedAtNanos = now,
            expiresAtNanos = Math.addExact(now, ttlNanos)
        )
        state.lastProcessEpoch = nextEpoch
        state.pending = identity
        return accepted(identity, idempotent = false, "component_process_launch_capability_issued")
    }

    @Synchronized
    fun query(attachCapability: String): EngineComponentProcessLaunchCapabilityResult {
        if (attachCapability.length < MIN_CAPABILITY_LENGTH || attachCapability != attachCapability.trim()) {
            return rejected("component_process_launch_capability_invalid")
        }
        val pending = states.values.asSequence()
            .mapNotNull(CapabilityState::pending)
            .firstOrNull { identity -> identity.attachCapability == attachCapability }
            ?: return rejected("component_process_launch_capability_not_found")
        if (nanoTime() >= pending.expiresAtNanos) {
            states[pending.key]?.pending = null
            return rejected("component_process_launch_capability_expired")
        }
        return accepted(pending, idempotent = true, "component_process_launch_capability_found")
    }

    @Synchronized
    fun consume(attachCapability: String): EngineComponentProcessLaunchCapabilityResult {
        val queried = query(attachCapability)
        val identity = queried.identity ?: return queried
        val generation = generations[identity.instanceId]
            ?: return rejected("component_process_launch_generation_not_found")
        if (generation.revoked) return rejected("component_process_launch_generation_revoked")
        if (
            generation.runtimeEpoch != identity.runtimeEpoch ||
            generation.engineSessionId != identity.engineSessionId
        ) {
            return rejected("component_process_launch_generation_mismatch")
        }
        val state = states[identity.key]
            ?: return rejected("component_process_launch_capability_not_found")
        val pending = state.pending
            ?: return rejected("component_process_launch_capability_replayed")
        if (pending != identity) return rejected("component_process_launch_capability_mismatch")
        if (nanoTime() >= pending.expiresAtNanos) {
            state.pending = null
            return rejected("component_process_launch_capability_expired")
        }
        state.pending = null
        return accepted(identity, idempotent = false, "component_process_launch_capability_consumed")
    }

    @Synchronized
    fun revokeGeneration(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Int {
        if (
            instanceId.isBlank() || instanceId != instanceId.trim() || runtimeEpoch <= 0L ||
            engineSessionId.isBlank() || engineSessionId != engineSessionId.trim()
        ) {
            return 0
        }
        val current = generations[instanceId]
        when {
            current == null || runtimeEpoch > current.runtimeEpoch -> {
                generations[instanceId] = RuntimeGeneration(runtimeEpoch, engineSessionId, revoked = true)
            }
            current.runtimeEpoch == runtimeEpoch && current.engineSessionId == engineSessionId -> {
                current.revoked = true
            }
            else -> return 0
        }
        val keys = states.keys.filter { key -> key.instanceId == instanceId }
        keys.forEach(states::remove)
        return keys.size
    }

    @Synchronized
    fun revokeInstance(instanceId: String): Int {
        if (instanceId.isBlank() || instanceId != instanceId.trim()) return 0
        generations.remove(instanceId)
        val keys = states.keys.filter { key -> key.instanceId == instanceId }
        keys.forEach(states::remove)
        return keys.size
    }

    private fun enterGenerationLocked(instanceId: String, requested: RuntimeGeneration): String? {
        val current = generations[instanceId]
        return when {
            current == null -> {
                generations[instanceId] = requested
                null
            }
            requested.runtimeEpoch > current.runtimeEpoch -> {
                states.keys.filter { key -> key.instanceId == instanceId }.forEach(states::remove)
                generations[instanceId] = requested
                null
            }
            requested.runtimeEpoch < current.runtimeEpoch -> "component_process_launch_generation_stale"
            requested.engineSessionId != current.engineSessionId -> "component_process_launch_generation_conflict"
            current.revoked -> "component_process_launch_generation_revoked"
            else -> null
        }
    }

    private data class CapabilityState(
        var lastProcessEpoch: Long = 0L,
        var pending: EngineComponentProcessLaunchIdentity? = null
    )

    private data class RuntimeGeneration(
        val runtimeEpoch: Long,
        val engineSessionId: String,
        var revoked: Boolean
    )

    private companion object {
        const val DEFAULT_TTL_NANOS = 30_000_000_000L
        const val MIN_CAPABILITY_LENGTH = 32
    }
}

private fun accepted(
    identity: EngineComponentProcessLaunchIdentity,
    idempotent: Boolean,
    reason: String
) = EngineComponentProcessLaunchCapabilityResult(
    accepted = true,
    idempotent = idempotent,
    identity = identity,
    reason = reason
)

private fun rejected(reason: String) = EngineComponentProcessLaunchCapabilityResult(
    accepted = false,
    idempotent = false,
    identity = null,
    reason = reason
)

package com.multiapp.core.engine

import com.multiapp.core.model.engine.VirtualInstanceRuntime
import java.security.SecureRandom
import java.util.Base64

data class EngineActivityLaunchIdentity(
    val capabilityToken: String,
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val proxyActivityClassName: String,
    val guestActivityClassName: String
) {
    init {
        require(capabilityToken.isNotBlank()) { "capabilityToken must not be blank" }
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        require(guestActivityClassName.isNotBlank()) { "guestActivityClassName must not be blank" }
    }
}

data class EngineActivityLaunchAuthorization(
    val accepted: Boolean,
    val idempotent: Boolean,
    val reason: String
)

class EngineActivityLaunchCapabilityRegistry(
    private val clockNanos: () -> Long = System::nanoTime,
    private val tokenFactory: () -> String = ::secureCapabilityToken,
    private val ttlNanos: Long = DEFAULT_TTL_NANOS
) {
    private val records = linkedMapOf<String, CapabilityRecord>()
    private val generations = linkedMapOf<String, CapabilityGeneration>()

    init {
        require(ttlNanos > 0L) { "ttlNanos must be positive" }
    }

    @Synchronized
    fun issue(
        runtime: VirtualInstanceRuntime,
        processId: Int,
        proxyActivityClassName: String,
        guestActivityClassName: String
    ): EngineActivityLaunchIdentity {
        require(processId > 0) { "processId must be positive" }
        require(runtime.processId == processId) {
            "processId must match the authoritative runtime processId"
        }
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        require(guestActivityClassName.isNotBlank()) { "guestActivityClassName must not be blank" }
        val now = clockNanos()
        pruneExpiredLocked(now)
        val generation = CapabilityGeneration(
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            processId = processId,
            expiresAtNanos = saturatingAdd(now, ttlNanos)
        )
        val currentGeneration = generations[runtime.instanceId]
        if (currentGeneration != null) {
            check(generation.runtimeEpoch >= currentGeneration.runtimeEpoch) {
                "cannot issue an Activity launch capability for a stale runtimeEpoch"
            }
            check(
                generation.runtimeEpoch != currentGeneration.runtimeEpoch ||
                    generation.hasSameBinding(currentGeneration)
            ) {
                "Activity launch capability binding changed without a runtimeEpoch advance"
            }
        }
        val capabilityToken = uniqueTokenLocked()
        if (currentGeneration == null || generation.runtimeEpoch > currentGeneration.runtimeEpoch) {
            records.entries.removeAll { (_, record) -> record.identity.instanceId == runtime.instanceId }
            generations[runtime.instanceId] = generation
        } else {
            currentGeneration.expiresAtNanos = generation.expiresAtNanos
        }
        val identity = EngineActivityLaunchIdentity(
            capabilityToken = capabilityToken,
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            proxyActivityClassName = proxyActivityClassName,
            guestActivityClassName = guestActivityClassName
        )
        records[identity.capabilityToken] = CapabilityRecord(
            identity = identity,
            processId = processId,
            expiresAtNanos = generation.expiresAtNanos
        )
        return identity
    }

    @Synchronized
    fun authorize(identity: EngineActivityLaunchIdentity, callingPid: Int): EngineActivityLaunchAuthorization {
        pruneExpiredLocked()
        val record = records[identity.capabilityToken]
            ?: return rejected("launch_capability_not_found")
        if (record.identity != identity) return rejected("launch_capability_identity_mismatch")
        if (callingPid <= 0 || callingPid != record.processId) {
            return rejected("launch_capability_process_id_mismatch")
        }
        val idempotent = record.authorized
        record.authorized = true
        return EngineActivityLaunchAuthorization(
            accepted = true,
            idempotent = idempotent,
            reason = if (idempotent) "launch_capability_already_authorized" else "launch_capability_authorized"
        )
    }

    @Synchronized
    fun validateResume(
        capabilityToken: String,
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        callingPid: Int
    ): EngineActivityLaunchAuthorization {
        pruneExpiredLocked()
        val record = records[capabilityToken] ?: return rejected("launch_capability_not_found")
        val identity = record.identity
        if (
            identity.instanceId != instanceId || identity.runtimeEpoch != runtimeEpoch ||
            identity.engineSessionId != engineSessionId || identity.processSlot != processSlot
        ) {
            return rejected("launch_capability_identity_mismatch")
        }
        if (callingPid <= 0 || callingPid != record.processId) {
            return rejected("launch_capability_process_id_mismatch")
        }
        if (!record.authorized) return rejected("launch_capability_not_authorized")
        return EngineActivityLaunchAuthorization(
            accepted = true,
            idempotent = record.completed,
            reason = if (record.completed) {
                "launch_capability_resume_already_completed"
            } else {
                "launch_capability_resume_authorized"
            }
        )
    }

    @Synchronized
    fun complete(capabilityToken: String): Boolean {
        pruneExpiredLocked()
        val record = records[capabilityToken] ?: return false
        if (!record.authorized) return false
        record.completed = true
        return true
    }

    @Synchronized
    fun revoke(capabilityToken: String): Boolean = records.remove(capabilityToken) != null

    @Synchronized
    fun revokeGeneration(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Int {
        val before = records.size
        records.entries.removeAll { (_, record) ->
            record.identity.instanceId == instanceId &&
                record.identity.runtimeEpoch == runtimeEpoch &&
                record.identity.engineSessionId == engineSessionId
        }
        return before - records.size
    }

    @Synchronized
    fun revokeInstance(instanceId: String): Int {
        val before = records.size
        records.entries.removeAll { (_, record) -> record.identity.instanceId == instanceId }
        generations.remove(instanceId)
        return before - records.size
    }

    @Synchronized
    internal fun size(): Int {
        pruneExpiredLocked()
        return records.size
    }

    private fun uniqueTokenLocked(): String {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenFactory().takeIf { it.isNotBlank() }
                ?: error("activity launch capability token factory returned blank")
            if (token !in records) return token
        }
        error("unable to allocate a unique activity launch capability token")
    }

    private fun pruneExpiredLocked(now: Long = clockNanos()) {
        records.entries.removeAll { (_, record) -> now - record.expiresAtNanos >= 0L }
        val retainedInstances = records.values.mapTo(hashSetOf()) { it.identity.instanceId }
        generations.entries.removeAll { (instanceId, generation) ->
            instanceId !in retainedInstances && now - generation.expiresAtNanos >= 0L
        }
    }

    private fun rejected(reason: String) = EngineActivityLaunchAuthorization(
        accepted = false,
        idempotent = false,
        reason = reason
    )

    private data class CapabilityRecord(
        val identity: EngineActivityLaunchIdentity,
        val processId: Int,
        val expiresAtNanos: Long,
        var authorized: Boolean = false,
        var completed: Boolean = false
    )

    private data class CapabilityGeneration(
        val runtimeEpoch: Long,
        val engineSessionId: String,
        val processSlot: String,
        val processId: Int,
        var expiresAtNanos: Long
    ) {
        fun hasSameBinding(other: CapabilityGeneration): Boolean =
            engineSessionId == other.engineSessionId &&
                processSlot == other.processSlot &&
                processId == other.processId
    }

    companion object {
        private const val MAX_TOKEN_ATTEMPTS = 8
        private val DEFAULT_TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(2)

        val global = EngineActivityLaunchCapabilityRegistry()
    }
}

private fun secureCapabilityToken(): String {
    val bytes = ByteArray(32)
    SecureRandomHolder.instance.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private object SecureRandomHolder {
    val instance = SecureRandom()
}

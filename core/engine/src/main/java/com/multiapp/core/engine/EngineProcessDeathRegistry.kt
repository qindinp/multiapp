package com.multiapp.core.engine

import android.os.IBinder

class EngineProcessDeathRegistry {
    private val states = linkedMapOf<String, InstanceState>()

    fun register(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        token: IBinder,
        onDeath: () -> Unit
    ): Boolean {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        lateinit var replacement: Registration
        val recipient = IBinder.DeathRecipient { handleDeath(instanceId, replacement) }
        replacement = Registration(runtimeEpoch, engineSessionId, token, recipient, onDeath)
        val reserved = synchronized(this) {
            val state = states.getOrPut(instanceId, ::InstanceState)
            val superseded = state.generation?.supersedes(replacement) == true ||
                sequenceOf(state.active).plus(state.pending.asSequence())
                .filterNotNull()
                .any { it.supersedes(replacement) }
            if (superseded) {
                cleanupStateLocked(instanceId, state)
                false
            } else {
                state.pending += replacement
                true
            }
        }
        if (!reserved) return false

        val linked = runCatching {
            token.linkToDeath(recipient, 0)
            token.isBinderAlive
        }.getOrDefault(false)
        if (!linked) {
            synchronized(this) {
                states[instanceId]?.let { state ->
                    state.pending.remove(replacement)
                    replacement.deactivateLocked()
                    cleanupStateLocked(instanceId, state)
                }
            }
            unlink(replacement)
            return false
        }

        var previous: Registration? = null
        val installed = synchronized(this) {
            val state = states[instanceId]
            if (state == null || !state.pending.remove(replacement)) {
                replacement.deactivateLocked()
                false
            } else if (replacement.dead || replacement.cancelled || state.active?.supersedes(replacement) == true) {
                replacement.deactivateLocked()
                cleanupStateLocked(instanceId, state)
                false
            } else {
                previous = state.active
                previous?.deactivateLocked()
                state.active = replacement
                state.generation = Generation(replacement.runtimeEpoch, replacement.engineSessionId)
                true
            }
        }
        if (!installed) {
            unlink(replacement)
            return false
        }
        previous?.let(::unlink)
        return true
    }

    fun remove(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Boolean {
        val removed = removeLocked(instanceId) { registration ->
            registration.runtimeEpoch == runtimeEpoch && registration.engineSessionId == engineSessionId
        }
        removed.forEach(::unlink)
        return removed.isNotEmpty()
    }

    fun removeInstance(instanceId: String): Boolean {
        val removed = removeLocked(instanceId, clearGeneration = true) { true }
        removed.forEach(::unlink)
        return removed.isNotEmpty()
    }

    internal fun size(): Int = synchronized(this) { states.values.count { it.active != null } }

    private fun handleDeath(instanceId: String, registration: Registration) {
        val callback = synchronized(this) {
            registration.dead = true
            val state = states[instanceId]
            state?.pending?.remove(registration)
            val wasActive = state?.active === registration
            if (wasActive) state.active = null
            val callback = registration.onDeath.takeIf { wasActive && !registration.cancelled }
            registration.deactivateLocked()
            if (state != null) cleanupStateLocked(instanceId, state)
            callback
        }
        callback?.invoke()
    }

    private fun removeLocked(
        instanceId: String,
        clearGeneration: Boolean = false,
        predicate: (Registration) -> Boolean
    ): List<Registration> = synchronized(this) {
        val state = states[instanceId] ?: return@synchronized emptyList()
        val removed = buildList {
            state.active?.takeIf(predicate)?.let { registration ->
                state.active = null
                add(registration)
            }
            val pending = state.pending.filter(predicate)
            state.pending.removeAll(pending.toSet())
            addAll(pending)
        }
        removed.forEach(Registration::deactivateLocked)
        if (clearGeneration) state.generation = null
        cleanupStateLocked(instanceId, state)
        removed
    }

    private fun cleanupStateLocked(instanceId: String, state: InstanceState) {
        if (
            state.active == null && state.pending.isEmpty() && state.generation == null &&
            states[instanceId] === state
        ) {
            states.remove(instanceId)
        }
    }

    private fun unlink(registration: Registration) {
        runCatching { registration.token.unlinkToDeath(registration.recipient, 0) }
    }

    private class InstanceState {
        var active: Registration? = null
        var generation: Generation? = null
        val pending = linkedSetOf<Registration>()
    }

    private data class Generation(
        val runtimeEpoch: Long,
        val engineSessionId: String
    ) {
        fun supersedes(other: Registration): Boolean =
            runtimeEpoch > other.runtimeEpoch ||
                (runtimeEpoch == other.runtimeEpoch && engineSessionId != other.engineSessionId)
    }

    private class Registration(
        val runtimeEpoch: Long,
        val engineSessionId: String,
        val token: IBinder,
        val recipient: IBinder.DeathRecipient,
        var onDeath: (() -> Unit)?
    ) {
        var dead: Boolean = false
        var cancelled: Boolean = false

        fun supersedes(other: Registration): Boolean =
            runtimeEpoch > other.runtimeEpoch ||
                (runtimeEpoch == other.runtimeEpoch && engineSessionId != other.engineSessionId)

        fun deactivateLocked() {
            cancelled = true
            onDeath = null
        }
    }
}

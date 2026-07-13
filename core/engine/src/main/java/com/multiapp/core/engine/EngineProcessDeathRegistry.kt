package com.multiapp.core.engine

import android.os.IBinder

data class EngineProcessDeathRegistrationResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val reason: String
)

class EngineProcessDeathRegistry {
    private val states = linkedMapOf<String, InstanceState>()

    fun register(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        token: IBinder,
        onDeath: () -> Unit
    ): Boolean = register(
        identity = EngineProcessClientIdentity(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = LEGACY_PROCESS_SLOT,
            processId = LEGACY_PROCESS_ID
        ),
        token = token,
        onDeath = onDeath
    ).accepted

    fun register(
        identity: EngineProcessClientIdentity,
        token: IBinder,
        onDeath: () -> Unit
    ): EngineProcessDeathRegistrationResult {
        val existing = synchronized(this) {
            states[identity.instanceId]?.active?.takeIf { active ->
                active.identity == identity && active.token === token && !active.cancelled
            }
        }
        if (existing != null) {
            if (tokenAlive(existing)) {
                return EngineProcessDeathRegistrationResult(true, true, "client_already_attached")
            }
            handleDeath(identity.instanceId, existing)
        }

        lateinit var replacement: Registration
        val recipient = IBinder.DeathRecipient { handleDeath(identity.instanceId, replacement) }
        replacement = Registration(identity, token, recipient, onDeath)
        val reservation = synchronized(this) {
            val state = states.getOrPut(identity.instanceId, ::InstanceState)
            when {
                state.generation?.rejects(identity) == true -> {
                    cleanupStateLocked(identity.instanceId, state)
                    Reservation(false, "stale_or_replayed_generation")
                }
                sequenceOf(state.active).plus(state.pending.asSequence())
                    .filterNotNull()
                    .any { it.rejects(identity) } -> {
                    cleanupStateLocked(identity.instanceId, state)
                    Reservation(false, "stale_or_conflicting_generation")
                }
                else -> {
                    state.pending += replacement
                    Reservation(true, "registration_reserved")
                }
            }
        }
        if (!reservation.accepted) {
            return EngineProcessDeathRegistrationResult(false, false, reservation.reason)
        }

        val linked = runCatching {
            token.linkToDeath(recipient, 0)
            token.isBinderAlive
        }.getOrDefault(false)
        if (!linked) {
            synchronized(this) {
                states[identity.instanceId]?.let { state ->
                    state.pending.remove(replacement)
                    replacement.deactivateLocked()
                    cleanupStateLocked(identity.instanceId, state)
                }
            }
            unlink(replacement)
            return EngineProcessDeathRegistrationResult(false, false, "client_token_not_alive")
        }

        var previous: Registration? = null
        val installed = synchronized(this) {
            val state = states[identity.instanceId]
            if (state == null || !state.pending.remove(replacement)) {
                replacement.deactivateLocked()
                false
            } else if (
                replacement.dead || replacement.cancelled ||
                state.generation?.rejects(identity) == true ||
                state.active?.rejects(identity) == true
            ) {
                replacement.deactivateLocked()
                cleanupStateLocked(identity.instanceId, state)
                false
            } else {
                previous = state.active
                previous?.deactivateLocked()
                state.active = replacement
                state.generation = Generation(identity)
                true
            }
        }
        if (!installed) {
            unlink(replacement)
            return EngineProcessDeathRegistrationResult(false, false, "generation_changed_during_attach")
        }
        previous?.let(::unlink)
        return EngineProcessDeathRegistrationResult(true, false, "client_token_linked")
    }

    fun isAuthoritative(
        identity: EngineProcessClientIdentity,
        token: IBinder? = null
    ): Boolean {
        val registration = synchronized(this) {
            states[identity.instanceId]?.active?.takeIf { active ->
                active.identity == identity && !active.cancelled &&
                    (token == null || active.token === token)
            }
        } ?: return false
        if (!tokenAlive(registration)) {
            handleDeath(identity.instanceId, registration)
            return false
        }
        return synchronized(this) {
            states[identity.instanceId]?.active === registration && !registration.cancelled
        }
    }

    fun remove(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Boolean {
        val removed = removeLocked(instanceId) { registration ->
            registration.identity.runtimeEpoch == runtimeEpoch &&
                registration.identity.engineSessionId == engineSessionId
        }
        removed.forEach(::unlink)
        return removed.isNotEmpty()
    }

    fun remove(identity: EngineProcessClientIdentity, token: IBinder): Boolean {
        val removed = removeLocked(identity.instanceId) { registration ->
            registration.identity == identity && registration.token === token
        }
        removed.forEach(::unlink)
        return removed.isNotEmpty()
    }

    fun rollback(identity: EngineProcessClientIdentity, token: IBinder): Boolean {
        val removed = synchronized(this) {
            val state = states[identity.instanceId] ?: return@synchronized emptyList()
            val removed = buildList {
                state.active
                    ?.takeIf { it.identity == identity && it.token === token }
                    ?.let { registration ->
                        state.active = null
                        add(registration)
                    }
                val pending = state.pending.filter { it.identity == identity && it.token === token }
                state.pending.removeAll(pending.toSet())
                addAll(pending)
            }
            removed.forEach(Registration::deactivateLocked)
            if (
                state.active == null && state.pending.isEmpty() &&
                state.generation?.identity == identity
            ) {
                state.generation = null
            }
            cleanupStateLocked(identity.instanceId, state)
            removed
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

    private fun tokenAlive(registration: Registration): Boolean =
        runCatching { registration.token.isBinderAlive }.getOrDefault(false)

    private fun unlink(registration: Registration) {
        runCatching { registration.token.unlinkToDeath(registration.recipient, 0) }
    }

    private class InstanceState {
        var active: Registration? = null
        var generation: Generation? = null
        val pending = linkedSetOf<Registration>()
    }

    private data class Generation(
        val identity: EngineProcessClientIdentity
    ) {
        fun rejects(candidate: EngineProcessClientIdentity): Boolean =
            candidate.runtimeEpoch <= identity.runtimeEpoch
    }

    private class Registration(
        val identity: EngineProcessClientIdentity,
        val token: IBinder,
        val recipient: IBinder.DeathRecipient,
        var onDeath: (() -> Unit)?
    ) {
        var dead: Boolean = false
        var cancelled: Boolean = false

        fun rejects(candidate: EngineProcessClientIdentity): Boolean =
            candidate.runtimeEpoch <= identity.runtimeEpoch

        fun deactivateLocked() {
            cancelled = true
            onDeath = null
        }
    }

    private data class Reservation(
        val accepted: Boolean,
        val reason: String
    )

    private companion object {
        const val LEGACY_PROCESS_SLOT = "legacy"
        const val LEGACY_PROCESS_ID = 1
    }
}

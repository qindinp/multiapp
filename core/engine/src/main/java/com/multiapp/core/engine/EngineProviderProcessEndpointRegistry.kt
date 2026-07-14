package com.multiapp.core.engine

import android.os.IBinder

data class EngineProviderProcessEndpointKey(
    val instanceId: String,
    val guestAuthority: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(instanceId == instanceId.trim()) { "instanceId must be normalized" }
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(guestAuthority == guestAuthority.trim()) { "guestAuthority must be normalized" }
        require(';' !in guestAuthority) { "guestAuthority must identify one authority" }
    }
}

/**
 * Complete engine-owned identity for one live Provider process endpoint.
 *
 * [declaredProcessName] preserves the manifest value (for example `:remote`),
 * while [effectiveProcessName] is the resolved guest process name. [processSlot]
 * is the host-declared process that actually carries the guest process.
 */
data class EngineProviderProcessEndpointIdentity(
    val instanceId: String,
    val guestAuthority: String,
    val providerClassName: String,
    val declaredProcessName: String?,
    val effectiveProcessName: String,
    val processSlot: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processId: Int
) {
    val key: EngineProviderProcessEndpointKey
        get() = EngineProviderProcessEndpointKey(instanceId, guestAuthority)

    init {
        EngineProviderProcessEndpointKey(instanceId, guestAuthority)
        require(providerClassName.isNotBlank()) { "providerClassName must not be blank" }
        require(providerClassName == providerClassName.trim()) {
            "providerClassName must be normalized"
        }
        require(declaredProcessName?.isNotBlank() ?: true) {
            "declaredProcessName must not be blank"
        }
        require(declaredProcessName == null || declaredProcessName == declaredProcessName.trim()) {
            "declaredProcessName must be normalized"
        }
        require(effectiveProcessName.isNotBlank()) { "effectiveProcessName must not be blank" }
        require(effectiveProcessName == effectiveProcessName.trim()) {
            "effectiveProcessName must be normalized"
        }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processSlot == processSlot.trim()) { "processSlot must be normalized" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(engineSessionId == engineSessionId.trim()) {
            "engineSessionId must be normalized"
        }
        require(processId > 0) { "processId must be positive" }
    }
}

data class EngineProviderProcessEndpointRegistrationResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val replacedGeneration: Boolean,
    val identity: EngineProviderProcessEndpointIdentity?,
    val reason: String
)

data class EngineProviderProcessEndpointQueryResult(
    val found: Boolean,
    val identity: EngineProviderProcessEndpointIdentity?,
    val endpointBinder: IBinder?,
    val reason: String
)

data class EngineProviderProcessEndpointRemovalResult(
    val removed: Boolean,
    val identity: EngineProviderProcessEndpointIdentity?,
    val reason: String
)

/**
 * Ephemeral Provider endpoint authority owned by the engine process.
 *
 * This registry deliberately keeps a generation tombstone after Binder death.
 * A dead process cannot replay the same generation; a successor must present a
 * strictly newer runtime epoch and a new engine session.
 */
class EngineProviderProcessEndpointRegistry {
    private val states = linkedMapOf<EngineProviderProcessEndpointKey, EndpointState>()

    fun register(
        identity: EngineProviderProcessEndpointIdentity,
        endpointBinder: IBinder,
        onDeath: (EngineProviderProcessEndpointIdentity) -> Unit = {}
    ): EngineProviderProcessEndpointRegistrationResult {
        val existing = synchronized(this) {
            states[identity.key]?.active?.takeIf { active ->
                active.identity == identity && active.endpointBinder === endpointBinder && !active.cancelled
            }
        }
        if (existing != null) {
            if (isAlive(existing)) {
                return accepted(identity, idempotent = true, replacedGeneration = false)
            }
            handleDeath(identity.key, existing)
        }

        lateinit var replacement: Registration
        val recipient = IBinder.DeathRecipient { handleDeath(identity.key, replacement) }
        replacement = Registration(identity, endpointBinder, recipient, onDeath)

        val reservation = synchronized(this) {
            val state = states.getOrPut(identity.key, ::EndpointState)
            val conflict = registrationConflictLocked(state, identity)
            if (conflict != null) {
                cleanupStateLocked(identity.key, state)
                Reservation(false, conflict)
            } else {
                state.pending += replacement
                Reservation(true, "endpoint_registration_reserved")
            }
        }
        if (!reservation.accepted) {
            return rejected(identity, reservation.reason)
        }

        val linked = runCatching {
            endpointBinder.linkToDeath(recipient, 0)
            endpointBinder.isBinderAlive
        }.getOrDefault(false)
        if (!linked) {
            synchronized(this) {
                states[identity.key]?.let { state ->
                    state.pending.remove(replacement)
                    replacement.deactivateLocked()
                    cleanupStateLocked(identity.key, state)
                }
            }
            unlink(replacement)
            return rejected(identity, "endpoint_binder_not_alive")
        }

        var previous: Registration? = null
        var replacedGeneration = false
        val installation = synchronized(this) {
            val state = states[identity.key]
            when {
                state == null || !state.pending.remove(replacement) -> {
                    replacement.deactivateLocked()
                    Reservation(false, "endpoint_registration_cancelled")
                }
                replacement.dead || replacement.cancelled -> {
                    replacement.deactivateLocked()
                    cleanupStateLocked(identity.key, state)
                    Reservation(false, "endpoint_died_during_registration")
                }
                else -> {
                    val conflict = installationConflictLocked(state, replacement)
                    if (conflict != null) {
                        replacement.deactivateLocked()
                        cleanupStateLocked(identity.key, state)
                        Reservation(false, conflict)
                    } else {
                        val previousGeneration = state.generation
                        previous = state.active
                        previous?.deactivateLocked()
                        state.active = replacement
                        state.generation = identity
                        replacedGeneration = previousGeneration != null &&
                            identity.runtimeEpoch > previousGeneration.runtimeEpoch
                        Reservation(true, "endpoint_registered")
                    }
                }
            }
        }
        if (!installation.accepted) {
            unlink(replacement)
            return rejected(identity, installation.reason)
        }
        previous?.let(::unlink)
        return accepted(identity, idempotent = false, replacedGeneration = replacedGeneration)
    }

    fun query(identity: EngineProviderProcessEndpointIdentity): EngineProviderProcessEndpointQueryResult {
        val registration = synchronized(this) {
            states[identity.key]?.active?.takeIf { active ->
                active.identity == identity && !active.cancelled
            }
        } ?: return queryRejected(identity, queryFailureReason(identity))

        if (!isAlive(registration)) {
            handleDeath(identity.key, registration)
            return queryRejected(identity, "endpoint_not_live")
        }
        return synchronized(this) {
            val current = states[identity.key]?.active
            if (current !== registration || registration.cancelled) {
                queryRejected(identity, "endpoint_generation_changed")
            } else {
                EngineProviderProcessEndpointQueryResult(
                    found = true,
                    identity = registration.identity,
                    endpointBinder = registration.endpointBinder,
                    reason = "authoritative_endpoint_found"
                )
            }
        }
    }

    fun unregister(
        identity: EngineProviderProcessEndpointIdentity,
        endpointBinder: IBinder
    ): EngineProviderProcessEndpointRemovalResult {
        val removed = synchronized(this) {
            val state = states[identity.key]
                ?: return@synchronized emptyList()
            buildList {
                state.active
                    ?.takeIf { it.identity == identity && it.endpointBinder === endpointBinder }
                    ?.let { registration ->
                        state.active = null
                        add(registration)
                    }
                val matchingPending = state.pending.filter { registration ->
                    registration.identity == identity && registration.endpointBinder === endpointBinder
                }
                state.pending.removeAll(matchingPending.toSet())
                addAll(matchingPending)
            }.also { registrations ->
                registrations.forEach(Registration::deactivateLocked)
                cleanupStateLocked(identity.key, state)
            }
        }
        removed.forEach(::unlink)
        return if (removed.isEmpty()) {
            EngineProviderProcessEndpointRemovalResult(false, null, "endpoint_registration_not_found")
        } else {
            EngineProviderProcessEndpointRemovalResult(true, identity, "endpoint_unregistered")
        }
    }

    /** Revokes liveness while retaining the generation tombstone against replay. */
    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Int {
        if (instanceId.isBlank() || runtimeEpoch <= 0L || engineSessionId.isBlank()) return 0
        val removed = synchronized(this) {
            states.entries.toList().flatMap { (key, state) ->
                if (key.instanceId != instanceId) return@flatMap emptyList()
                buildList {
                    state.active
                        ?.takeIf { it.identity.matchesGeneration(runtimeEpoch, engineSessionId) }
                        ?.let { registration ->
                            state.active = null
                            add(registration)
                        }
                    val pending = state.pending.filter {
                        it.identity.matchesGeneration(runtimeEpoch, engineSessionId)
                    }
                    state.pending.removeAll(pending.toSet())
                    addAll(pending)
                }.also { registrations ->
                    registrations.maxByOrNull { it.identity.runtimeEpoch }?.identity?.let { revoked ->
                        val current = state.generation
                        if (current == null || revoked.runtimeEpoch > current.runtimeEpoch) {
                            state.generation = revoked
                        }
                    }
                    registrations.forEach(Registration::deactivateLocked)
                    cleanupStateLocked(key, state)
                }
            }
        }
        removed.forEach(::unlink)
        return removed.size
    }

    /**
     * Explicit Binder-death cleanup for control planes that already aggregate
     * process death. Automatic per-endpoint linkToDeath cleanup remains active.
     */
    fun handleBinderDeath(endpointBinder: IBinder): Int {
        val callbacks = mutableListOf<
            Pair<
                (EngineProviderProcessEndpointIdentity) -> Unit,
                EngineProviderProcessEndpointIdentity
                >
            >()
        val removed = synchronized(this) {
            states.entries.toList().flatMap { (key, state) ->
                buildList {
                    state.active
                        ?.takeIf { it.endpointBinder === endpointBinder }
                        ?.let { registration ->
                            state.active = null
                            registration.onDeath?.let { callback ->
                                callbacks += callback to registration.identity
                            }
                            add(registration)
                        }
                    val pending = state.pending.filter { it.endpointBinder === endpointBinder }
                    state.pending.removeAll(pending.toSet())
                    addAll(pending)
                }.also { registrations ->
                    registrations.forEach { registration ->
                        registration.dead = true
                        registration.deactivateLocked()
                    }
                    cleanupStateLocked(key, state)
                }
            }
        }
        removed.forEach(::unlink)
        callbacks.forEach { (callback, identity) -> callback(identity) }
        return removed.size
    }

    fun revokeInstance(instanceId: String): Int {
        if (instanceId.isBlank()) return 0
        val removed = synchronized(this) {
            states.entries
                .filter { (key, _) -> key.instanceId == instanceId }
                .flatMap { (key, state) ->
                    buildList {
                        state.active?.let(::add)
                        addAll(state.pending)
                    }.also { registrations ->
                        registrations.forEach(Registration::deactivateLocked)
                        states.remove(key)
                    }
                }
        }
        removed.forEach(::unlink)
        return removed.size
    }

    internal fun activeCount(): Int = synchronized(this) {
        states.values.count { it.active != null }
    }

    private fun queryFailureReason(identity: EngineProviderProcessEndpointIdentity): String = synchronized(this) {
        val state = states[identity.key] ?: return@synchronized "endpoint_not_found"
        val generation = state.generation ?: return@synchronized "endpoint_not_live"
        when {
            identity.runtimeEpoch < generation.runtimeEpoch -> "endpoint_generation_stale"
            identity.runtimeEpoch > generation.runtimeEpoch -> "endpoint_generation_not_registered"
            identity != generation -> "endpoint_generation_identity_mismatch"
            else -> "endpoint_not_live"
        }
    }

    private fun registrationConflictLocked(
        state: EndpointState,
        candidate: EngineProviderProcessEndpointIdentity
    ): String? {
        val identities = buildList {
            state.generation?.let(::add)
            state.active?.identity?.let(::add)
            state.pending.mapTo(this) { it.identity }
        }
        val newestEpoch = identities.maxOfOrNull { it.runtimeEpoch } ?: return null
        if (candidate.runtimeEpoch < newestEpoch) return "endpoint_generation_stale"
        if (candidate.runtimeEpoch == newestEpoch) {
            return if (identities.any { it.runtimeEpoch == newestEpoch && it != candidate }) {
                "endpoint_generation_conflict"
            } else {
                "endpoint_generation_replayed"
            }
        }
        if (identities.any { it.engineSessionId == candidate.engineSessionId }) {
            return "endpoint_engine_session_reused"
        }
        return null
    }

    private fun installationConflictLocked(
        state: EndpointState,
        candidate: Registration
    ): String? {
        val otherIdentities = buildList {
            state.generation?.let(::add)
            state.active?.identity?.let(::add)
            state.pending.filterNot { it === candidate }.mapTo(this) { it.identity }
        }
        val newer = otherIdentities.maxByOrNull { it.runtimeEpoch } ?: return null
        return when {
            candidate.identity.runtimeEpoch < newer.runtimeEpoch -> "endpoint_generation_changed_during_registration"
            candidate.identity.runtimeEpoch == newer.runtimeEpoch -> "endpoint_generation_conflict"
            otherIdentities.any { it.engineSessionId == candidate.identity.engineSessionId } -> {
                "endpoint_engine_session_reused"
            }
            else -> null
        }
    }

    private fun handleDeath(key: EngineProviderProcessEndpointKey, registration: Registration) {
        val callback = synchronized(this) {
            registration.dead = true
            val state = states[key]
            state?.pending?.remove(registration)
            val wasActive = state?.active === registration
            if (wasActive) state.active = null
            val callback = registration.onDeath.takeIf { wasActive && !registration.cancelled }
            registration.deactivateLocked()
            if (state != null) cleanupStateLocked(key, state)
            callback
        }
        callback?.invoke(registration.identity)
    }

    private fun cleanupStateLocked(key: EngineProviderProcessEndpointKey, state: EndpointState) {
        if (
            state.active == null && state.pending.isEmpty() && state.generation == null &&
            states[key] === state
        ) {
            states.remove(key)
        }
    }

    private fun isAlive(registration: Registration): Boolean =
        runCatching { registration.endpointBinder.isBinderAlive }.getOrDefault(false)

    private fun unlink(registration: Registration) {
        runCatching { registration.endpointBinder.unlinkToDeath(registration.recipient, 0) }
    }

    private fun accepted(
        identity: EngineProviderProcessEndpointIdentity,
        idempotent: Boolean,
        replacedGeneration: Boolean
    ) = EngineProviderProcessEndpointRegistrationResult(
        accepted = true,
        idempotent = idempotent,
        replacedGeneration = replacedGeneration,
        identity = identity,
        reason = if (idempotent) "endpoint_already_registered" else "endpoint_registered"
    )

    private fun rejected(
        identity: EngineProviderProcessEndpointIdentity,
        reason: String
    ) = EngineProviderProcessEndpointRegistrationResult(
        accepted = false,
        idempotent = false,
        replacedGeneration = false,
        identity = identity,
        reason = reason
    )

    private fun queryRejected(
        identity: EngineProviderProcessEndpointIdentity,
        reason: String
    ) = EngineProviderProcessEndpointQueryResult(
        found = false,
        identity = identity,
        endpointBinder = null,
        reason = reason
    )

    private class EndpointState {
        var active: Registration? = null
        var generation: EngineProviderProcessEndpointIdentity? = null
        val pending = linkedSetOf<Registration>()
    }

    private class Registration(
        val identity: EngineProviderProcessEndpointIdentity,
        val endpointBinder: IBinder,
        val recipient: IBinder.DeathRecipient,
        var onDeath: ((EngineProviderProcessEndpointIdentity) -> Unit)?
    ) {
        var dead: Boolean = false
        var cancelled: Boolean = false

        fun deactivateLocked() {
            cancelled = true
            onDeath = null
        }
    }

    private data class Reservation(
        val accepted: Boolean,
        val reason: String
    )
}

private fun EngineProviderProcessEndpointIdentity.matchesGeneration(
    runtimeEpoch: Long,
    engineSessionId: String
): Boolean = this.runtimeEpoch == runtimeEpoch && this.engineSessionId == engineSessionId

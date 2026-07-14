package com.multiapp.core.engine

import android.os.IBinder
import java.util.concurrent.CountDownLatch

data class EngineComponentProcessClientKey(
    val instanceId: String,
    val effectiveGuestProcessName: String
) {
    init {
        validateComponentProcessText("instanceId", instanceId)
        validateComponentProcessText("effectiveGuestProcessName", effectiveGuestProcessName)
    }
}

/** Complete engine-owned identity for one custom component process. */
data class EngineComponentProcessClientIdentity(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processEpoch: Long,
    val clientSessionId: String,
    val effectiveGuestProcessName: String,
    val processSlot: String,
    val processId: Int,
    val processStartTicks: Long
) {
    val key: EngineComponentProcessClientKey
        get() = EngineComponentProcessClientKey(instanceId, effectiveGuestProcessName)

    init {
        EngineComponentProcessClientKey(instanceId, effectiveGuestProcessName)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateComponentProcessText("engineSessionId", engineSessionId)
        require(processEpoch > 0L) { "processEpoch must be positive" }
        validateComponentProcessText("clientSessionId", clientSessionId)
        validateComponentProcessText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        require(processStartTicks > 0L) { "processStartTicks must be positive" }
    }
}

data class EngineComponentProcessClientAttachResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val replacedGeneration: Boolean,
    val identity: EngineComponentProcessClientIdentity?,
    val reason: String
)

data class EngineComponentProcessClientQueryResult(
    val found: Boolean,
    val identity: EngineComponentProcessClientIdentity?,
    val clientToken: IBinder?,
    val reason: String
)

/**
 * Engine-owned live-client authority for manifest custom component processes.
 *
 * Runtime generation is instance-wide, while the live client key also includes
 * the effective guest process name. Generation tombstones deliberately survive
 * Binder death and explicit removal so a dead client cannot replay its identity.
 */
class EngineComponentProcessClientRegistry(
    private val processIdentityProbe: EngineComponentProcessIdentityProbe? =
        EngineComponentProcessIdentityProbe.PLATFORM_DEFAULT
) {
    private val states = linkedMapOf<EngineComponentProcessClientKey, ClientState>()
    private val instanceGenerations = linkedMapOf<String, InstanceGeneration>()
    private val activeByPid = linkedMapOf<Int, Registration>()
    private val activeBySlot = linkedMapOf<String, Registration>()

    fun attach(
        identity: EngineComponentProcessClientIdentity,
        clientToken: IBinder,
        onAuthorityDeath: (EngineComponentProcessClientIdentity) -> Unit = {}
    ): EngineComponentProcessClientAttachResult {
        while (true) {
            val active = synchronized(this) {
                states[identity.key]?.active?.takeIf { registration ->
                    registration.identity == identity &&
                        registration.clientToken === clientToken &&
                        !registration.cancelled &&
                        isCurrentGenerationLocked(registration.identity)
                }
            }
            if (active != null) {
                if (!isAlive(active)) {
                    handleNotLive(identity.key, active)
                    continue
                }
                val stillActive = synchronized(this) { isActiveLocked(active) }
                if (stillActive) {
                    return attachAccepted(identity, idempotent = true, replacedGeneration = false)
                }
                continue
            }

            lateinit var candidate: Registration
            val recipient = IBinder.DeathRecipient { handleDeath(identity.key, candidate) }
            candidate = Registration(identity, clientToken, recipient, onAuthorityDeath)

            val reservation = synchronized(this) {
                val state = states.getOrPut(identity.key, ::ClientState)
                val current = state.active?.takeIf { registration ->
                    registration.identity == identity &&
                        registration.clientToken === clientToken &&
                        !registration.cancelled
                }
                val pending = state.pending.firstOrNull { registration ->
                    registration.identity == identity &&
                        registration.clientToken === clientToken &&
                        !registration.cancelled
                }
                val conflict = when {
                    current != null || pending != null -> null
                    else -> instanceGenerationConflictLocked(identity, excluding = null)
                        ?: keyGenerationConflictLocked(state, identity, excluding = null)
                        ?: bindingConflictLocked(identity, excluding = null)
                }
                when {
                    current != null -> Reservation(existing = current, waitForExisting = false)
                    pending != null -> Reservation(existing = pending, waitForExisting = true)
                    conflict != null -> {
                        cleanupStateLocked(identity.key, state)
                        Reservation(reason = conflict)
                    }
                    else -> {
                        state.pending += candidate
                        Reservation(reserved = true)
                    }
                }
            }

            val existingReservation = reservation.existing
            if (existingReservation != null) {
                if (reservation.waitForExisting && !awaitCompletion(existingReservation)) {
                    return attachRejected(identity, "component_process_attach_interrupted")
                }
                continue
            }
            if (!reservation.reserved) {
                return attachRejected(identity, checkNotNull(reservation.reason))
            }

            val linkedAndAlive = runCatching {
                clientToken.linkToDeath(recipient, 0)
                clientToken.isBinderAlive
            }.getOrDefault(false)
            if (!linkedAndAlive) {
                synchronized(this) {
                    states[identity.key]?.let { state ->
                        state.pending.remove(candidate)
                        candidate.deactivateLocked()
                        cleanupStateLocked(identity.key, state)
                    } ?: candidate.deactivateLocked()
                }
                unlink(candidate)
                return attachRejected(identity, "component_process_client_token_not_alive")
            }

            val installation = synchronized(this) {
                val state = states[identity.key]
                when {
                    state == null || candidate !in state.pending -> {
                        candidate.deactivateLocked()
                        Installation(reason = "component_process_attach_cancelled")
                    }
                    candidate.dead || candidate.cancelled -> {
                        state.pending.remove(candidate)
                        candidate.deactivateLocked()
                        cleanupStateLocked(identity.key, state)
                        Installation(reason = "component_process_client_died_during_attach")
                    }
                    else -> {
                        val conflict = instanceGenerationConflictLocked(identity, candidate)
                            ?: keyGenerationConflictLocked(state, identity, candidate)
                            ?: bindingConflictLocked(identity, candidate)
                        if (conflict != null) {
                            state.pending.remove(candidate)
                            candidate.deactivateLocked()
                            cleanupStateLocked(identity.key, state)
                            Installation(reason = conflict)
                        } else {
                            val replacedGeneration = state.generation?.let { previous ->
                                identity.runtimeEpoch > previous.runtimeEpoch ||
                                    identity.processEpoch > previous.processEpoch
                            } == true
                            val displaced = advanceInstanceGenerationLocked(identity, candidate)
                            state.pending.remove(candidate)
                            state.active?.let { previous ->
                                removeActiveIndexesLocked(previous)
                                previous.deactivateLocked()
                                displaced += previous
                            }
                            state.active = candidate
                            state.generation = identity
                            instanceGenerations.putIfAbsent(
                                identity.instanceId,
                                InstanceGeneration(
                                    runtimeEpoch = identity.runtimeEpoch,
                                    engineSessionId = identity.engineSessionId
                                )
                            )
                            activeByPid[identity.processId] = candidate
                            activeBySlot[identity.processSlot] = candidate
                            candidate.completeLocked()
                            Installation(
                                installed = true,
                                replacedGeneration = replacedGeneration,
                                displaced = displaced
                            )
                        }
                    }
                }
            }
            installation.displaced.forEach(::unlink)
            if (!installation.installed) {
                unlink(candidate)
                return attachRejected(identity, checkNotNull(installation.reason))
            }
            return attachAccepted(
                identity = identity,
                idempotent = false,
                replacedGeneration = installation.replacedGeneration
            )
        }
    }

    fun queryByKey(
        key: EngineComponentProcessClientKey
    ): EngineComponentProcessClientQueryResult {
        val registration = synchronized(this) {
            states[key]?.active?.takeIf { active ->
                !active.cancelled && isCurrentGenerationLocked(active.identity)
            }
        } ?: return queryRejected(queryFailureReason(key))
        return queryLive(registration) {
            states[key]?.active === registration && isActiveLocked(registration)
        }
    }

    fun queryByKey(
        instanceId: String,
        effectiveGuestProcessName: String
    ): EngineComponentProcessClientQueryResult = queryByKey(
        EngineComponentProcessClientKey(instanceId, effectiveGuestProcessName)
    )

    fun queryByPid(processId: Int): EngineComponentProcessClientQueryResult {
        if (processId <= 0) return queryRejected("component_process_pid_invalid")
        val registration = synchronized(this) {
            activeByPid[processId]?.takeIf { active ->
                !active.cancelled && isCurrentGenerationLocked(active.identity)
            }
        } ?: return queryRejected("component_process_client_not_found")
        return queryLive(registration) {
            activeByPid[processId] === registration && isActiveLocked(registration)
        }
    }

    fun isAuthoritative(
        identity: EngineComponentProcessClientIdentity,
        clientToken: IBinder? = null
    ): Boolean {
        val registration = synchronized(this) {
            states[identity.key]?.active?.takeIf { active ->
                active.identity == identity &&
                    !active.cancelled &&
                    (clientToken == null || active.clientToken === clientToken) &&
                    isActiveLocked(active)
            }
        } ?: return false
        if (!isAlive(registration)) {
            handleNotLive(identity.key, registration)
            return false
        }
        return synchronized(this) {
            registration.identity == identity &&
                (clientToken == null || registration.clientToken === clientToken) &&
                isActiveLocked(registration)
        }
    }

    /** Removes an exact attachment while retaining its generation tombstone. */
    fun remove(
        identity: EngineComponentProcessClientIdentity,
        clientToken: IBinder
    ): Boolean {
        val removed = synchronized(this) {
            val state = states[identity.key] ?: return@synchronized emptyList()
            buildList {
                state.active
                    ?.takeIf { it.identity == identity && it.clientToken === clientToken }
                    ?.let { registration ->
                        state.active = null
                        removeActiveIndexesLocked(registration)
                        add(registration)
                    }
                val pending = state.pending.filter { registration ->
                    registration.identity == identity && registration.clientToken === clientToken
                }
                state.pending.removeAll(pending.toSet())
                addAll(pending)
            }.also { registrations ->
                registrations.forEach(Registration::deactivateLocked)
                cleanupStateLocked(identity.key, state)
            }
        }
        removed.forEach(::unlink)
        return removed.isNotEmpty()
    }

    /** Revokes one instance generation and keeps a fence against late attach replay. */
    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Int {
        if (
            instanceId.isBlank() || instanceId != instanceId.trim() || runtimeEpoch <= 0L ||
            engineSessionId.isBlank() || engineSessionId != engineSessionId.trim()
        ) {
            return 0
        }
        val removed = synchronized(this) {
            val current = instanceGenerations[instanceId]
            val advancesGeneration = current == null || runtimeEpoch > current.runtimeEpoch
            val revokesCurrent = current != null &&
                runtimeEpoch == current.runtimeEpoch &&
                engineSessionId == current.engineSessionId
            if (advancesGeneration) {
                instanceGenerations[instanceId] = InstanceGeneration(
                    runtimeEpoch = runtimeEpoch,
                    engineSessionId = engineSessionId,
                    revoked = true
                )
            } else if (revokesCurrent) {
                current.revoked = true
            }

            states.entries.toList().flatMap { (key, state) ->
                if (key.instanceId != instanceId) return@flatMap emptyList()
                buildList {
                    state.active
                        ?.takeIf { registration ->
                            shouldRevoke(
                                registration.identity,
                                runtimeEpoch,
                                engineSessionId,
                                advancesGeneration
                            )
                        }
                        ?.let { registration ->
                            state.active = null
                            removeActiveIndexesLocked(registration)
                            add(registration)
                        }
                    val pending = state.pending.filter { registration ->
                        shouldRevoke(
                            registration.identity,
                            runtimeEpoch,
                            engineSessionId,
                            advancesGeneration
                        )
                    }
                    state.pending.removeAll(pending.toSet())
                    addAll(pending)
                }.also { registrations ->
                    registrations.maxByOrNull { it.identity.runtimeEpoch }?.identity?.let { revoked ->
                        val generation = state.generation
                        if (generation == null || revoked.runtimeEpoch > generation.runtimeEpoch) {
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

    /** Removes all live clients, pending attaches, tombstones, and generation fences. */
    fun revokeInstance(instanceId: String): Int {
        if (instanceId.isBlank() || instanceId != instanceId.trim()) return 0
        val removed = synchronized(this) {
            val registrations = states.entries
                .filter { (key, _) -> key.instanceId == instanceId }
                .flatMap { (key, state) ->
                    buildList {
                        state.active?.let { registration ->
                            removeActiveIndexesLocked(registration)
                            add(registration)
                        }
                        addAll(state.pending)
                    }.also { clients ->
                        clients.forEach(Registration::deactivateLocked)
                        states.remove(key)
                    }
                }
            instanceGenerations.remove(instanceId)
            registrations
        }
        removed.forEach(::unlink)
        return removed.size
    }

    internal fun activeCount(): Int = synchronized(this) { activeByPid.size }

    private fun queryLive(
        registration: Registration,
        stillCurrent: () -> Boolean
    ): EngineComponentProcessClientQueryResult {
        if (!isAlive(registration)) {
            handleNotLive(registration.identity.key, registration)
            return queryRejected("component_process_client_not_live")
        }
        return synchronized(this) {
            if (!stillCurrent()) {
                queryRejected("component_process_authority_changed")
            } else {
                EngineComponentProcessClientQueryResult(
                    found = true,
                    identity = registration.identity,
                    clientToken = registration.clientToken,
                    reason = "authoritative_component_process_client_found"
                )
            }
        }
    }

    private fun queryFailureReason(key: EngineComponentProcessClientKey): String = synchronized(this) {
        when {
            states[key]?.generation != null -> "component_process_client_not_live"
            instanceGenerations[key.instanceId]?.revoked == true -> "component_process_generation_revoked"
            else -> "component_process_client_not_found"
        }
    }

    private fun instanceGenerationConflictLocked(
        candidate: EngineComponentProcessClientIdentity,
        excluding: Registration?
    ): String? {
        val identities = buildList {
            instanceGenerations[candidate.instanceId]?.let { generation ->
                add(generation.asIdentityMarker(candidate))
            }
            states.values.forEach { state ->
                state.generation
                    ?.takeIf { it.instanceId == candidate.instanceId }
                    ?.let(::add)
                state.active
                    ?.takeIf { it !== excluding && it.identity.instanceId == candidate.instanceId }
                    ?.identity
                    ?.let(::add)
                state.pending
                    .asSequence()
                    .filter { it !== excluding && it.identity.instanceId == candidate.instanceId }
                    .mapTo(this) { it.identity }
            }
        }
        val newestEpoch = identities.maxOfOrNull { it.runtimeEpoch } ?: return null
        if (candidate.runtimeEpoch < newestEpoch) return "component_process_generation_stale"
        if (candidate.runtimeEpoch == newestEpoch) {
            if (
                identities.any {
                    it.runtimeEpoch == newestEpoch &&
                        it.engineSessionId != candidate.engineSessionId
                }
            ) {
                return "component_process_generation_conflict"
            }
            val fence = instanceGenerations[candidate.instanceId]
            if (
                fence?.revoked == true &&
                fence.runtimeEpoch == candidate.runtimeEpoch &&
                fence.engineSessionId == candidate.engineSessionId
            ) {
                return "component_process_generation_revoked"
            }
            return null
        }
        if (identities.any { it.engineSessionId == candidate.engineSessionId }) {
            return "component_process_engine_session_reused"
        }
        return null
    }

    private fun keyGenerationConflictLocked(
        state: ClientState,
        candidate: EngineComponentProcessClientIdentity,
        excluding: Registration?
    ): String? {
        val identities = buildList {
            state.generation?.let(::add)
            state.active?.takeIf { it !== excluding }?.identity?.let(::add)
            state.pending
                .asSequence()
                .filter { it !== excluding }
                .mapTo(this) { it.identity }
        }.filter { identity ->
            identity.runtimeEpoch == candidate.runtimeEpoch &&
                identity.engineSessionId == candidate.engineSessionId
        }
        val newestEpoch = identities.maxOfOrNull { it.processEpoch } ?: return null
        return when {
            candidate.processEpoch < newestEpoch -> "component_process_generation_stale"
            candidate.processEpoch == newestEpoch && identities.any { it == candidate } -> {
                "component_process_generation_replayed"
            }
            candidate.processEpoch == newestEpoch -> "component_process_generation_conflict"
            identities.any { it.clientSessionId == candidate.clientSessionId } -> {
                "component_process_client_session_reused"
            }
            else -> null
        }
    }

    private fun bindingConflictLocked(
        candidate: EngineComponentProcessClientIdentity,
        excluding: Registration?
    ): String? {
        states.forEach { (key, state) ->
            if (key == candidate.key) return@forEach
            val liveBindings = buildList {
                state.active?.takeIf { it !== excluding }?.let(::add)
                state.pending.filterTo(this) { it !== excluding }
            }
            liveBindings.forEach { registration ->
                val identity = registration.identity
                val displacedByCandidate = identity.instanceId == candidate.instanceId &&
                    identity.runtimeEpoch < candidate.runtimeEpoch
                if (!displacedByCandidate) {
                    if (identity.processSlot == candidate.processSlot) {
                        return "component_process_slot_conflict"
                    }
                    if (identity.processId == candidate.processId) {
                        return "component_process_pid_conflict"
                    }
                }
            }
            state.generation?.let { tombstone ->
                val sameInstanceGeneration = tombstone.instanceId == candidate.instanceId &&
                    tombstone.runtimeEpoch == candidate.runtimeEpoch &&
                    tombstone.engineSessionId == candidate.engineSessionId
                if (sameInstanceGeneration) {
                    if (tombstone.processSlot == candidate.processSlot) {
                        return "component_process_slot_conflict"
                    }
                    if (tombstone.processId == candidate.processId) {
                        return "component_process_pid_conflict"
                    }
                }
            }
        }
        return null
    }

    private fun advanceInstanceGenerationLocked(
        identity: EngineComponentProcessClientIdentity,
        candidate: Registration
    ): MutableList<Registration> {
        val current = instanceGenerations[identity.instanceId]
        if (current != null && identity.runtimeEpoch <= current.runtimeEpoch) {
            return mutableListOf()
        }
        instanceGenerations[identity.instanceId] = InstanceGeneration(
            runtimeEpoch = identity.runtimeEpoch,
            engineSessionId = identity.engineSessionId
        )
        val displaced = mutableListOf<Registration>()
        states.entries.toList().forEach { (key, state) ->
            if (key.instanceId != identity.instanceId) return@forEach
            state.active
                ?.takeIf { registration -> registration.identity.runtimeEpoch < identity.runtimeEpoch }
                ?.let { registration ->
                    state.active = null
                    removeActiveIndexesLocked(registration)
                    registration.deactivateLocked()
                    displaced += registration
                }
            val pending = state.pending.filter { registration ->
                registration !== candidate && registration.identity.runtimeEpoch < identity.runtimeEpoch
            }
            state.pending.removeAll(pending.toSet())
            pending.forEach(Registration::deactivateLocked)
            displaced += pending
            cleanupStateLocked(key, state)
        }
        return displaced
    }

    private fun handleDeath(
        key: EngineComponentProcessClientKey,
        registration: Registration
    ) = retireRegistration(key, registration, unlinkRecipient = false)

    private fun handleNotLive(
        key: EngineComponentProcessClientKey,
        registration: Registration
    ) = retireRegistration(key, registration, unlinkRecipient = true)

    private fun retireRegistration(
        key: EngineComponentProcessClientKey,
        registration: Registration,
        unlinkRecipient: Boolean
    ) {
        var shouldUnlink = false
        val callback = synchronized(this) {
            registration.dead = true
            val state = states[key]
            val wasPending = state?.pending?.remove(registration) == true
            val wasActive = state?.active === registration
            if (wasActive) {
                state.active = null
                removeActiveIndexesLocked(registration)
            }
            shouldUnlink = unlinkRecipient && (wasPending || wasActive)
            val callback = registration.onAuthorityDeath
                ?.takeIf { wasActive && !registration.cancelled }
            registration.deactivateLocked()
            if (state != null) cleanupStateLocked(key, state)
            callback
        }
        if (shouldUnlink) unlink(registration)
        callback?.let { onDeath ->
            runCatching { onDeath(registration.identity) }
        }
    }

    private fun isActiveLocked(registration: Registration): Boolean =
        states[registration.identity.key]?.active === registration &&
            activeByPid[registration.identity.processId] === registration &&
            activeBySlot[registration.identity.processSlot] === registration &&
            !registration.cancelled &&
            isCurrentGenerationLocked(registration.identity)

    private fun isCurrentGenerationLocked(identity: EngineComponentProcessClientIdentity): Boolean {
        val generation = instanceGenerations[identity.instanceId] ?: return false
        return !generation.revoked &&
            generation.runtimeEpoch == identity.runtimeEpoch &&
            generation.engineSessionId == identity.engineSessionId
    }

    private fun removeActiveIndexesLocked(registration: Registration) {
        if (activeByPid[registration.identity.processId] === registration) {
            activeByPid.remove(registration.identity.processId)
        }
        if (activeBySlot[registration.identity.processSlot] === registration) {
            activeBySlot.remove(registration.identity.processSlot)
        }
    }

    private fun cleanupStateLocked(
        key: EngineComponentProcessClientKey,
        state: ClientState
    ) {
        if (
            state.active == null && state.pending.isEmpty() && state.generation == null &&
            states[key] === state
        ) {
            states.remove(key)
        }
    }

    private fun isAlive(registration: Registration): Boolean {
        if (!runCatching { registration.clientToken.isBinderAlive }.getOrDefault(false)) {
            return false
        }
        val probe = processIdentityProbe ?: return true
        val observed = runCatching {
            probe.read(registration.identity.processId)
        }.getOrNull() ?: return false
        return observed.processName == registration.identity.processSlot &&
            observed.processStartTicks == registration.identity.processStartTicks
    }

    private fun unlink(registration: Registration) {
        runCatching { registration.clientToken.unlinkToDeath(registration.recipient, 0) }
    }

    private fun awaitCompletion(registration: Registration): Boolean = try {
        registration.completion.await()
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private class ClientState {
        var active: Registration? = null
        var generation: EngineComponentProcessClientIdentity? = null
        val pending = linkedSetOf<Registration>()
    }

    private class Registration(
        val identity: EngineComponentProcessClientIdentity,
        val clientToken: IBinder,
        val recipient: IBinder.DeathRecipient,
        var onAuthorityDeath: ((EngineComponentProcessClientIdentity) -> Unit)?
    ) {
        val completion = CountDownLatch(1)
        var dead: Boolean = false
        var cancelled: Boolean = false

        fun completeLocked() {
            completion.countDown()
        }

        fun deactivateLocked() {
            cancelled = true
            onAuthorityDeath = null
            completion.countDown()
        }
    }

    private data class InstanceGeneration(
        val runtimeEpoch: Long,
        val engineSessionId: String,
        var revoked: Boolean = false
    ) {
        fun asIdentityMarker(
            candidate: EngineComponentProcessClientIdentity
        ): EngineComponentProcessClientIdentity = candidate.copy(
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId
        )
    }

    private data class Reservation(
        val reserved: Boolean = false,
        val existing: Registration? = null,
        val waitForExisting: Boolean = false,
        val reason: String? = null
    )

    private data class Installation(
        val installed: Boolean = false,
        val replacedGeneration: Boolean = false,
        val displaced: List<Registration> = emptyList(),
        val reason: String? = null
    )
}

private fun shouldRevoke(
    identity: EngineComponentProcessClientIdentity,
    runtimeEpoch: Long,
    engineSessionId: String,
    advancesGeneration: Boolean
): Boolean = if (advancesGeneration) {
    identity.runtimeEpoch <= runtimeEpoch
} else {
    identity.runtimeEpoch == runtimeEpoch && identity.engineSessionId == engineSessionId
}

private fun attachAccepted(
    identity: EngineComponentProcessClientIdentity,
    idempotent: Boolean,
    replacedGeneration: Boolean
) = EngineComponentProcessClientAttachResult(
    accepted = true,
    idempotent = idempotent,
    replacedGeneration = replacedGeneration,
    identity = identity,
    reason = if (idempotent) {
        "component_process_client_already_attached"
    } else {
        "component_process_client_attached"
    }
)

private fun attachRejected(
    identity: EngineComponentProcessClientIdentity,
    reason: String
) = EngineComponentProcessClientAttachResult(
    accepted = false,
    idempotent = false,
    replacedGeneration = false,
    identity = identity,
    reason = reason
)

private fun queryRejected(reason: String) = EngineComponentProcessClientQueryResult(
    found = false,
    identity = null,
    clientToken = null,
    reason = reason
)

private fun validateComponentProcessText(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value == value.trim()) { "$name must be normalized" }
    require('\u0000' !in value) { "$name must not contain NUL" }
}

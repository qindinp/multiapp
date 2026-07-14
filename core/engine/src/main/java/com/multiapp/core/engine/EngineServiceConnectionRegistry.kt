package com.multiapp.core.engine

import android.os.IBinder
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch

/**
 * Complete server-owned identity for one Service binding made through an
 * IServiceConnection Binder.
 */
data class EngineServiceConnectionBindingRecord(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int,
    val component: String
) {
    init {
        validateConnectionText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateConnectionText("engineSessionId", engineSessionId)
        validateConnectionText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        validateConnectionText("component", component)
    }
}

typealias EngineServiceConnectionRecord = EngineServiceConnectionBindingRecord
typealias EngineServiceBindingRecord = EngineServiceConnectionBindingRecord

data class EngineServiceConnectionRegistrationResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val replacedGeneration: Boolean,
    val binding: EngineServiceConnectionBindingRecord?,
    val reason: String
) {
    val record: EngineServiceConnectionBindingRecord?
        get() = binding
}

data class EngineServiceConnectionQueryResult(
    val found: Boolean,
    val bindings: List<EngineServiceConnectionBindingRecord>,
    val reason: String
) {
    val records: List<EngineServiceConnectionBindingRecord>
        get() = bindings
}

data class EngineServiceConnectionRemovalResult(
    val removed: Boolean,
    val bindings: List<EngineServiceConnectionBindingRecord>,
    val reason: String
) {
    val records: List<EngineServiceConnectionBindingRecord>
        get() = bindings

    val removedCount: Int
        get() = bindings.size
}

/**
 * Engine-owned IBinder -> Service binding index.
 *
 * One Binder owns one client process identity but may point at several Service
 * components. The aggregate is linked to Binder death once; all of its records
 * are detached in one locked transition before the death callback runs.
 */
class EngineServiceConnectionRegistry {
    private val connections = IdentityHashMap<IBinder, ConnectionState>()
    private val binderTombstones = IdentityHashMap<IBinder, BinderTombstone>()
    private val generations = linkedMapOf<String, Generation>()

    fun register(
        binding: EngineServiceConnectionBindingRecord,
        connectionBinder: IBinder,
        onDeath: (List<EngineServiceConnectionBindingRecord>) -> Unit = {}
    ): EngineServiceConnectionRegistrationResult = registerInternal(
        binding = binding,
        connectionBinder = connectionBinder,
        onDeath = onDeath
    )

    fun bind(
        binding: EngineServiceConnectionBindingRecord,
        connectionBinder: IBinder,
        onDeath: (List<EngineServiceConnectionBindingRecord>) -> Unit = {}
    ): EngineServiceConnectionRegistrationResult = register(
        binding = binding,
        connectionBinder = connectionBinder,
        onDeath = onDeath
    )

    fun bind(
        connectionBinder: IBinder,
        binding: EngineServiceConnectionBindingRecord,
        onDeath: (List<EngineServiceConnectionBindingRecord>) -> Unit = {}
    ): EngineServiceConnectionRegistrationResult = register(
        binding = binding,
        connectionBinder = connectionBinder,
        onDeath = onDeath
    )

    fun query(connectionBinder: IBinder): EngineServiceConnectionQueryResult {
        val state = synchronized(this) {
            connections[connectionBinder]
        }
        if (state == null) {
            return synchronized(this) {
                if (binderTombstones[connectionBinder]?.dead == true) {
                    queryRejected("service_connection_not_live")
                } else {
                    queryRejected("service_connection_not_found")
                }
            }
        }
        val active = synchronized(this) {
            connections[connectionBinder] === state && state.phase == ConnectionPhase.ACTIVE
        }
        if (!active) return queryRejected("service_connection_registration_pending")
        if (!isAlive(state)) {
            handleDeath(state)
            return queryRejected("service_connection_not_live")
        }
        return synchronized(this) {
            if (connections[connectionBinder] !== state || state.phase != ConnectionPhase.ACTIVE) {
                queryRejected(state.failureReason ?: "service_connection_generation_changed")
            } else {
                EngineServiceConnectionQueryResult(
                    found = true,
                    bindings = state.records.sortedBindings(),
                    reason = "authoritative_service_connection_found"
                )
            }
        }
    }

    fun query(
        connectionBinder: IBinder,
        binding: EngineServiceConnectionBindingRecord
    ): EngineServiceConnectionQueryResult {
        val conflict = synchronized(this) {
            connections[connectionBinder]?.owner?.conflictWith(binding.owner())
                ?: binderTombstones[connectionBinder]?.owner?.conflictWith(binding.owner())
        }
        if (conflict != null) return queryRejected(conflict)
        val queried = query(connectionBinder)
        if (!queried.found) return queried
        return if (binding in queried.bindings) {
            EngineServiceConnectionQueryResult(
                found = true,
                bindings = listOf(binding),
                reason = "authoritative_service_connection_binding_found"
            )
        } else {
            queryRejected("service_connection_binding_not_found")
        }
    }

    fun query(
        binding: EngineServiceConnectionBindingRecord,
        connectionBinder: IBinder
    ): EngineServiceConnectionQueryResult = query(connectionBinder, binding)

    fun unbind(
        connectionBinder: IBinder,
        binding: EngineServiceConnectionBindingRecord
    ): EngineServiceConnectionRemovalResult {
        var unlink: ConnectionState? = null
        val result = synchronized(this) {
            val state = connections[connectionBinder]
                ?: return@synchronized removalRejected(
                    binderConflictReasonLocked(connectionBinder, binding.owner())
                        ?: "service_connection_binding_not_found"
                )
            state.owner.conflictWith(binding.owner())?.let {
                return@synchronized removalRejected(it)
            }
            if (!state.records.remove(binding)) {
                return@synchronized removalRejected("service_connection_binding_not_found")
            }
            if (state.records.isEmpty()) {
                val detached = detachStateLocked(
                    state = state,
                    reason = "service_connection_unbound",
                    dead = false,
                    unlinkActive = true
                )
                unlink = detached.unlink
            }
            EngineServiceConnectionRemovalResult(
                removed = true,
                bindings = listOf(binding),
                reason = "service_connection_unbound"
            )
        }
        unlink?.let(::unlink)
        return result
    }

    fun unbind(
        binding: EngineServiceConnectionBindingRecord,
        connectionBinder: IBinder
    ): EngineServiceConnectionRemovalResult = unbind(connectionBinder, binding)

    fun unbind(connectionBinder: IBinder): EngineServiceConnectionRemovalResult =
        removeAll(connectionBinder)

    fun unregister(
        binding: EngineServiceConnectionBindingRecord,
        connectionBinder: IBinder
    ): EngineServiceConnectionRemovalResult = unbind(connectionBinder, binding)

    fun removeAll(connectionBinder: IBinder): EngineServiceConnectionRemovalResult {
        val detached = synchronized(this) {
            val state = connections[connectionBinder]
                ?: return@synchronized null
            detachStateLocked(
                state = state,
                reason = "service_connection_bindings_removed",
                dead = false,
                unlinkActive = true
            )
        } ?: return removalRejected("service_connection_not_found")
        detached.unlink?.let(::unlink)
        return EngineServiceConnectionRemovalResult(
            removed = true,
            bindings = detached.bindings,
            reason = "service_connection_bindings_removed"
        )
    }

    /** Revokes live records and retains a fail-closed generation tombstone. */
    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Int {
        validateConnectionText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateConnectionText("engineSessionId", engineSessionId)

        val detached = synchronized(this) {
            val requested = Generation(runtimeEpoch, engineSessionId, revoked = true)
            val current = generations[instanceId]
            when {
                current == null || runtimeEpoch > current.runtimeEpoch -> {
                    generations[instanceId] = requested
                }
                runtimeEpoch == current.runtimeEpoch && engineSessionId == current.engineSessionId -> {
                    current.revoked = true
                }
                else -> return@synchronized emptyList()
            }

            connections.values.toList()
                .filter { state ->
                    state.owner.instanceId == instanceId &&
                        state.owner.runtimeEpoch <= runtimeEpoch
                }
                .map { state ->
                    detachStateLocked(
                        state = state,
                        reason = "service_connection_generation_revoked",
                        dead = false,
                        unlinkActive = true,
                        notifyDeath = true
                    )
                }
        }
        detached.forEach(::finishDetached)
        return detached.sumOf { it.bindings.size }
    }

    fun revokeInstance(instanceId: String): Int {
        validateConnectionText("instanceId", instanceId)
        val detached = synchronized(this) {
            val removed = connections.values.toList()
                .filter { it.owner.instanceId == instanceId }
                .map { state ->
                    detachStateLocked(
                        state = state,
                        reason = "service_connection_instance_revoked",
                        dead = false,
                        unlinkActive = true,
                        notifyDeath = true
                    )
                }
            generations.remove(instanceId)
            binderTombstones.entries.removeAll { (_, tombstone) ->
                tombstone.owner.instanceId == instanceId
            }
            removed
        }
        detached.forEach(::finishDetached)
        return detached.sumOf { it.bindings.size }
    }

    fun revokeProcess(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int
    ): Int {
        val owner = ConnectionOwner(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            processId = processId
        )
        validateConnectionText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateConnectionText("engineSessionId", engineSessionId)
        validateConnectionText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        val detached = synchronized(this) {
            connections.values.toList()
                .filter { state -> state.owner == owner }
                .map { state ->
                    detachStateLocked(
                        state = state,
                        reason = "service_connection_process_revoked",
                        dead = false,
                        unlinkActive = true,
                        notifyDeath = true
                    )
                }
        }
        detached.forEach(::finishDetached)
        return detached.sumOf { it.bindings.size }
    }

    /**
     * Explicit death entry for a control plane that already aggregates process
     * death. The automatic DeathRecipient remains the normal cleanup path.
     */
    fun handleBinderDeath(connectionBinder: IBinder): Int {
        val detached = synchronized(this) {
            val state = connections[connectionBinder]
                ?: return@synchronized null
            detachStateLocked(
                state = state,
                reason = "service_connection_binder_died",
                dead = true,
                unlinkActive = true,
                notifyDeath = true
            )
        } ?: return 0
        finishDetached(detached)
        return detached.bindings.size
    }

    internal fun activeConnectionCount(): Int = synchronized(this) {
        connections.values.count { it.phase == ConnectionPhase.ACTIVE }
    }

    internal fun activeBindingCount(): Int = synchronized(this) {
        connections.values
            .filter { it.phase == ConnectionPhase.ACTIVE }
            .sumOf { it.records.size }
    }

    internal fun activeBindingCount(binding: EngineServiceConnectionBindingRecord): Int = synchronized(this) {
        connections.values
            .asSequence()
            .filter { it.phase == ConnectionPhase.ACTIVE }
            .flatMap { it.records.asSequence() }
            .count { candidate ->
                candidate.instanceId == binding.instanceId &&
                    candidate.runtimeEpoch == binding.runtimeEpoch &&
                    candidate.engineSessionId == binding.engineSessionId &&
                    candidate.component == binding.component
            }
    }

    internal fun tombstoneCount(): Int = synchronized(this) { generations.size }

    private fun registerInternal(
        binding: EngineServiceConnectionBindingRecord,
        connectionBinder: IBinder,
        onDeath: (List<EngineServiceConnectionBindingRecord>) -> Unit
    ): EngineServiceConnectionRegistrationResult {
        val owner = binding.owner()
        var shouldLink = false
        var shouldAwait = false
        var reservedIdempotent = false
        val state = synchronized(this) {
            val current = connections[connectionBinder]
            if (current != null) {
                current.owner.conflictWith(owner)?.let { return rejected(binding, it) }
                when (current.phase) {
                    ConnectionPhase.LINKING -> {
                        reservedIdempotent = !current.records.add(binding)
                        shouldAwait = true
                        current
                    }
                    ConnectionPhase.ACTIVE -> current
                    else -> return rejected(
                        binding,
                        current.failureReason ?: "service_connection_registration_cancelled"
                    )
                }
            } else {
                binderConflictReasonLocked(connectionBinder, owner)?.let {
                    return rejected(binding, it)
                }
                generationConflictLocked(owner)?.let { return rejected(binding, it) }

                lateinit var replacement: ConnectionState
                val recipient = IBinder.DeathRecipient { handleDeath(replacement) }
                replacement = ConnectionState(
                    owner = owner,
                    binder = connectionBinder,
                    recipient = recipient,
                    onDeath = onDeath
                ).also { it.records += binding }
                connections[connectionBinder] = replacement
                shouldLink = true
                replacement
            }
        }

        return when {
            shouldLink -> linkAndInstall(state, binding)
            shouldAwait -> {
                awaitRegistration(state)
                synchronized(this) {
                    if (
                        connections[connectionBinder] === state &&
                        state.phase == ConnectionPhase.ACTIVE &&
                        binding in state.records
                    ) {
                        accepted(binding, reservedIdempotent, state.replacedGeneration)
                    } else {
                        rejected(
                            binding,
                            state.failureReason ?: "service_connection_registration_cancelled"
                        )
                    }
                }
            }
            else -> addToActive(state, binding)
        }
    }

    private fun addToActive(
        state: ConnectionState,
        binding: EngineServiceConnectionBindingRecord
    ): EngineServiceConnectionRegistrationResult {
        if (!isAlive(state)) {
            handleDeath(state)
            return rejected(binding, "service_connection_binder_not_alive")
        }
        return synchronized(this) {
            if (connections[state.binder] !== state || state.phase != ConnectionPhase.ACTIVE) {
                return@synchronized rejected(
                    binding,
                    state.failureReason ?: "service_connection_generation_changed"
                )
            }
            state.owner.conflictWith(binding.owner())?.let {
                return@synchronized rejected(binding, it)
            }
            val idempotent = !state.records.add(binding)
            accepted(binding, idempotent, replacedGeneration = false)
        }
    }

    private fun linkAndInstall(
        state: ConnectionState,
        requestedBinding: EngineServiceConnectionBindingRecord
    ): EngineServiceConnectionRegistrationResult {
        var linked = false
        val alive = runCatching {
            state.binder.linkToDeath(state.recipient, 0)
            linked = true
            state.binder.isBinderAlive
        }.getOrDefault(false)
        if (!alive) {
            synchronized(this) {
                if (connections[state.binder] === state) {
                    connections.remove(state.binder)
                    state.failLocked("service_connection_binder_not_alive")
                    binderTombstones[state.binder] = BinderTombstone(state.owner, dead = true)
                }
            }
            if (linked) unlinkDirect(state)
            return rejected(requestedBinding, "service_connection_binder_not_alive")
        }

        val obsolete = mutableListOf<DetachedState>()
        var requestedBindingInstalled = false
        val installationReason = synchronized(this) {
            if (connections[state.binder] !== state || state.phase != ConnectionPhase.LINKING) {
                return@synchronized state.failureReason
                    ?: "service_connection_registration_cancelled"
            }
            val conflict = generationConflictLocked(state.owner, excluded = state)
            if (conflict != null) {
                connections.remove(state.binder)
                state.failLocked(conflict)
                return@synchronized conflict
            }

            val previousGeneration = generations[state.owner.instanceId]
            connections.values.toList()
                .filter { candidate ->
                    candidate !== state &&
                        candidate.owner.instanceId == state.owner.instanceId &&
                        candidate.owner.runtimeEpoch < state.owner.runtimeEpoch
                }
                .mapTo(obsolete) { candidate ->
                    detachStateLocked(
                        state = candidate,
                        reason = "service_connection_generation_changed",
                        dead = false,
                        unlinkActive = true
                    )
                }

            state.linkEstablished = true
            state.replacedGeneration = previousGeneration != null &&
                state.owner.runtimeEpoch > previousGeneration.runtimeEpoch
            requestedBindingInstalled = requestedBinding in state.records
            state.activateLocked()
            generations[state.owner.instanceId] = Generation(
                runtimeEpoch = state.owner.runtimeEpoch,
                engineSessionId = state.owner.engineSessionId,
                revoked = false
            )
            binderTombstones[state.binder] = BinderTombstone(state.owner, dead = false)
            null
        }
        obsolete.mapNotNull(DetachedState::unlink).forEach(::unlink)
        if (installationReason != null) {
            unlinkDirect(state)
            return rejected(requestedBinding, installationReason)
        }
        return if (requestedBindingInstalled) {
            accepted(requestedBinding, idempotent = false, state.replacedGeneration)
        } else {
            rejected(requestedBinding, "service_connection_registration_cancelled")
        }
    }

    private fun handleDeath(state: ConnectionState) {
        val detached = synchronized(this) {
            if (connections[state.binder] !== state) return@synchronized null
            detachStateLocked(
                state = state,
                reason = "service_connection_binder_died",
                dead = true,
                unlinkActive = false,
                notifyDeath = true
            )
        } ?: return
        runCatching { detached.callback?.invoke(detached.bindings) }
    }

    private fun detachStateLocked(
        state: ConnectionState,
        reason: String,
        dead: Boolean,
        unlinkActive: Boolean,
        notifyDeath: Boolean = false
    ): DetachedState {
        check(connections[state.binder] === state)
        connections.remove(state.binder)
        val wasActive = state.phase == ConnectionPhase.ACTIVE
        val bindings = state.records.sortedBindings()
        state.records.clear()
        val callback = state.onDeath.takeIf { notifyDeath && wasActive }
        state.onDeath = null
        val unlink = state.takeUnlinkLocked().takeIf { unlinkActive }
        state.failLocked(reason, dead)
        val previous = binderTombstones[state.binder]
        binderTombstones[state.binder] = BinderTombstone(
            owner = state.owner,
            dead = dead || previous?.dead == true
        )
        return DetachedState(
            bindings = bindings,
            unlink = if (unlink == true) state else null,
            callback = callback
        )
    }

    private fun generationConflictLocked(
        candidate: ConnectionOwner,
        excluded: ConnectionState? = null
    ): String? {
        val known = buildList {
            generations[candidate.instanceId]?.let(::add)
            connections.values
                .filter { it !== excluded && it.owner.instanceId == candidate.instanceId }
                .mapTo(this) { it.owner.generation() }
        }
        val newestEpoch = known.maxOfOrNull(Generation::runtimeEpoch) ?: return null
        if (candidate.runtimeEpoch < newestEpoch) return "service_connection_generation_stale"
        if (candidate.runtimeEpoch == newestEpoch) {
            val sameEpoch = known.filter { it.runtimeEpoch == newestEpoch }
            if (sameEpoch.any { it.engineSessionId != candidate.engineSessionId }) {
                return "service_connection_generation_conflict"
            }
            if (sameEpoch.any(Generation::revoked)) {
                return "service_connection_generation_revoked"
            }
            return null
        }
        if (known.any { it.engineSessionId == candidate.engineSessionId }) {
            return "service_connection_engine_session_reused"
        }
        return null
    }

    private fun binderConflictReasonLocked(
        binder: IBinder,
        candidate: ConnectionOwner
    ): String? {
        val tombstone = binderTombstones[binder] ?: return null
        tombstone.owner.conflictWith(candidate)?.let { return it }
        return "service_connection_binder_not_alive".takeIf { tombstone.dead }
    }

    private fun awaitRegistration(state: ConnectionState) {
        var interrupted = false
        while (true) {
            try {
                state.registrationComplete.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun isAlive(state: ConnectionState): Boolean =
        runCatching { state.binder.isBinderAlive }.getOrDefault(false)

    private fun unlink(state: ConnectionState) {
        runCatching { state.binder.unlinkToDeath(state.recipient, 0) }
    }

    private fun unlinkDirect(state: ConnectionState) {
        runCatching { state.binder.unlinkToDeath(state.recipient, 0) }
    }

    private fun finishDetached(detached: DetachedState) {
        detached.unlink?.let(::unlink)
        runCatching { detached.callback?.invoke(detached.bindings) }
    }

    private class ConnectionState(
        val owner: ConnectionOwner,
        val binder: IBinder,
        val recipient: IBinder.DeathRecipient,
        var onDeath: ((List<EngineServiceConnectionBindingRecord>) -> Unit)?
    ) {
        val records = linkedSetOf<EngineServiceConnectionBindingRecord>()
        val registrationComplete = CountDownLatch(1)
        var phase: ConnectionPhase = ConnectionPhase.LINKING
        var failureReason: String? = null
        var linkEstablished: Boolean = false
        var replacedGeneration: Boolean = false

        fun activateLocked() {
            phase = ConnectionPhase.ACTIVE
            registrationComplete.countDown()
        }

        fun failLocked(reason: String, dead: Boolean = false) {
            failureReason = reason
            phase = if (dead) ConnectionPhase.DEAD else ConnectionPhase.CANCELLED
            registrationComplete.countDown()
        }

        fun takeUnlinkLocked(): Boolean {
            if (!linkEstablished) return false
            linkEstablished = false
            return true
        }
    }

    private enum class ConnectionPhase {
        LINKING,
        ACTIVE,
        CANCELLED,
        DEAD
    }

    private data class BinderTombstone(
        val owner: ConnectionOwner,
        val dead: Boolean
    )

    private data class DetachedState(
        val bindings: List<EngineServiceConnectionBindingRecord>,
        val unlink: ConnectionState?,
        val callback: ((List<EngineServiceConnectionBindingRecord>) -> Unit)? = null
    )

    private fun accepted(
        binding: EngineServiceConnectionBindingRecord,
        idempotent: Boolean,
        replacedGeneration: Boolean
    ) = EngineServiceConnectionRegistrationResult(
        accepted = true,
        idempotent = idempotent,
        replacedGeneration = replacedGeneration,
        binding = binding,
        reason = if (idempotent) {
            "service_connection_binding_already_registered"
        } else {
            "service_connection_bound"
        }
    )

    private fun rejected(
        binding: EngineServiceConnectionBindingRecord,
        reason: String
    ) = EngineServiceConnectionRegistrationResult(
        accepted = false,
        idempotent = false,
        replacedGeneration = false,
        binding = binding,
        reason = reason
    )

    private fun queryRejected(reason: String) = EngineServiceConnectionQueryResult(
        found = false,
        bindings = emptyList(),
        reason = reason
    )

    private fun removalRejected(reason: String) = EngineServiceConnectionRemovalResult(
        removed = false,
        bindings = emptyList(),
        reason = reason
    )
}

private fun EngineServiceConnectionBindingRecord.owner() =
    ConnectionOwner(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        processId = processId
    )

private data class ConnectionOwner(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int
) {
    fun generation(): Generation = Generation(runtimeEpoch, engineSessionId, revoked = false)

    fun conflictWith(candidate: ConnectionOwner): String? = when {
        instanceId != candidate.instanceId -> "service_connection_instance_mismatch"
        runtimeEpoch != candidate.runtimeEpoch || engineSessionId != candidate.engineSessionId -> {
            "service_connection_generation_mismatch"
        }
        processSlot != candidate.processSlot || processId != candidate.processId -> {
            "service_connection_process_binding_mismatch"
        }
        else -> null
    }
}

private data class Generation(
    val runtimeEpoch: Long,
    val engineSessionId: String,
    var revoked: Boolean
)

private fun Collection<EngineServiceConnectionBindingRecord>.sortedBindings():
    List<EngineServiceConnectionBindingRecord> = sortedWith(
        compareBy(
            EngineServiceConnectionBindingRecord::component,
            EngineServiceConnectionBindingRecord::instanceId,
            EngineServiceConnectionBindingRecord::runtimeEpoch,
            EngineServiceConnectionBindingRecord::processSlot,
            EngineServiceConnectionBindingRecord::processId
        )
    )

private fun validateConnectionText(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value == value.trim()) { "$name must be normalized" }
    require('\u0000' !in value) { "$name must not contain NUL" }
}

package com.multiapp.core.engine

data class EngineComponentProcessSlotGeneration(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String
) {
    init {
        validateComponentProcessSlotText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateComponentProcessSlotText("engineSessionId", engineSessionId)
    }
}

data class EngineComponentProcessSlotKey(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val guestProcessName: String
) {
    val generation: EngineComponentProcessSlotGeneration
        get() = EngineComponentProcessSlotGeneration(instanceId, runtimeEpoch, engineSessionId)

    init {
        EngineComponentProcessSlotGeneration(instanceId, runtimeEpoch, engineSessionId)
        validateComponentProcessSlotText("guestProcessName", guestProcessName)
    }
}

data class EngineComponentProcessSlotAssignment(
    val key: EngineComponentProcessSlotKey,
    val processSlot: String
) {
    val instanceId: String
        get() = key.instanceId
    val runtimeEpoch: Long
        get() = key.runtimeEpoch
    val engineSessionId: String
        get() = key.engineSessionId
    val guestProcessName: String
        get() = key.guestProcessName

    init {
        validateComponentProcessSlotText("processSlot", processSlot)
    }
}

class EngineComponentProcessSlotExhaustedException(
    val key: EngineComponentProcessSlotKey,
    candidateSlots: List<String>
) : IllegalStateException(
    "No free component process slot for instanceId=${key.instanceId}, " +
        "runtimeEpoch=${key.runtimeEpoch}, guestProcessName=${key.guestProcessName}, " +
        "candidateCount=${candidateSlots.size}"
) {
    val candidateSlots: List<String> = candidateSlots.toList()
    val candidateCount: Int = candidateSlots.size
}

/**
 * Engine-owned authority for mapping one guest process in a live runtime generation to one
 * host-declared process slot.
 *
 * Every mutation and both indexes are guarded by the allocator monitor. A newer runtime epoch
 * atomically invalidates the previous generation for that instance; a revoked or stale generation
 * cannot reserve a slot again.
 */
class EngineComponentProcessSlotAllocator {
    private val assignments = linkedMapOf<
        EngineComponentProcessSlotKey,
        EngineComponentProcessSlotAssignment
        >()
    private val ownersByProcessSlot = linkedMapOf<String, EngineComponentProcessSlotKey>()
    private val generations = linkedMapOf<String, GenerationState>()

    /**
     * Allocates the runtime primary slot to the Application process, or atomically reserves the
     * first free declared candidate for a custom guest process.
     *
     * [declaredCandidateSlots] must contain only custom-process candidates. In particular, it must
     * not contain [primaryProcessSlot]; accepting that mix could let a custom process steal the
     * runtime's primary host process.
     */
    @Synchronized
    fun allocate(
        key: EngineComponentProcessSlotKey,
        applicationGuestProcessName: String,
        primaryProcessSlot: String,
        declaredCandidateSlots: List<String>
    ): EngineComponentProcessSlotAssignment {
        val candidates = validatedCandidateSnapshot(
            applicationGuestProcessName = applicationGuestProcessName,
            primaryProcessSlot = primaryProcessSlot,
            declaredCandidateSlots = declaredCandidateSlots
        )
        enterGenerationLocked(
            generation = key.generation,
            applicationGuestProcessName = applicationGuestProcessName,
            primaryProcessSlot = primaryProcessSlot
        )

        val usesPrimarySlot = key.guestProcessName == applicationGuestProcessName
        val eligibleSlots = if (usesPrimarySlot) listOf(primaryProcessSlot) else candidates
        val existing = assignments[key]
        if (existing != null && existing.processSlot in eligibleSlots) {
            check(ownersByProcessSlot[existing.processSlot] == key) {
                "component process slot owner index is inconsistent"
            }
            return existing
        }
        if (existing != null) removeAssignmentLocked(key)

        val processSlot = eligibleSlots.firstOrNull { it !in ownersByProcessSlot }
            ?: throw EngineComponentProcessSlotExhaustedException(key, eligibleSlots)
        val assignment = EngineComponentProcessSlotAssignment(key, processSlot)
        check(assignments.put(key, assignment) == null) {
            "component process slot key was concurrently assigned"
        }
        check(ownersByProcessSlot.put(processSlot, key) == null) {
            "component process slot was concurrently assigned"
        }
        return assignment
    }

    fun allocate(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        guestProcessName: String,
        applicationGuestProcessName: String,
        primaryProcessSlot: String,
        declaredCandidateSlots: List<String>
    ): EngineComponentProcessSlotAssignment = allocate(
        key = EngineComponentProcessSlotKey(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            guestProcessName = guestProcessName
        ),
        applicationGuestProcessName = applicationGuestProcessName,
        primaryProcessSlot = primaryProcessSlot,
        declaredCandidateSlots = declaredCandidateSlots
    )

    @Synchronized
    fun query(key: EngineComponentProcessSlotKey): EngineComponentProcessSlotAssignment? =
        assignments[key]

    fun query(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        guestProcessName: String
    ): EngineComponentProcessSlotAssignment? = query(
        EngineComponentProcessSlotKey(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            guestProcessName = guestProcessName
        )
    )

    @Synchronized
    fun ownerOf(processSlot: String): EngineComponentProcessSlotKey? {
        validateComponentProcessSlotText("processSlot", processSlot)
        return ownersByProcessSlot[processSlot]
    }

    @Synchronized
    fun release(key: EngineComponentProcessSlotKey): Boolean =
        removeAssignmentLocked(key) != null

    /** Releases only if the complete assignment is still authoritative. */
    @Synchronized
    fun release(assignment: EngineComponentProcessSlotAssignment): Boolean {
        if (assignments[assignment.key] != assignment) return false
        return removeAssignmentLocked(assignment.key) != null
    }

    /** Revokes one generation and leaves a tombstone so that generation cannot be replayed. */
    @Synchronized
    fun revokeGeneration(generation: EngineComponentProcessSlotGeneration): Int {
        val current = generations[generation.instanceId]
        return when {
            current == null -> {
                generations[generation.instanceId] = GenerationState(
                    generation = generation,
                    applicationGuestProcessName = null,
                    primaryProcessSlot = null,
                    revoked = true
                )
                removeGenerationAssignmentsLocked(generation)
            }
            generation.runtimeEpoch < current.generation.runtimeEpoch -> {
                removeGenerationAssignmentsLocked(generation)
            }
            generation.runtimeEpoch == current.generation.runtimeEpoch -> {
                check(generation.engineSessionId == current.generation.engineSessionId) {
                    "runtimeEpoch is already bound to another engineSessionId"
                }
                val removed = removeGenerationAssignmentsLocked(generation)
                current.revoked = true
                removed
            }
            else -> {
                check(generation.engineSessionId != current.generation.engineSessionId) {
                    "engineSessionId must change with runtimeEpoch"
                }
                val removed = removeInstanceAssignmentsLocked(generation.instanceId)
                generations[generation.instanceId] = GenerationState(
                    generation = generation,
                    applicationGuestProcessName = null,
                    primaryProcessSlot = null,
                    revoked = true
                )
                removed
            }
        }
    }

    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Int = revokeGeneration(
        EngineComponentProcessSlotGeneration(instanceId, runtimeEpoch, engineSessionId)
    )

    @Synchronized
    fun revokeInstance(instanceId: String): Int {
        validateComponentProcessSlotText("instanceId", instanceId)
        val removed = removeInstanceAssignmentsLocked(instanceId)
        generations[instanceId]?.revoked = true
        return removed
    }

    /**
     * Reconciles one authoritative generation against the current host declaration. Old generation
     * assignments and assignments whose slot disappeared from the declaration are released.
     */
    @Synchronized
    fun reconcile(
        generation: EngineComponentProcessSlotGeneration,
        applicationGuestProcessName: String,
        primaryProcessSlot: String,
        declaredCandidateSlots: List<String>
    ): Int {
        val candidates = validatedCandidateSnapshot(
            applicationGuestProcessName = applicationGuestProcessName,
            primaryProcessSlot = primaryProcessSlot,
            declaredCandidateSlots = declaredCandidateSlots
        )
        var removed = enterGenerationLocked(
            generation = generation,
            applicationGuestProcessName = applicationGuestProcessName,
            primaryProcessSlot = primaryProcessSlot
        )
        val candidateSet = candidates.toSet()
        val invalidKeys = assignments.values
            .asSequence()
            .filter { it.instanceId == generation.instanceId }
            .filter { assignment ->
                assignment.key.generation != generation ||
                    if (assignment.guestProcessName == applicationGuestProcessName) {
                        assignment.processSlot != primaryProcessSlot
                    } else {
                        assignment.processSlot == primaryProcessSlot ||
                            assignment.processSlot !in candidateSet
                    }
            }
            .map { it.key }
            .toList()
        invalidKeys.forEach { key ->
            if (removeAssignmentLocked(key) != null) removed++
        }
        return removed
    }

    fun reconcile(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        applicationGuestProcessName: String,
        primaryProcessSlot: String,
        declaredCandidateSlots: List<String>
    ): Int = reconcile(
        generation = EngineComponentProcessSlotGeneration(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId
        ),
        applicationGuestProcessName = applicationGuestProcessName,
        primaryProcessSlot = primaryProcessSlot,
        declaredCandidateSlots = declaredCandidateSlots
    )

    @Synchronized
    internal fun size(): Int = assignments.size

    private fun enterGenerationLocked(
        generation: EngineComponentProcessSlotGeneration,
        applicationGuestProcessName: String,
        primaryProcessSlot: String
    ): Int {
        val current = generations[generation.instanceId]
        when {
            current == null -> {
                generations[generation.instanceId] = GenerationState(
                    generation = generation,
                    applicationGuestProcessName = applicationGuestProcessName,
                    primaryProcessSlot = primaryProcessSlot
                )
                return 0
            }
            generation.runtimeEpoch < current.generation.runtimeEpoch -> {
                error("cannot allocate a component process slot for a stale runtimeEpoch")
            }
            generation.runtimeEpoch == current.generation.runtimeEpoch -> {
                check(generation.engineSessionId == current.generation.engineSessionId) {
                    "runtimeEpoch is already bound to another engineSessionId"
                }
                check(!current.revoked) {
                    "cannot allocate a component process slot for a revoked runtime generation"
                }
                check(current.applicationGuestProcessName == applicationGuestProcessName) {
                    "applicationGuestProcessName changed without a runtimeEpoch advance"
                }
                check(current.primaryProcessSlot == primaryProcessSlot) {
                    "primaryProcessSlot changed without a runtimeEpoch advance"
                }
                return 0
            }
            else -> {
                check(generation.engineSessionId != current.generation.engineSessionId) {
                    "engineSessionId must change with runtimeEpoch"
                }
                val removed = removeInstanceAssignmentsLocked(generation.instanceId)
                generations[generation.instanceId] = GenerationState(
                    generation = generation,
                    applicationGuestProcessName = applicationGuestProcessName,
                    primaryProcessSlot = primaryProcessSlot
                )
                return removed
            }
        }
    }

    private fun validatedCandidateSnapshot(
        applicationGuestProcessName: String,
        primaryProcessSlot: String,
        declaredCandidateSlots: List<String>
    ): List<String> {
        validateComponentProcessSlotText(
            "applicationGuestProcessName",
            applicationGuestProcessName
        )
        validateComponentProcessSlotText("primaryProcessSlot", primaryProcessSlot)
        val candidates = declaredCandidateSlots.toList()
        candidates.forEachIndexed { index, candidate ->
            validateComponentProcessSlotText("declaredCandidateSlots[$index]", candidate)
        }
        require(candidates.size == candidates.toSet().size) {
            "declaredCandidateSlots must not contain duplicates"
        }
        require(primaryProcessSlot !in candidates) {
            "declaredCandidateSlots must not contain primaryProcessSlot"
        }
        return candidates
    }

    private fun removeGenerationAssignmentsLocked(
        generation: EngineComponentProcessSlotGeneration
    ): Int = removeAssignmentsLocked { key -> key.generation == generation }

    private fun removeInstanceAssignmentsLocked(instanceId: String): Int =
        removeAssignmentsLocked { key -> key.instanceId == instanceId }

    private fun removeAssignmentsLocked(
        predicate: (EngineComponentProcessSlotKey) -> Boolean
    ): Int {
        val keys = assignments.keys.filter(predicate)
        keys.forEach(::removeAssignmentLocked)
        return keys.size
    }

    private fun removeAssignmentLocked(
        key: EngineComponentProcessSlotKey
    ): EngineComponentProcessSlotAssignment? {
        val assignment = assignments.remove(key) ?: return null
        check(ownersByProcessSlot[assignment.processSlot] == key) {
            "component process slot owner index is inconsistent"
        }
        ownersByProcessSlot.remove(assignment.processSlot)
        return assignment
    }

    private data class GenerationState(
        val generation: EngineComponentProcessSlotGeneration,
        val applicationGuestProcessName: String?,
        val primaryProcessSlot: String?,
        var revoked: Boolean = false
    )
}

private fun validateComponentProcessSlotText(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value == value.trim()) { "$name must be trimmed" }
    require(value.length <= MAX_COMPONENT_PROCESS_SLOT_TEXT_LENGTH) {
        "$name must be at most $MAX_COMPONENT_PROCESS_SLOT_TEXT_LENGTH characters"
    }
    require(value.none { character -> character.isWhitespace() }) {
        "$name must not contain whitespace"
    }
    require(value.none { character -> Character.isISOControl(character.code) }) {
        "$name must not contain control characters"
    }
}

private const val MAX_COMPONENT_PROCESS_SLOT_TEXT_LENGTH = 512

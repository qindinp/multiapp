package com.multiapp.core.model.virtual

import java.util.UUID

class ProxyActivityRegistry(
    private val proxyActivityClassNames: List<String>,
    private val launchModeByClassName: Map<String, String?> = emptyMap(),
    private val slotAssignmentStore: ProxyActivitySlotAssignmentStore? = null
) {
    private val records = LinkedHashMap<String, VirtualActivityRecord>()

    init {
        require(proxyActivityClassNames.isNotEmpty()) { "at least one proxy Activity is required" }
        require(proxyActivityClassNames.all { it.isNotBlank() }) { "proxy Activity class names must not be blank" }
    }

    @Synchronized
    fun allocate(
        instanceId: String,
        originPackageName: String,
        guestActivityClassName: String,
        launchMode: String? = null,
        taskKey: String = instanceId,
        taskAffinity: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): VirtualActivityRecord {
        val normalizedLaunchMode = normalizeLaunchMode(launchMode)
        if (!isSupportedLaunchMode(normalizedLaunchMode)) {
            throw UnsupportedVirtualActivityLaunchModeException(requireNotNull(normalizedLaunchMode))
        }
        val proxyClassName = selectProxyActivity(
            launchMode = normalizedLaunchMode,
            instanceId = instanceId,
            taskKey = taskKey
        )
        val record = VirtualActivityRecord(
            token = UUID.randomUUID().toString(),
            instanceId = instanceId,
            originPackageName = originPackageName,
            guestActivityClassName = guestActivityClassName,
            proxyActivityClassName = proxyClassName,
            launchMode = normalizedLaunchMode,
            taskAffinity = taskAffinity,
            createdAtMs = nowMs
        )
        records[record.token] = record
        return record
    }

    @Synchronized
    fun registerExisting(existingRecords: List<VirtualActivityRecord>) {
        existingRecords
            .filter { it.state != VirtualActivityState.FINISHED && it.state != VirtualActivityState.DESTROYED }
            .forEach { record -> records[record.token] = record }
    }

    @Synchronized
    fun resolve(token: String): VirtualActivityRecord? = records[token]

    @Synchronized
    fun consume(token: String): VirtualActivityRecord? = records.remove(token)

    @Synchronized
    fun listRecords(): List<VirtualActivityRecord> = records.values.toList()

    private fun selectProxyActivity(
        launchMode: String?,
        instanceId: String,
        taskKey: String
    ): String {
        val candidates = proxyActivityClassNames.filter { className ->
            normalizeLaunchMode(launchModeByClassName[className]) == launchMode
        }.ifEmpty { proxyActivityClassNames }
        val activeByProxy = records.values.associateBy { it.proxyActivityClassName }
        val assignmentKey = ProxyActivitySlotKey(
            instanceId = instanceId,
            launchMode = launchMode,
            taskKey = taskKey
        )
        val orderedCandidates = orderedCandidates(candidates, taskKey)
        val activeAvailableCandidates = orderedCandidates.filter { candidate ->
            isProxyActiveSlotAvailableFor(candidate, assignmentKey, activeByProxy)
        }
        val selected = if (slotAssignmentStore != null) {
            slotAssignmentStore.reserve(assignmentKey, activeAvailableCandidates)
        } else {
            activeAvailableCandidates.firstOrNull()
        } ?: throw ProxyActivitySlotExhaustedException(
            instanceId = instanceId,
            launchMode = launchMode,
            taskKey = taskKey,
            candidateCount = candidates.size
        )
        return selected
    }

    private fun isProxyActiveSlotAvailableFor(
        proxyActivityClassName: String,
        assignmentKey: ProxyActivitySlotKey,
        activeByProxy: Map<String, VirtualActivityRecord>
    ): Boolean {
        val active = activeByProxy[proxyActivityClassName]
        return active == null || active.matchesSlotOwner(assignmentKey)
    }

    private fun VirtualActivityRecord.matchesSlotOwner(key: ProxyActivitySlotKey): Boolean =
        instanceId == key.instanceId &&
            normalizeLaunchMode(launchMode) == key.launchMode &&
            taskAffinity == key.taskKey

    companion object {
        fun normalizeLaunchMode(launchMode: String?): String? {
            val normalized = launchMode?.trim()
            return when (normalized) {
                null, "", "standard" -> null
                else -> normalized
            }
        }

        fun isSupportedLaunchMode(launchMode: String?): Boolean =
            normalizeLaunchMode(launchMode) in setOf(null, "singleTop", "singleTask")

        internal fun stableSlotIndex(instanceId: String, slotCount: Int): Int {
            require(slotCount > 0) { "slotCount must be positive" }
            return Math.floorMod(instanceId.hashCode(), slotCount)
        }

        private fun orderedCandidates(candidates: List<String>, taskKey: String): List<String> {
            val start = stableSlotIndex(taskKey, candidates.size)
            return candidates.indices.map { offset -> candidates[(start + offset) % candidates.size] }
        }
    }
}

class UnsupportedVirtualActivityLaunchModeException(
    val launchMode: String
) : UnsupportedOperationException(
    "Virtual Activity launchMode=$launchMode is not supported"
)

class ProxyActivitySlotExhaustedException(
    val instanceId: String,
    val launchMode: String?,
    val taskKey: String,
    val candidateCount: Int
) : IllegalStateException(
    "No free proxy Activity slot for instanceId=$instanceId, launchMode=${launchMode ?: "standard"}, " +
        "taskKey=$taskKey, candidateCount=$candidateCount"
)

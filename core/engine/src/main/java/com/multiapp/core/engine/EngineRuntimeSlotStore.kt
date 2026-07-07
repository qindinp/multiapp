package com.multiapp.core.engine

import java.io.File
import java.util.Properties

data class EngineRuntimeSlotAssignment(
    val instanceId: String,
    val originPackageName: String,
    val processSlot: String,
    val proxySlot: String,
    val updatedAtMs: Long
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(proxySlot.isNotBlank()) { "proxySlot must not be blank" }
    }
}

interface EngineRuntimeSlotStore {
    fun assign(
        instanceId: String,
        originPackageName: String,
        processCandidates: List<String>,
        proxyCandidates: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): EngineRuntimeSlotAssignment

    fun get(instanceId: String): EngineRuntimeSlotAssignment?
    fun list(): List<EngineRuntimeSlotAssignment>
    fun prune(validInstanceIds: Set<String>): Int
}

class EngineRuntimeSlotExhaustedException(
    val instanceId: String,
    val originPackageName: String,
    val slotType: String,
    val candidateCount: Int
) : IllegalStateException(
    "No free $slotType slot for instanceId=$instanceId, originPackageName=$originPackageName, " +
        "candidateCount=$candidateCount"
)

class InMemoryEngineRuntimeSlotStore : EngineRuntimeSlotStore {
    private val assignments = linkedMapOf<String, EngineRuntimeSlotAssignment>()

    @Synchronized
    override fun assign(
        instanceId: String,
        originPackageName: String,
        processCandidates: List<String>,
        proxyCandidates: List<String>,
        nowMs: Long
    ): EngineRuntimeSlotAssignment {
        return assignFrom(
            current = assignments.values.toList(),
            existing = assignments[instanceId],
            instanceId = instanceId,
            originPackageName = originPackageName,
            processCandidates = processCandidates,
            proxyCandidates = proxyCandidates,
            nowMs = nowMs
        ).also { assignments[instanceId] = it }
    }

    @Synchronized
    override fun get(instanceId: String): EngineRuntimeSlotAssignment? = assignments[instanceId]

    @Synchronized
    override fun list(): List<EngineRuntimeSlotAssignment> = assignments.values.toList()

    @Synchronized
    override fun prune(validInstanceIds: Set<String>): Int {
        val before = assignments.size
        assignments.keys.removeAll { it !in validInstanceIds }
        return before - assignments.size
    }
}

class FileBackedEngineRuntimeSlotStore(
    private val file: File
) : EngineRuntimeSlotStore {

    @Synchronized
    override fun assign(
        instanceId: String,
        originPackageName: String,
        processCandidates: List<String>,
        proxyCandidates: List<String>,
        nowMs: Long
    ): EngineRuntimeSlotAssignment {
        val current = load().associateBy { it.instanceId }.toMutableMap()
        val assignment = assignFrom(
            current = current.values.toList(),
            existing = current[instanceId],
            instanceId = instanceId,
            originPackageName = originPackageName,
            processCandidates = processCandidates,
            proxyCandidates = proxyCandidates,
            nowMs = nowMs
        )
        current[instanceId] = assignment
        store(current.values.toList())
        return assignment
    }

    @Synchronized
    override fun get(instanceId: String): EngineRuntimeSlotAssignment? =
        load().firstOrNull { it.instanceId == instanceId }

    @Synchronized
    override fun list(): List<EngineRuntimeSlotAssignment> = load()

    @Synchronized
    override fun prune(validInstanceIds: Set<String>): Int {
        val current = load()
        val retained = current.filter { it.instanceId in validInstanceIds }
        if (retained.size != current.size) {
            store(retained)
        }
        return current.size - retained.size
    }

    private fun load(): List<EngineRuntimeSlotAssignment> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use { input -> properties.load(input) }
        return properties.stringPropertyNames()
            .asSequence()
            .mapNotNull { name -> name.substringBefore('.').takeIf { it.isNotBlank() } }
            .distinct()
            .sorted()
            .mapNotNull { instanceId ->
                val prefix = "$instanceId."
                val originPackageName = properties.getProperty(prefix + ORIGIN_PACKAGE_NAME).orEmpty()
                val processSlot = properties.getProperty(prefix + PROCESS_SLOT).orEmpty()
                val proxySlot = properties.getProperty(prefix + PROXY_SLOT).orEmpty()
                val updatedAtMs = properties.getProperty(prefix + UPDATED_AT_MS).orEmpty().toLongOrNull() ?: 0L
                runCatching {
                    EngineRuntimeSlotAssignment(
                        instanceId = instanceId,
                        originPackageName = originPackageName,
                        processSlot = processSlot,
                        proxySlot = proxySlot,
                        updatedAtMs = updatedAtMs
                    )
                }.getOrNull()
            }
            .toList()
    }

    private fun store(assignments: List<EngineRuntimeSlotAssignment>) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        assignments.sortedBy { it.instanceId }.forEach { assignment ->
            require(!assignment.instanceId.hasUnsafeStorageChars()) { "unsafe instanceId for engine slot key" }
            val prefix = "${assignment.instanceId}."
            properties.setProperty(prefix + ORIGIN_PACKAGE_NAME, assignment.originPackageName)
            properties.setProperty(prefix + PROCESS_SLOT, assignment.processSlot)
            properties.setProperty(prefix + PROXY_SLOT, assignment.proxySlot)
            properties.setProperty(prefix + UPDATED_AT_MS, assignment.updatedAtMs.toString())
        }
        file.outputStream().use { output ->
            properties.store(output, "MultiApp engine runtime slot assignments")
        }
    }

    private fun String.hasUnsafeStorageChars(): Boolean =
        any { it == '\n' || it == '\r' || it == '.' || it.code < 0x20 }

    companion object {
        private const val ORIGIN_PACKAGE_NAME = "originPackageName"
        private const val PROCESS_SLOT = "processSlot"
        private const val PROXY_SLOT = "proxySlot"
        private const val UPDATED_AT_MS = "updatedAtMs"
    }
}

private fun assignFrom(
    current: List<EngineRuntimeSlotAssignment>,
    existing: EngineRuntimeSlotAssignment?,
    instanceId: String,
    originPackageName: String,
    processCandidates: List<String>,
    proxyCandidates: List<String>,
    nowMs: Long
): EngineRuntimeSlotAssignment {
    require(instanceId.isNotBlank()) { "instanceId must not be blank" }
    require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
    require(processCandidates.isNotEmpty()) { "processCandidates must not be empty" }
    require(proxyCandidates.isNotEmpty()) { "proxyCandidates must not be empty" }

    if (existing != null &&
        existing.originPackageName == originPackageName &&
        existing.processSlot in processCandidates &&
        existing.proxySlot in proxyCandidates
    ) {
        return existing.copy(updatedAtMs = nowMs)
    }

    val usedProcessSlots = current
        .asSequence()
        .filter { it.instanceId != instanceId && it.originPackageName == originPackageName }
        .map { it.processSlot }
        .toSet()
    val usedProxySlots = current
        .asSequence()
        .filter { it.instanceId != instanceId }
        .map { it.proxySlot }
        .toSet()

    val processSlot = chooseSlot(
        candidates = processCandidates,
        used = usedProcessSlots,
        key = "$originPackageName:$instanceId"
    ) ?: throw EngineRuntimeSlotExhaustedException(
        instanceId = instanceId,
        originPackageName = originPackageName,
        slotType = "process",
        candidateCount = processCandidates.size
    )
    val proxySlot = chooseSlot(
        candidates = proxyCandidates,
        used = usedProxySlots,
        key = instanceId
    ) ?: throw EngineRuntimeSlotExhaustedException(
        instanceId = instanceId,
        originPackageName = originPackageName,
        slotType = "proxy",
        candidateCount = proxyCandidates.size
    )

    return EngineRuntimeSlotAssignment(
        instanceId = instanceId,
        originPackageName = originPackageName,
        processSlot = processSlot,
        proxySlot = proxySlot,
        updatedAtMs = nowMs
    )
}

private fun chooseSlot(candidates: List<String>, used: Set<String>, key: String): String? {
    val start = stableSlotIndex(key, candidates.size)
    return candidates.indices
        .asSequence()
        .map { offset -> candidates[(start + offset) % candidates.size] }
        .firstOrNull { candidate -> candidate !in used }
}

internal fun stableSlotIndex(key: String, slotCount: Int): Int {
    require(slotCount > 0) { "slotCount must be positive" }
    return Math.floorMod(key.hashCode(), slotCount)
}

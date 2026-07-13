package com.multiapp.core.model.virtual

data class ProxyActivitySlotKey(
    val instanceId: String,
    val launchMode: String?,
    val taskKey: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(taskKey.isNotBlank()) { "taskKey must not be blank" }
    }
}

interface ProxyActivitySlotAssignmentStore {
    fun find(key: ProxyActivitySlotKey): String?
    fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String)
    fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean = false
    fun reserve(key: ProxyActivitySlotKey, candidateProxyActivityClassNames: List<String>): String? {
        val assigned = find(key)
        if (assigned != null && assigned in candidateProxyActivityClassNames) {
            return assigned
        }
        val selected = candidateProxyActivityClassNames.firstOrNull { candidate ->
            val owner = ownerOf(candidate)
            owner == null || owner == key
        } ?: return null
        save(key, selected)
        return selected
    }
    fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? = null
    fun removeInstance(instanceId: String): Int = 0
    fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int = 0
}

class InMemoryProxyActivitySlotAssignmentStore : ProxyActivitySlotAssignmentStore {
    private val assignments = linkedMapOf<ProxyActivitySlotKey, String>()

    @Synchronized
    override fun find(key: ProxyActivitySlotKey): String? = assignments[key]

    @Synchronized
    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        assignments[key] = proxyActivityClassName
    }

    @Synchronized
    override fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean {
        require(newProxyActivityClassName == null || newProxyActivityClassName.isNotBlank()) {
            "newProxyActivityClassName must be null or non-blank"
        }
        if (assignments[key] != expectedProxyActivityClassName) return false
        if (
            newProxyActivityClassName != null &&
            assignments.any { (storedKey, value) -> storedKey != key && value == newProxyActivityClassName }
        ) {
            return false
        }
        if (newProxyActivityClassName == null) {
            assignments.remove(key)
        } else {
            assignments[key] = newProxyActivityClassName
        }
        return true
    }

    @Synchronized
    override fun reserve(
        key: ProxyActivitySlotKey,
        candidateProxyActivityClassNames: List<String>
    ): String? {
        val candidates = candidateProxyActivityClassNames.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null
        assignments[key]
            ?.takeIf { assigned ->
                assigned in candidates && assignments.none { (storedKey, value) ->
                    storedKey != key && value == assigned
                }
            }
            ?.let { return it }
        val selected = candidates.firstOrNull { candidate ->
            assignments.none { (storedKey, value) -> storedKey != key && value == candidate }
        } ?: return null
        assignments[key] = selected
        return selected
    }

    @Synchronized
    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? {
        if (proxyActivityClassName.isBlank()) return null
        return assignments.entries
            .sortedBy { it.key.toString() }
            .firstOrNull { it.value == proxyActivityClassName }
            ?.key
    }

    @Synchronized
    override fun removeInstance(instanceId: String): Int {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        val matchingKeys = assignments.keys.filter { it.instanceId == instanceId }
        matchingKeys.forEach(assignments::remove)
        return matchingKeys.size
    }

    @Synchronized
    override fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int {
        val staleKeys = assignments
            .filter { (key, className) ->
                key.instanceId !in validInstanceIds ||
                    className !in knownProxyActivityClassNames ||
                    className !in liveProxyActivityClassNames
            }
            .keys
        staleKeys.forEach(assignments::remove)
        return staleKeys.size
    }
}

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

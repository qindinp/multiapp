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
    fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? = null
    fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int = 0
}

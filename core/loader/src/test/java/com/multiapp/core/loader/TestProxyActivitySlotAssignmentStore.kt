package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey

internal open class TestProxyActivitySlotAssignmentStore : ProxyActivitySlotAssignmentStore {
    protected val assignments = linkedMapOf<ProxyActivitySlotKey, String>()

    @Synchronized
    override fun find(key: ProxyActivitySlotKey): String? = assignments[key]

    @Synchronized
    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
        assignments[key] = proxyActivityClassName
    }

    @Synchronized
    override fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean {
        if (assignments[key] != expectedProxyActivityClassName) return false
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
        assignments[key]
            ?.takeIf { it in candidateProxyActivityClassNames }
            ?.let { return it }
        val selected = candidateProxyActivityClassNames.firstOrNull { candidate ->
            assignments.entries.none { (owner, assigned) -> owner != key && assigned == candidate }
        } ?: return null
        assignments[key] = selected
        return selected
    }

    @Synchronized
    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? =
        assignments.entries.firstOrNull { it.value == proxyActivityClassName }?.key
}

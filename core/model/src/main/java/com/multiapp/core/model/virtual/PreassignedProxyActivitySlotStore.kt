package com.multiapp.core.model.virtual

/** Process-local view of one slot already reserved by the engine authority. */
class PreassignedProxyActivitySlotStore(
    private val key: ProxyActivitySlotKey,
    private val proxyActivityClassName: String
) : ProxyActivitySlotAssignmentStore {
    init {
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
    }

    override fun find(key: ProxyActivitySlotKey): String? =
        proxyActivityClassName.takeIf { key == this.key }

    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
        require(key == this.key && proxyActivityClassName == this.proxyActivityClassName) {
            "client cannot change an engine-preassigned proxy Activity slot"
        }
    }

    override fun reserve(
        key: ProxyActivitySlotKey,
        candidateProxyActivityClassNames: List<String>
    ): String? = proxyActivityClassName.takeIf {
        key == this.key && proxyActivityClassName in candidateProxyActivityClassNames
    }

    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? =
        key.takeIf { proxyActivityClassName == this.proxyActivityClassName }

    override fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean =
        key == this.key &&
            expectedProxyActivityClassName == proxyActivityClassName &&
            newProxyActivityClassName == proxyActivityClassName
}

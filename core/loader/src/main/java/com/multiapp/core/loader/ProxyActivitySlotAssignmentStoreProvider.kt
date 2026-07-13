package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey

/**
 * Process-local loader seam for the authoritative proxy Activity slot store.
 * The production authority must install its adapter before Activity allocation.
 */
object ProxyActivitySlotAssignmentStoreProvider {
    @Volatile
    private var store: ProxyActivitySlotAssignmentStore? = null

    fun install(store: ProxyActivitySlotAssignmentStore) {
        this.store = store
    }

    fun requireStore(): ProxyActivitySlotAssignmentStore =
        store ?: throw ProxyActivitySlotAssignmentStoreProviderNotInstalledException()

    internal fun clearForTests() {
        store = null
    }
}

class ProxyActivitySlotAssignmentStoreProviderNotInstalledException : IllegalStateException(
    "ProxyActivitySlotAssignmentStoreProvider is not installed; " +
        "the production proxy Activity slot authority must install a store adapter before allocation"
)

internal object ProviderBackedProxyActivitySlotAssignmentStore : ProxyActivitySlotAssignmentStore {
    override fun find(key: ProxyActivitySlotKey): String? =
        ProxyActivitySlotAssignmentStoreProvider.requireStore().find(key)

    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
        ProxyActivitySlotAssignmentStoreProvider.requireStore().save(key, proxyActivityClassName)
    }

    override fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean = ProxyActivitySlotAssignmentStoreProvider.requireStore().compareAndSet(
        key,
        expectedProxyActivityClassName,
        newProxyActivityClassName
    )

    override fun reserve(
        key: ProxyActivitySlotKey,
        candidateProxyActivityClassNames: List<String>
    ): String? = ProxyActivitySlotAssignmentStoreProvider.requireStore().reserve(
        key,
        candidateProxyActivityClassNames
    )

    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? =
        ProxyActivitySlotAssignmentStoreProvider.requireStore().ownerOf(proxyActivityClassName)

    override fun removeInstance(instanceId: String): Int =
        ProxyActivitySlotAssignmentStoreProvider.requireStore().removeInstance(instanceId)

    override fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int = ProxyActivitySlotAssignmentStoreProvider.requireStore().pruneStaleAssignments(
        validInstanceIds,
        liveProxyActivityClassNames,
        knownProxyActivityClassNames
    )
}

internal class ProxyActivitySlotAssignmentRollback(
    private val store: ProxyActivitySlotAssignmentStore
) {
    private val previousAssignments = linkedMapOf<ProxyActivitySlotKey, String?>()

    fun remember(key: ProxyActivitySlotKey) {
        if (!previousAssignments.containsKey(key)) {
            previousAssignments[key] = store.find(key)
        }
    }

    fun restore() {
        previousAssignments.forEach { (key, previousAssignment) ->
            val currentAssignment = store.find(key)
            if (currentAssignment != previousAssignment) {
                store.compareAndSet(key, currentAssignment, previousAssignment)
            }
        }
    }
}

internal fun VirtualActivityLaunchRequest.proxyActivitySlotKey(): ProxyActivitySlotKey {
    val taskKey = taskAffinity ?: "$originPackageName:$instanceId"
    return ProxyActivitySlotKey(
        instanceId = instanceId,
        launchMode = ProxyActivityRegistry.normalizeLaunchMode(launchMode),
        taskKey = taskKey
    )
}

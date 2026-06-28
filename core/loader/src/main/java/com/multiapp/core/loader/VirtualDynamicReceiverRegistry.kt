package com.multiapp.core.loader

import android.content.BroadcastReceiver
import android.content.Intent

data class VirtualDynamicReceiverFilter(
    val actions: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val dataSchemes: Set<String> = emptySet()
) {
    fun matches(intent: Intent): Boolean {
        val action = intent.action
        if (actions.isNotEmpty() && action !in actions) return false

        val intentCategories = runCatching { intent.categories.orEmpty() }.getOrDefault(emptySet())
        if (!categories.containsAll(intentCategories)) return false

        val scheme = runCatching { intent.data?.scheme }.getOrNull()
        if (dataSchemes.isNotEmpty() && scheme !in dataSchemes) return false

        return true
    }
}

data class VirtualDynamicReceiverRecord(
    val instanceId: String,
    val receiver: BroadcastReceiver,
    val filter: VirtualDynamicReceiverFilter
)

class VirtualDynamicReceiverRegistry {
    private val records = linkedMapOf<BroadcastReceiver, VirtualDynamicReceiverRecord>()

    @Synchronized
    fun register(
        instanceId: String,
        receiver: BroadcastReceiver,
        filter: VirtualDynamicReceiverFilter
    ): VirtualDynamicReceiverRecord {
        val record = VirtualDynamicReceiverRecord(instanceId, receiver, filter)
        records[receiver] = record
        return record
    }

    @Synchronized
    fun unregister(receiver: BroadcastReceiver): VirtualDynamicReceiverRecord? = records.remove(receiver)

    @Synchronized
    fun query(instanceId: String, intent: Intent): List<VirtualDynamicReceiverRecord> {
        return records.values.filter { it.instanceId == instanceId && it.filter.matches(intent) }
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    companion object {
        val global: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry()
    }
}

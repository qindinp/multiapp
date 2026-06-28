package com.multiapp.core.loader

import android.app.Service

/** Process-local records for guest Service instances hosted by one StubService slot. */
class VirtualServiceRecordManager {
    private val records = linkedMapOf<String, VirtualServiceRecord>()

    @Synchronized
    fun get(instanceId: String, guestServiceClassName: String): VirtualServiceRecord? =
        records[key(instanceId, guestServiceClassName)]

    @Synchronized
    fun put(record: VirtualServiceRecord): VirtualServiceRecord {
        records[key(record.instanceId, record.guestServiceClassName)] = record
        return record
    }

    @Synchronized
    fun updateStart(
        instanceId: String,
        guestServiceClassName: String,
        startId: Int,
        lastStartCommandResult: Int
    ): VirtualServiceRecord? {
        val key = key(instanceId, guestServiceClassName)
        val existing = records[key] ?: return null
        val updated = existing.copy(
            lastStartId = startId,
            startCount = existing.startCount + 1,
            lastStartCommandResult = lastStartCommandResult
        )
        records[key] = updated
        return updated
    }

    @Synchronized
    fun remove(instanceId: String, guestServiceClassName: String): VirtualServiceRecord? =
        records.remove(key(instanceId, guestServiceClassName))

    @Synchronized
    fun list(): List<VirtualServiceRecord> = records.values.toList()

    @Synchronized
    fun clear() {
        records.clear()
    }

    private fun key(instanceId: String, guestServiceClassName: String): String =
        "$instanceId:$guestServiceClassName"

    companion object {
        val global: VirtualServiceRecordManager = VirtualServiceRecordManager()
    }
}

data class VirtualServiceRecord(
    val instanceId: String,
    val originPackageName: String,
    val guestServiceClassName: String,
    val service: Service,
    val createdAtMs: Long,
    val lastStartId: Int? = null,
    val startCount: Int = 0,
    val lastStartCommandResult: Int? = null
)

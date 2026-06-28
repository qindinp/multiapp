package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord

/**
 * Process-local Activity launch record store for hosted container proxy slots.
 *
 * Proxy Activity launches carry all required extras for cold-path recovery, but
 * a container runtime still needs a central in-process table for diagnostics,
 * result routing, and future task/back-stack management. This mirrors the
 * Activity record layer used by VirtualApp/BlackBox/DroidPlugin-style runtimes.
 */
class VirtualActivityRecordManager {
    private val records = linkedMapOf<String, VirtualActivityRecord>()
    private val recordsByProxy = linkedMapOf<String, String>()
    private val recordsByActivityId = linkedMapOf<String, String>()
    private val activityStack = VirtualActivityStack()
    private var lastLaunchResult: VirtualActivityStack.LaunchResult? = null

    @Synchronized
    fun register(record: VirtualActivityRecord): VirtualActivityRecord {
        records[record.token] = record
        recordsByProxy[record.proxyActivityClassName] = record.token
        recordsByActivityId[record.activityId] = record.token
        return record
    }

    @Synchronized
    fun registerLaunch(
        record: VirtualActivityRecord,
        intentFlags: Int = record.intentFlags,
        dataIntent: VirtualIntentSnapshot? = null
    ): VirtualActivityStack.LaunchResult {
        val result = activityStack.launch(record, intentFlags, dataIntent)
        result.clearedActivities.forEach { cleared -> markFinished(cleared) }
        register(result.activity)
        lastLaunchResult = result
        return result
    }

    @Synchronized
    fun lastLaunchResult(): VirtualActivityStack.LaunchResult? = lastLaunchResult

    @Synchronized
    fun listTasks(): List<VirtualTaskRecord> = activityStack.listTasks()

    @Synchronized
    fun resolve(token: String?): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        return records[token]
    }

    @Synchronized
    fun resolveByActivityId(activityId: String?): VirtualActivityRecord? {
        if (activityId.isNullOrBlank()) return null
        return recordsByActivityId[activityId]?.let { records[it] }
    }

    @Synchronized
    fun resolveByProxy(proxyActivityClassName: String?): VirtualActivityRecord? {
        if (proxyActivityClassName.isNullOrBlank()) return null
        return recordsByProxy[proxyActivityClassName]?.let { records[it] }
    }

    @Synchronized
    fun finish(token: String?): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        val finished = activityStack.finishByToken(token) ?: records[token]?.copy(state = VirtualActivityState.FINISHED)
        return finished?.let { markFinished(it) }
    }

    @Synchronized
    fun finishByActivityId(activityId: String?): VirtualActivityRecord? {
        if (activityId.isNullOrBlank()) return null
        val token = recordsByActivityId[activityId]
        val finished = activityStack.finishByActivityId(activityId)
            ?: token?.let { records[it]?.copy(state = VirtualActivityState.FINISHED) }
        return finished?.let { markFinished(it) }
    }

    @Synchronized
    fun setResult(
        token: String?,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null
    ): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        val updated = activityStack.setResultByToken(token, resultCode, dataIntent)
            ?: records[token]?.copy(result = VirtualActivityResult(resultCode = resultCode, dataIntent = dataIntent))
        return updated?.let { updateRecord(it) }
    }

    @Synchronized
    fun setResultByActivityId(
        activityId: String?,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null
    ): VirtualActivityRecord? {
        if (activityId.isNullOrBlank()) return null
        val token = recordsByActivityId[activityId]
        val updated = activityStack.setResultByActivityId(activityId, resultCode, dataIntent)
            ?: token?.let { records[it]?.copy(result = VirtualActivityResult(resultCode = resultCode, dataIntent = dataIntent)) }
        return updated?.let { updateRecord(it) }
    }

    @Synchronized
    fun consumePendingNewIntent(token: String?): VirtualActivityPendingNewIntent? {
        if (token.isNullOrBlank()) return null
        val record = records[token] ?: return null
        if (record.state == VirtualActivityState.FINISHED) return null
        val pending = activityStack.consumePendingNewIntentByToken(token) ?: return null
        updateRecord(record.copy(pendingNewIntents = record.pendingNewIntents.drop(1)))
        return pending
    }

    @Synchronized
    fun consumePendingNewIntentByActivityId(activityId: String?): VirtualActivityPendingNewIntent? {
        if (activityId.isNullOrBlank()) return null
        val token = recordsByActivityId[activityId] ?: return null
        return consumePendingNewIntent(token)
    }

    @Synchronized
    fun consumeResult(token: String?): VirtualActivityResult? {
        if (token.isNullOrBlank()) return null
        val record = records[token] ?: return null
        val result = activityStack.consumeResultByToken(token) ?: record.result ?: return null
        updateRecord(record.copy(result = null))
        return result
    }

    @Synchronized
    fun consumeResultByActivityId(activityId: String?): VirtualActivityResult? {
        if (activityId.isNullOrBlank()) return null
        val token = recordsByActivityId[activityId] ?: return null
        return consumeResult(token)
    }

    @Synchronized
    fun consume(token: String?): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        val removed = unregister(token) ?: return null
        activityStack.removeByToken(token)
        return removed
    }

    @Synchronized
    fun clearByInstance(instanceId: String): Int {
        val tokens = records.values
            .filter { it.instanceId == instanceId }
            .map { it.token }
        tokens.forEach { unregister(it) }
        activityStack.removeByInstance(instanceId)
        return tokens.size
    }

    @Synchronized
    fun list(): List<VirtualActivityRecord> = records.values.toList()

    @Synchronized
    fun clearAll() {
        records.clear()
        recordsByProxy.clear()
        recordsByActivityId.clear()
        activityStack.clearAll()
        lastLaunchResult = null
    }

    private fun updateRecord(record: VirtualActivityRecord): VirtualActivityRecord {
        records[record.token] = record
        recordsByActivityId[record.activityId] = record.token
        if (record.state != VirtualActivityState.FINISHED) {
            recordsByProxy[record.proxyActivityClassName] = record.token
        }
        return record
    }

    private fun markFinished(record: VirtualActivityRecord): VirtualActivityRecord {
        val finished = record.copy(state = VirtualActivityState.FINISHED)
        records[finished.token] = finished
        recordsByActivityId[finished.activityId] = finished.token
        if (recordsByProxy[finished.proxyActivityClassName] == finished.token) {
            recordsByProxy.remove(finished.proxyActivityClassName)
        }
        return finished
    }

    private fun unregister(token: String): VirtualActivityRecord? {
        val removed = records.remove(token) ?: return null
        if (recordsByProxy[removed.proxyActivityClassName] == token) {
            recordsByProxy.remove(removed.proxyActivityClassName)
        }
        if (recordsByActivityId[removed.activityId] == token) {
            recordsByActivityId.remove(removed.activityId)
        }
        return removed
    }

    companion object {
        val global: VirtualActivityRecordManager = VirtualActivityRecordManager()
    }
}

package com.multiapp.core.loader

import android.content.Intent
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord

data class VirtualActivityRecordManagerSnapshot(
    val tasks: List<VirtualTaskRecord>,
    val records: List<VirtualActivityRecord>,
    val lastLaunchResult: VirtualActivityStack.LaunchResult?,
    val intents: Map<String, Intent>
)

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
        conflictingProxyOwner(record)?.let { owner ->
            return owner
        }
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
        conflictingProxyOwner(record)?.let { owner ->
            return existingOwnerLaunchResult(owner)
        }
        val result = activityStack.launch(record, intentFlags, dataIntent)
        result.clearedActivities.forEach { cleared -> markFinished(cleared) }
        register(result.activity)
        lastLaunchResult = result
        return result
    }

    @Synchronized
    fun conflictingProxyOwner(record: VirtualActivityRecord): VirtualActivityRecord? = records.values
        .asSequence()
        .filter { it.proxyActivityClassName == record.proxyActivityClassName }
        .filter { it.isActive() }
        .firstOrNull { owner ->
            if (owner.token == record.token) {
                !(owner.matchesRecordOwner(record) && owner.matchesProxySlotOwner(record))
            } else {
                !owner.matchesProxySlotOwner(record)
            }
        }

    @Synchronized
    fun lastLaunchResult(): VirtualActivityStack.LaunchResult? = lastLaunchResult

    @Synchronized
    fun listTasks(): List<VirtualTaskRecord> = activityStack.listTasks()

    @Synchronized
    fun exportTasks(): List<VirtualTaskRecord> = activityStack.listTasks()

    @Synchronized
    fun snapshotState(): VirtualActivityRecordManagerSnapshot = VirtualActivityRecordManagerSnapshot(
        tasks = activityStack.listTasks(),
        records = records.values.toList(),
        lastLaunchResult = lastLaunchResult,
        intents = VirtualActivityIntentStore.snapshot()
    )

    @Synchronized
    fun restoreState(snapshot: VirtualActivityRecordManagerSnapshot) {
        records.clear()
        recordsByProxy.clear()
        recordsByActivityId.clear()
        activityStack.restore(snapshot.tasks)
        snapshot.records.forEach { record ->
            records[record.token] = record
            recordsByActivityId[record.activityId] = record.token
            if (record.isActive()) {
                recordsByProxy[record.proxyActivityClassName] = record.token
            }
        }
        lastLaunchResult = snapshot.lastLaunchResult
        VirtualActivityIntentStore.restore(snapshot.intents)
    }

    @Synchronized
    fun restoreTasks(tasks: List<VirtualTaskRecord>): Int {
        records.clear()
        recordsByProxy.clear()
        recordsByActivityId.clear()
        VirtualActivityIntentStore.clearAll()
        lastLaunchResult = null
        val restored = activityStack.restore(tasks)
        activityStack.listTasks()
            .flatMap { it.activities }
            .forEach { record ->
                records[record.token] = record
                recordsByActivityId[record.activityId] = record.token
                if (record.isActive()) {
                    recordsByProxy[record.proxyActivityClassName] = record.token
                }
            }
        return restored
    }

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
        dataIntent: VirtualIntentSnapshot? = null,
        requestCode: Int = -1,
        resultWho: String? = null,
        frameworkDispatchAttempted: Boolean = false,
        frameworkDispatchInvoked: Boolean = false
    ): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        val result = VirtualActivityResult(
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        )
        val updated = activityStack.setResultByToken(
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ) ?: records[token]?.copy(result = result)
        return updated?.let { updateRecord(it) }
    }

    @Synchronized
    fun setResultByActivityId(
        activityId: String?,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null,
        requestCode: Int = -1,
        resultWho: String? = null,
        frameworkDispatchAttempted: Boolean = false,
        frameworkDispatchInvoked: Boolean = false
    ): VirtualActivityRecord? {
        if (activityId.isNullOrBlank()) return null
        val token = recordsByActivityId[activityId]
        val result = VirtualActivityResult(
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        )
        val updated = activityStack.setResultByActivityId(
            activityId = activityId,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ) ?: token?.let { records[it]?.copy(result = result) }
        return updated?.let { updateRecord(it) }
    }

    @Synchronized
    fun markResultDispatchState(
        token: String?,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        val updated = activityStack.updateResultDispatchStateByToken(
            token = token,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ) ?: records[token]?.takeIf { it.result != null }?.let { record ->
            record.copy(
                result = record.result?.copy(
                    frameworkDispatchAttempted = frameworkDispatchAttempted,
                    frameworkDispatchInvoked = frameworkDispatchInvoked
                )
            )
        }
        return updated?.let { updateRecord(it) }
    }

    @Synchronized
    fun updateState(
        token: String?,
        state: VirtualActivityState
    ): VirtualActivityRecord? {
        if (token.isNullOrBlank()) return null
        if (state == VirtualActivityState.FINISHED || state == VirtualActivityState.DESTROYED) {
            return finish(token)
        }
        val updated = activityStack.updateStateByToken(token, state) ?: records[token]?.copy(state = state)
        return updated?.let { updateRecord(it) }
    }

    @Synchronized
    fun updateStateByActivityId(
        activityId: String?,
        state: VirtualActivityState
    ): VirtualActivityRecord? {
        if (activityId.isNullOrBlank()) return null
        val token = recordsByActivityId[activityId]
        if (state == VirtualActivityState.FINISHED || state == VirtualActivityState.DESTROYED) {
            return finishByActivityId(activityId)
        }
        val updated = activityStack.updateStateByActivityId(activityId, state)
            ?: token?.let { records[it]?.copy(state = state) }
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
    fun consumeResultForResumeFallback(token: String?): VirtualActivityResult? {
        if (token.isNullOrBlank()) return null
        val record = records[token] ?: return null
        val result = activityStack.findByToken(token)?.result ?: record.result ?: return null
        if (result.requestCode < 0) return null
        if (result.frameworkDispatchInvoked) return null
        return consumeResult(token)
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
    fun pruneStaleProxyRecords(
        knownProxyActivityClassNames: Set<String>,
        liveProxyActivityClassNames: Set<String>
    ): Int {
        val staleTokens = records.values
            .filter { record ->
                record.proxyActivityClassName !in knownProxyActivityClassNames ||
                    record.proxyActivityClassName !in liveProxyActivityClassNames
            }
            .map { it.token }
        staleTokens.forEach { token ->
            unregister(token)
            activityStack.removeByToken(token)
        }
        activityStack.pruneEmptyTasks()
        if (lastLaunchResult?.activity?.token in staleTokens) {
            lastLaunchResult = null
        }
        return staleTokens.size
    }

    @Synchronized
    fun list(): List<VirtualActivityRecord> = records.values.toList()

    @Synchronized
    fun clearAll() {
        records.clear()
        recordsByProxy.clear()
        recordsByActivityId.clear()
        activityStack.clearAll()
        VirtualActivityIntentStore.clearAll()
        lastLaunchResult = null
    }

    private fun existingOwnerLaunchResult(owner: VirtualActivityRecord): VirtualActivityStack.LaunchResult {
        val task = activityStack.listTasks().firstOrNull { task ->
            task.activities.any { it.token == owner.token }
        } ?: VirtualTaskRecord(
            taskId = owner.taskId.takeIf { it > 0 } ?: 1,
            affinity = owner.taskAffinity ?: "${owner.originPackageName}:${owner.instanceId}",
            activities = listOf(owner)
        )
        return VirtualActivityStack.LaunchResult(
            activity = owner,
            task = task,
            reused = true
        )
    }

    private fun VirtualActivityRecord.isActive(): Boolean =
        state != VirtualActivityState.FINISHED && state != VirtualActivityState.DESTROYED

    private fun VirtualActivityRecord.matchesRecordOwner(other: VirtualActivityRecord): Boolean =
        instanceId == other.instanceId &&
            originPackageName == other.originPackageName &&
            guestActivityClassName == other.guestActivityClassName &&
            proxyActivityClassName == other.proxyActivityClassName

    private fun VirtualActivityRecord.matchesProxySlotOwner(other: VirtualActivityRecord): Boolean =
        instanceId == other.instanceId &&
            ProxyActivityRegistry.normalizeLaunchMode(launchMode) == ProxyActivityRegistry.normalizeLaunchMode(other.launchMode) &&
            effectiveTaskAffinity() == other.effectiveTaskAffinity()

    private fun VirtualActivityRecord.effectiveTaskAffinity(): String =
        taskAffinity ?: "${originPackageName}:${instanceId}"

    private fun updateRecord(record: VirtualActivityRecord): VirtualActivityRecord {
        records[record.token] = record
        recordsByActivityId[record.activityId] = record.token
        if (record.isActive()) {
            recordsByProxy[record.proxyActivityClassName] = record.token
        } else if (recordsByProxy[record.proxyActivityClassName] == record.token) {
            remapActiveProxyOwner(record.proxyActivityClassName, record.token)
        }
        return record
    }

    private fun markFinished(record: VirtualActivityRecord): VirtualActivityRecord {
        val finished = record.copy(state = VirtualActivityState.FINISHED)
        records[finished.token] = finished
        recordsByActivityId[finished.activityId] = finished.token
        if (recordsByProxy[finished.proxyActivityClassName] == finished.token) {
            remapActiveProxyOwner(finished.proxyActivityClassName, finished.token)
        }
        return finished
    }

    private fun unregister(token: String): VirtualActivityRecord? {
        val removed = records.remove(token) ?: return null
        VirtualActivityIntentStore.clear(token)
        if (recordsByProxy[removed.proxyActivityClassName] == token) {
            remapActiveProxyOwner(removed.proxyActivityClassName, token)
        }
        if (recordsByActivityId[removed.activityId] == token) {
            recordsByActivityId.remove(removed.activityId)
        }
        return removed
    }

    private fun remapActiveProxyOwner(proxyActivityClassName: String, excludedToken: String) {
        val replacement = records.values
            .asSequence()
            .filter { it.token != excludedToken }
            .filter { it.proxyActivityClassName == proxyActivityClassName }
            .filter { it.isActive() }
            .lastOrNull()
        if (replacement == null) {
            recordsByProxy.remove(proxyActivityClassName)
        } else {
            recordsByProxy[proxyActivityClassName] = replacement.token
        }
    }

    companion object {
        val global: VirtualActivityRecordManager = VirtualActivityRecordManager()
    }
}

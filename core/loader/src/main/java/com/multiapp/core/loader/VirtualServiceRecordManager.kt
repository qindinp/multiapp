package com.multiapp.core.loader

import android.app.Service
import android.os.Binder
import android.os.IBinder

/** Process-local records for guest Service instances hosted by one StubService slot. */
class VirtualServiceRecordManager {
    private val records = linkedMapOf<String, VirtualServiceRecord>()

    inline fun <T> withLifecycleLock(block: () -> T): T = synchronized(this, block)

    @Synchronized
    fun get(instanceId: String, guestServiceClassName: String): VirtualServiceRecord? =
        records[key(instanceId, guestServiceClassName)]

    @Synchronized
    fun getByToken(token: IBinder): VirtualServiceRecord? =
        records.values.firstOrNull { it.token === token }

    @Synchronized
    fun put(record: VirtualServiceRecord): VirtualServiceRecord {
        records[key(record.instanceId, record.guestServiceClassName)] = record
        return record
    }

    @Synchronized
    fun beginStart(
        instanceId: String,
        guestServiceClassName: String,
        startId: Int
    ): VirtualServiceRecord? {
        val key = key(instanceId, guestServiceClassName)
        val existing = records[key] ?: return null
        val updated = existing.copy(
            lastStartId = startId,
            startCount = existing.startCount + 1,
            started = true
        )
        records[key] = updated
        return updated
    }

    @Synchronized
    fun completeStart(
        instanceId: String,
        guestServiceClassName: String,
        lastStartCommandResult: Int
    ): VirtualServiceRecord? {
        val key = key(instanceId, guestServiceClassName)
        val existing = records[key] ?: return null
        val updated = existing.copy(
            lastStartCommandResult = lastStartCommandResult
        )
        records[key] = updated
        return updated
    }

    @Synchronized
    fun updateBind(
        instanceId: String,
        guestServiceClassName: String,
        bindKey: String,
        binder: IBinder?,
        flags: Int,
        rebindPending: Boolean = false
    ): VirtualServiceRecord? {
        val key = key(instanceId, guestServiceClassName)
        val existing = records[key] ?: return null
        val existingBinding = existing.bindings[bindKey]
        val updatedBinding = (existingBinding ?: VirtualServiceBindingRecord(bindKey = bindKey)).copy(
            binder = binder,
            flags = flags,
            bindCount = (existingBinding?.bindCount ?: 0) + 1,
            activeConnectionCount = (existingBinding?.activeConnectionCount ?: 0) + 1,
            rebindPending = rebindPending,
            lastUnbindReturned = existingBinding?.lastUnbindReturned
        )
        val updated = existing.copy(
            bindCount = existing.bindCount + 1,
            activeBindCount = existing.activeBindCount + 1,
            lastBinder = binder,
            bindings = existing.bindings + (bindKey to updatedBinding)
        )
        records[key] = updated
        return updated
    }

    @Synchronized
    fun updateUnbind(
        instanceId: String,
        guestServiceClassName: String,
        bindKey: String,
        lastUnbindReturned: Boolean
    ): VirtualServiceRecord? {
        val key = key(instanceId, guestServiceClassName)
        val existing = records[key] ?: return null
        val existingBinding = existing.bindings[bindKey]
            ?.takeIf { it.activeConnectionCount > 0 }
            ?: return existing
        val nextActiveConnections = existingBinding.activeConnectionCount - 1
        val updatedBindings = if (nextActiveConnections == 0 && !lastUnbindReturned) {
            existing.bindings - bindKey
        } else {
            existing.bindings + (
                bindKey to existingBinding.copy(
                    activeConnectionCount = nextActiveConnections,
                    rebindPending = nextActiveConnections == 0 && lastUnbindReturned,
                    lastUnbindReturned = lastUnbindReturned
                )
            )
        }
        val updated = existing.copy(
            activeBindCount = (existing.activeBindCount - 1).coerceAtLeast(0),
            lastBinder = if (existing.activeBindCount <= 1) null else existing.lastBinder,
            bindings = updatedBindings
        )
        records[key] = updated
        return updated
    }

    @Synchronized
    fun getBinding(
        instanceId: String,
        guestServiceClassName: String,
        bindKey: String
    ): VirtualServiceBindingRecord? = get(instanceId, guestServiceClassName)?.bindings?.get(bindKey)

    @Synchronized
    fun markStartedStopped(instanceId: String, guestServiceClassName: String): VirtualServiceRecord? {
        val key = key(instanceId, guestServiceClassName)
        val existing = records[key] ?: return null
        val updated = existing.copy(started = false)
        records[key] = updated
        return updated
    }

    @Synchronized
    fun markStartedStopped(token: IBinder): VirtualServiceRecord? {
        val existing = getByToken(token) ?: return null
        return markStartedStopped(existing.instanceId, existing.guestServiceClassName)
    }

    @Synchronized
    fun updateForeground(
        token: IBinder,
        foreground: Boolean,
        notificationId: Int?,
        foregroundServiceType: Int
    ): VirtualServiceRecord? {
        val existing = getByToken(token) ?: return null
        val key = key(existing.instanceId, existing.guestServiceClassName)
        val updated = existing.copy(
            foreground = foreground,
            foregroundNotificationId = notificationId,
            foregroundServiceType = foregroundServiceType
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
    fun hasActiveRecordsForProxyStub(processSlot: String?): Boolean {
        val proxyStubKey = processSlot.toProxyStubKey()
        return records.values.any { record ->
            record.processSlot.toProxyStubKey() == proxyStubKey && record.hasActiveLifecycle
        }
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    private fun key(instanceId: String, guestServiceClassName: String): String =
        "$instanceId:$guestServiceClassName"

    private fun String?.toProxyStubKey(): String = this?.trim().orEmpty()

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
    val processSlot: String? = null,
    val token: IBinder = Binder(),
    val started: Boolean = false,
    val lastStartId: Int? = null,
    val startCount: Int = 0,
    val lastStartCommandResult: Int? = null,
    val bindCount: Int = 0,
    val activeBindCount: Int = 0,
    val lastBinder: IBinder? = null,
    val bindings: Map<String, VirtualServiceBindingRecord> = emptyMap(),
    val foreground: Boolean = false,
    val foregroundNotificationId: Int? = null,
    val foregroundServiceType: Int = 0
) {
    val activeStartCount: Int
        get() = if (started) 1 else 0

    val hasActiveLifecycle: Boolean
        get() = started || foreground || activeBindCount > 0 ||
            bindings.values.any { it.activeConnectionCount > 0 }
}

data class VirtualServiceBindingRecord(
    val bindKey: String,
    val binder: IBinder? = null,
    val flags: Int = 0,
    val bindCount: Int = 0,
    val activeConnectionCount: Int = 0,
    val rebindPending: Boolean = false,
    val lastUnbindReturned: Boolean? = null
)

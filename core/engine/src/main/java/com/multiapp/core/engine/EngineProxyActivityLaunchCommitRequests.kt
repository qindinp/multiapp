package com.multiapp.core.engine

import android.content.Intent
import com.multiapp.core.loader.VirtualActivityIntentStore
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualIntentSnapshot

/**
 * Engine facade for turning a delivered proxy Intent into one authoritative
 * Activity-launch commit. App code must not depend on loader runtime types.
 */
object EngineProxyActivityLaunchCommitRequests {
    fun fromProxyIntent(
        proxyIntent: Intent,
        record: VirtualActivityRecord,
        originalGuestIntent: () -> Intent? = { proxyIntent.originalGuestIntent() }
    ): EngineActivityLaunchCommitRequest? {
        val identity = proxyIntent.toCommitIdentity(record) ?: return null
        val guestIntent = originalGuestIntent()
        return EngineActivityLaunchCommitRequest(
            identity = identity,
            record = record,
            intentFlags = guestIntent?.safeFlags() ?: record.intentFlags,
            dataIntent = guestIntent?.toVirtualIntentSnapshot()
        )
    }

    private fun Intent.toCommitIdentity(record: VirtualActivityRecord): EngineActivityLaunchIdentity? {
        val capabilityToken = getStringExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val runtimeEpoch = getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, 0L)
            .takeIf { it > 0L }
            ?: return null
        val engineSessionId = getStringExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val processSlot = getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            EngineActivityLaunchIdentity(
                capabilityToken = capabilityToken,
                instanceId = record.instanceId,
                runtimeEpoch = runtimeEpoch,
                engineSessionId = engineSessionId,
                processSlot = processSlot,
                proxyActivityClassName = record.proxyActivityClassName,
                guestActivityClassName = record.guestActivityClassName
            )
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun Intent.originalGuestIntent(): Intent? =
        VirtualActivityIntentStore.find(getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN))
            ?: runCatching {
                getParcelableExtra<Intent>(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT)
            }.getOrNull()

    private fun Intent.toVirtualIntentSnapshot(): VirtualIntentSnapshot {
        val extrasSnapshot = runCatching { extras }
            .getOrNull()
            ?.keySet()
            ?.associateWith { "<present>" }
            .orEmpty()
        return VirtualIntentSnapshot(
            flags = safeFlags(),
            action = runCatching { action }.getOrNull(),
            // This snapshot is replayed into the guest, not written as public evidence.
            // Redacting here breaks URI-based Activity contracts.
            dataUri = runCatching { dataString }.getOrNull(),
            categories = runCatching { categories.orEmpty().toSet() }.getOrDefault(emptySet()),
            extras = extrasSnapshot
        )
    }

    private fun Intent.safeFlags(): Int = runCatching { flags }.getOrDefault(0)

}

package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualIntentSnapshot

data class EngineActivityLaunchCommitRequest(
    val identity: EngineActivityLaunchIdentity,
    val record: VirtualActivityRecord,
    val intentFlags: Int = record.intentFlags,
    val dataIntent: VirtualIntentSnapshot? = null
)

data class EngineActivityLaunchCommitValidation(
    val accepted: Boolean,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

fun interface EngineActivityLaunchCommitValidator {
    fun validate(identity: EngineActivityLaunchIdentity, callingPid: Int): EngineActivityLaunchCommitValidation
}

data class EngineActivityLaunchCommitResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val activity: VirtualActivityRecord? = null,
    val launchReused: Boolean = false,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

/**
 * Engine-side owner for a single Activity launch mutation.
 *
 * This intentionally accepts one validated record rather than a task snapshot: the engine's
 * [VirtualActivityRecordManager] remains the only authority that can decide reuse and queue a
 * pending new intent.
 */
class EngineActivityLaunchCommitter(
    private val activityRecordManager: VirtualActivityRecordManager,
    private val validator: EngineActivityLaunchCommitValidator,
    private val persist: (String) -> Unit = {}
) {
    private val receipts = linkedMapOf<String, Receipt>()

    @Synchronized
    fun commit(
        request: EngineActivityLaunchCommitRequest,
        callingPid: Int
    ): EngineActivityLaunchCommitResult {
        val validation = validator.validate(request.identity, callingPid)
        if (!validation.accepted) {
            return rejected("activity_launch_commit_rejected:${validation.reason}")
        }
        val invalidReason = request.invalidReason()
        if (invalidReason != null) return rejected(invalidReason)

        val fingerprint = request.fingerprint()
        val capabilityToken = request.identity.capabilityToken
        receipts[capabilityToken]?.let { receipt ->
            return if (receipt.fingerprint == fingerprint) {
                receipt.result.copy(idempotent = true, reason = "activity_launch_commit_idempotent")
            } else {
                rejected("activity_launch_commit_replayed")
            }
        }

        val snapshot = activityRecordManager.snapshotState()
        return runCatching {
            val launch = activityRecordManager.registerLaunch(
                record = request.record,
                intentFlags = request.intentFlags,
                dataIntent = request.dataIntent
            )
            persist(request.identity.instanceId)
            EngineActivityLaunchCommitResult(
                accepted = true,
                idempotent = false,
                activity = launch.activity,
                launchReused = launch.reused,
                reason = "activity_launch_committed"
            ).also { result ->
                receipts[capabilityToken] = Receipt(fingerprint, result)
            }
        }.getOrElse { error ->
            activityRecordManager.restoreState(snapshot)
            rejected("activity_launch_commit_failed:${error.javaClass.name}")
        }
    }

    private fun EngineActivityLaunchCommitRequest.invalidReason(): String? = when {
        record.token.isBlank() || record.activityId.isBlank() -> "activity_launch_commit_record_identity_invalid"
        record.activityId != record.token -> "activity_launch_commit_activity_id_mismatch"
        record.instanceId != identity.instanceId -> "activity_launch_commit_instance_mismatch"
        record.guestActivityClassName != identity.guestActivityClassName ->
            "activity_launch_commit_guest_activity_mismatch"
        record.proxyActivityClassName != identity.proxyActivityClassName ->
            "activity_launch_commit_proxy_activity_mismatch"
        !ProxyActivityRegistry.isSupportedLaunchMode(record.launchMode) ->
            "activity_launch_commit_launch_mode_unsupported"
        else -> null
    }

    private fun EngineActivityLaunchCommitRequest.fingerprint(): String = listOf(
        identity.capabilityToken,
        identity.instanceId,
        identity.runtimeEpoch.toString(),
        identity.engineSessionId,
        identity.processSlot,
        identity.proxyActivityClassName,
        identity.guestActivityClassName,
        record.token,
        record.activityId,
        record.originPackageName,
        record.launchMode.orEmpty(),
        record.taskAffinity.orEmpty(),
        intentFlags.toString(),
        dataIntent?.action.orEmpty(),
        dataIntent?.dataUri.orEmpty()
    ).joinToString("\u0000")

    private fun rejected(reason: String) = EngineActivityLaunchCommitResult(
        accepted = false,
        idempotent = false,
        reason = reason
    )

    private data class Receipt(
        val fingerprint: String,
        val result: EngineActivityLaunchCommitResult
    )
}

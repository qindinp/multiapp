package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualIntentSnapshot

interface VirtualActivityOperations {
    fun consumePendingNewIntent(instanceId: String, token: String): VirtualActivityPendingNewIntent?
    fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null
    ): VirtualActivityFinishResultRecord
    fun setActivityResult(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null,
        requestCode: Int = -1,
        resultWho: String? = null,
        frameworkDispatchAttempted: Boolean = false,
        frameworkDispatchInvoked: Boolean = false
    ): Boolean
    fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult?
    fun consumeActivityResultForResumeFallback(instanceId: String, token: String): VirtualActivityResult?
    fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): Boolean
    fun finishActivity(instanceId: String, token: String): Boolean
}

data class VirtualActivityFinishResultRecord(
    val instanceId: String,
    val sourceToken: String? = null,
    val requestCode: Int = -1,
    val resultCode: Int,
    val dataIntent: VirtualIntentSnapshot? = null,
    val recorded: Boolean,
    val reason: String
)

class ManagerBackedVirtualActivityOperations(
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global
) : VirtualActivityOperations {
    override fun consumePendingNewIntent(instanceId: String, token: String): VirtualActivityPendingNewIntent? =
        activityRecordManager.consumePendingNewIntent(token)

    override fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?
    ): VirtualActivityFinishResultRecord {
        val record = activityRecordManager.resolve(token)
            ?: return finishResultRecord(
                instanceId = instanceId,
                resultCode = resultCode,
                dataIntent = dataIntent,
                reason = "ACTIVITY_RECORD_MISSING"
            )
        val sourceToken = record.resultToToken
        if (sourceToken.isNullOrBlank() || record.resultRequestCode < 0) {
            return finishResultRecord(
                instanceId = instanceId,
                resultCode = resultCode,
                dataIntent = dataIntent,
                reason = "RESULT_ROUTE_MISSING"
            )
        }
        val sourceRecord = activityRecordManager.resolve(sourceToken)
            ?: return finishResultRecord(
                instanceId = instanceId,
                sourceToken = sourceToken,
                requestCode = record.resultRequestCode,
                resultCode = resultCode,
                dataIntent = dataIntent,
                reason = "RESULT_TARGET_MISSING"
            )
        if (sourceRecord.instanceId != instanceId) {
            return finishResultRecord(
                instanceId = instanceId,
                sourceToken = sourceToken,
                requestCode = record.resultRequestCode,
                resultCode = resultCode,
                dataIntent = dataIntent,
                reason = "RESULT_TARGET_INSTANCE_MISMATCH"
            )
        }
        val updated = activityRecordManager.setResult(
            token = sourceToken,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = record.resultRequestCode
        )
        return finishResultRecord(
            instanceId = instanceId,
            sourceToken = sourceToken,
            requestCode = record.resultRequestCode,
            resultCode = resultCode,
            dataIntent = dataIntent,
            recorded = updated != null,
            reason = if (updated != null) "" else "RESULT_RECORD_UPDATE_FAILED"
        )
    }

    override fun setActivityResult(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?,
        requestCode: Int,
        resultWho: String?,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): Boolean =
        activityRecordManager.setResult(
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ) != null

    override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? =
        activityRecordManager.consumeResult(token)

    override fun consumeActivityResultForResumeFallback(instanceId: String, token: String): VirtualActivityResult? =
        activityRecordManager.consumeResultForResumeFallback(token)

    override fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): Boolean =
        activityRecordManager.markResultDispatchState(
            token = token,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ) != null

    override fun finishActivity(instanceId: String, token: String): Boolean =
        activityRecordManager.finish(token) != null

    private fun finishResultRecord(
        sourceToken: String? = null,
        instanceId: String,
        requestCode: Int = -1,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?,
        recorded: Boolean = false,
        reason: String
    ): VirtualActivityFinishResultRecord = VirtualActivityFinishResultRecord(
        instanceId = instanceId,
        sourceToken = sourceToken,
        requestCode = requestCode,
        resultCode = resultCode,
        dataIntent = dataIntent,
        recorded = recorded,
        reason = reason
    )
}

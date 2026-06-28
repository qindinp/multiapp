package com.multiapp.app.container

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.multiapp.core.loader.VirtualServiceDispatchResult
import com.multiapp.core.loader.VirtualServiceDispatcher
import com.multiapp.core.loader.VirtualServiceManager

/** Host-declared Service proxy slot for v2 hosted containers. */
class StubService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val instanceId = intent?.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID).orEmpty()
        val originPackage = intent?.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
        val guestService = intent?.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME).orEmpty()
        val reason = intent?.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON).orEmpty()
        val foreground = intent?.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) ?: false

        Log.i(
            TAG,
            "StubService received guest start: instanceId=$instanceId, " +
                "origin=$originPackage, guest=$guestService, reason=$reason, startId=$startId"
        )
        val dispatchResult = VirtualServiceDispatcher(hostContext = this).dispatch(intent, flags, startId)
        val evidence = dispatchResult.toEvidenceFields(
            fallbackInstanceId = instanceId,
            fallbackOriginPackageName = originPackage,
            fallbackGuestServiceClassName = guestService,
            fallbackReason = reason,
            fallbackForeground = foreground,
            startId = startId
        )
        if (evidence.instanceId.isNotBlank()) {
            writeServiceEvidence(evidence)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun writeServiceEvidence(evidence: ServiceEvidenceFields) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
                instanceId = evidence.instanceId,
                component = "service-proxy",
                fields = linkedMapOf(
                    "status" to evidence.status,
                    "stage" to "SERVICE_RUNTIME",
                    "originPackageName" to evidence.originPackageName,
                    "guestServiceClassName" to evidence.guestServiceClassName,
                    "reason" to evidence.reason,
                    "foreground" to evidence.foreground,
                    "startId" to evidence.startId,
                    "stubStopped" to true,
                    "guestRecordCached" to evidence.guestRecordCached,
                    "lifecycle" to evidence.lifecycle,
                    "lifecycleSuccess" to evidence.lifecycleSuccess,
                    "startCommandResult" to evidence.startCommandResult,
                    "errorClassName" to evidence.errorClassName,
                    "errorMessage" to evidence.errorMessage,
                    "detail" to evidence.detail
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write service evidence for instanceId=${evidence.instanceId}", error)
        }
    }

    private fun VirtualServiceDispatchResult.toEvidenceFields(
        fallbackInstanceId: String,
        fallbackOriginPackageName: String,
        fallbackGuestServiceClassName: String,
        fallbackReason: String,
        fallbackForeground: Boolean,
        startId: Int
    ): ServiceEvidenceFields {
        val request = startRequest
        val status: String
        val lifecycle: String
        val detail: String
        val lifecycleSuccess: Boolean?
        val guestRecordCached: Boolean
        val startCommandResultValue: Int?
        val errorClassName: String?
        val errorMessage: String?
        when (this) {
            is VirtualServiceDispatchResult.ServiceStarted -> {
                status = "STARTED"
                lifecycle = lifecycleEvidence.event.name
                detail = "startCommandResult=$startCommandResult"
                lifecycleSuccess = lifecycleEvidence.success
                guestRecordCached = lifecycleEvidence.cached
                startCommandResultValue = lifecycleEvidence.startCommandResult
                errorClassName = lifecycleEvidence.errorClassName
                errorMessage = lifecycleEvidence.errorMessage
            }
            is VirtualServiceDispatchResult.RuntimeNotBound -> {
                status = "RUNTIME_NOT_BOUND"
                lifecycle = "NOT_STARTED"
                detail = "process runtime is not bound"
                lifecycleSuccess = null
                guestRecordCached = false
                startCommandResultValue = null
                errorClassName = null
                errorMessage = null
            }
            is VirtualServiceDispatchResult.RuntimeIncomplete -> {
                status = "RUNTIME_INCOMPLETE"
                lifecycle = "NOT_STARTED"
                detail = reason
                lifecycleSuccess = null
                guestRecordCached = false
                startCommandResultValue = null
                errorClassName = null
                errorMessage = null
            }
            is VirtualServiceDispatchResult.Unsupported -> {
                status = "UNSUPPORTED"
                lifecycle = "NOT_STARTED"
                detail = reason
                lifecycleSuccess = null
                guestRecordCached = false
                startCommandResultValue = null
                errorClassName = null
                errorMessage = null
            }
            is VirtualServiceDispatchResult.ServiceCreateFailed -> {
                status = "CREATE_FAILED"
                lifecycle = lifecycleEvidence.event.name
                detail = error.message ?: error.javaClass.name
                lifecycleSuccess = lifecycleEvidence.success
                guestRecordCached = lifecycleEvidence.cached
                startCommandResultValue = lifecycleEvidence.startCommandResult
                errorClassName = lifecycleEvidence.errorClassName
                errorMessage = lifecycleEvidence.errorMessage
            }
            is VirtualServiceDispatchResult.ServiceAttachFailed -> {
                status = "ATTACH_FAILED"
                lifecycle = lifecycleEvidence.event.name
                detail = error.message ?: error.javaClass.name
                lifecycleSuccess = lifecycleEvidence.success
                guestRecordCached = lifecycleEvidence.cached
                startCommandResultValue = lifecycleEvidence.startCommandResult
                errorClassName = lifecycleEvidence.errorClassName
                errorMessage = lifecycleEvidence.errorMessage
            }
            is VirtualServiceDispatchResult.ServiceOnCreateFailed -> {
                status = "ON_CREATE_FAILED"
                lifecycle = lifecycleEvidence.event.name
                detail = error.message ?: error.javaClass.name
                lifecycleSuccess = lifecycleEvidence.success
                guestRecordCached = lifecycleEvidence.cached
                startCommandResultValue = lifecycleEvidence.startCommandResult
                errorClassName = lifecycleEvidence.errorClassName
                errorMessage = lifecycleEvidence.errorMessage
            }
            is VirtualServiceDispatchResult.ServiceOnStartCommandFailed -> {
                status = "ON_START_COMMAND_FAILED"
                lifecycle = lifecycleEvidence.event.name
                detail = error.message ?: error.javaClass.name
                lifecycleSuccess = lifecycleEvidence.success
                guestRecordCached = lifecycleEvidence.cached
                startCommandResultValue = lifecycleEvidence.startCommandResult
                errorClassName = lifecycleEvidence.errorClassName
                errorMessage = lifecycleEvidence.errorMessage
            }
            is VirtualServiceDispatchResult.InvalidProxyIntent -> {
                status = "INVALID_PROXY_INTENT"
                lifecycle = "NOT_STARTED"
                detail = reason
                lifecycleSuccess = null
                guestRecordCached = false
                startCommandResultValue = null
                errorClassName = null
                errorMessage = null
            }
            is VirtualServiceDispatchResult.InstanceNotFound -> {
                status = "INSTANCE_NOT_FOUND"
                lifecycle = "NOT_STARTED"
                detail = "instance snapshot is not registered"
                lifecycleSuccess = null
                guestRecordCached = false
                startCommandResultValue = null
                errorClassName = null
                errorMessage = null
            }
        }
        return ServiceEvidenceFields(
            instanceId = request?.instanceId ?: fallbackInstanceId,
            originPackageName = request?.originPackageName ?: fallbackOriginPackageName,
            guestServiceClassName = request?.guestServiceClassName ?: fallbackGuestServiceClassName,
            reason = request?.reason ?: fallbackReason,
            foreground = request?.foreground ?: fallbackForeground,
            startId = startId,
            status = status,
            lifecycle = lifecycle,
            lifecycleSuccess = lifecycleSuccess,
            guestRecordCached = guestRecordCached,
            startCommandResult = startCommandResultValue,
            errorClassName = errorClassName,
            errorMessage = errorMessage,
            detail = detail
        )
    }

    private data class ServiceEvidenceFields(
        val instanceId: String,
        val originPackageName: String,
        val guestServiceClassName: String,
        val reason: String,
        val foreground: Boolean,
        val startId: Int,
        val status: String,
        val lifecycle: String,
        val lifecycleSuccess: Boolean?,
        val guestRecordCached: Boolean,
        val startCommandResult: Int?,
        val errorClassName: String?,
        val errorMessage: String?,
        val detail: String
    )

    companion object {
        private const val TAG = "StubService"
    }
}

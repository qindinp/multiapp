package com.multiapp.app.container

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.multiapp.core.loader.VirtualServiceDispatchResult
import com.multiapp.core.loader.VirtualServiceDispatcher
import com.multiapp.core.loader.VirtualServiceIntentStore
import com.multiapp.core.loader.VirtualServiceManager
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceStartRequest

/** Host-declared Service proxy slot for v2 hosted containers. */
open class StubService : Service() {
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startRequest = intent?.let { VirtualServiceManager(packageName).requestFromProxyIntent(it) }
        val instanceId = startRequest?.instanceId
            ?: intent?.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID).orEmpty()
        val originPackage = startRequest?.originPackageName
            ?: intent?.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
        val guestService = startRequest?.guestServiceClassName
            ?: intent?.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME).orEmpty()
        val reason = startRequest?.reason
            ?: intent?.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON).orEmpty()
        val foreground = startRequest?.foreground
            ?: (intent?.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) ?: false)
        val foregroundStatus = enterForegroundIfNeeded(foreground)
        val hasReusableRuntime = instanceId.isNotBlank() &&
            VirtualProcessRuntime.global.reusableResult(instanceId) != null
        if (shouldBindRuntimeAsync(instanceId, hasReusableRuntime)) {
            Log.i(
                TAG,
                "StubService scheduling async runtime bind: instanceId=$instanceId, " +
                    "origin=$originPackage, guest=$guestService, reason=$reason, " +
                    "foreground=$foreground, foregroundStatus=$foregroundStatus, startId=$startId"
            )
            bindRuntimeAndDispatchAsync(
                intent = intent,
                startRequest = startRequest,
                flags = flags,
                startId = startId,
                instanceId = instanceId,
                originPackage = originPackage,
                guestService = guestService,
                reason = reason,
                foreground = foreground,
                foregroundStatus = foregroundStatus
            )
            return START_NOT_STICKY
        }

        val runtimeBindResult = if (startRequest != null) {
            HostedServiceRuntimeBinder().ensureBound(this, startRequest)
        } else {
            HostedServiceRuntimeBinder().ensureBound(this, intent)
        }
        return dispatchServiceStart(
            intent = intent,
            startRequest = startRequest,
            flags = flags,
            startId = startId,
            instanceId = instanceId,
            originPackage = originPackage,
            guestService = guestService,
            reason = reason,
            foreground = foreground,
            foregroundStatus = foregroundStatus,
            runtimeBindResult = runtimeBindResult,
            hostReturnMode = HOST_RETURN_MODE_SYNC_GUEST_RESULT
        )
    }

    private fun bindRuntimeAndDispatchAsync(
        intent: Intent?,
        startRequest: VirtualServiceStartRequest?,
        flags: Int,
        startId: Int,
        instanceId: String,
        originPackage: String,
        guestService: String,
        reason: String,
        foreground: Boolean,
        foregroundStatus: String
    ) {
        val hostContext = applicationContext ?: this
        val completionHandler = mainHandler
        Thread(
            {
                val runtimeBindResult = runCatching {
                    if (startRequest != null) {
                        HostedServiceRuntimeBinder().ensureBound(hostContext, startRequest)
                    } else {
                        HostedServiceRuntimeBinder().ensureBound(hostContext, intent)
                    }
                }.getOrElse { error ->
                    HostedServiceRuntimeBindResult.Failed(
                        instanceId = instanceId,
                        processSlot = startRequest?.processSlot,
                        errorClassName = error.javaClass.name,
                        errorMessage = error.message,
                        detail = "runtimeBindCrashed"
                    )
                }
                completionHandler.post {
                    dispatchServiceStart(
                        intent = intent,
                        startRequest = startRequest,
                        flags = flags,
                        startId = startId,
                        instanceId = instanceId,
                        originPackage = originPackage,
                        guestService = guestService,
                        reason = reason,
                        foreground = foreground,
                        foregroundStatus = foregroundStatus,
                        runtimeBindResult = runtimeBindResult,
                        hostReturnMode = HOST_RETURN_MODE_ASYNC_DEFAULT
                    )
                }
            },
            "multiapp-service-bind-${instanceId.take(8)}"
        ).start()
    }

    private fun dispatchServiceStart(
        intent: Intent?,
        startRequest: VirtualServiceStartRequest?,
        flags: Int,
        startId: Int,
        instanceId: String,
        originPackage: String,
        guestService: String,
        reason: String,
        foreground: Boolean,
        foregroundStatus: String,
        runtimeBindResult: HostedServiceRuntimeBindResult,
        hostReturnMode: String
    ): Int {
        Log.i(
            TAG,
            "StubService received guest start: instanceId=$instanceId, " +
                "origin=$originPackage, guest=$guestService, reason=$reason, " +
                "foreground=$foreground, foregroundStatus=$foregroundStatus, " +
                "runtimeBindStatus=${runtimeBindResult.status}, startId=$startId"
        )
        val dispatchResult = if (startRequest != null) {
            VirtualServiceDispatcher(hostContext = this).dispatch(startRequest, flags, startId)
        } else {
            VirtualServiceDispatcher(hostContext = this).dispatch(intent, flags, startId)
        }
        val evidence = dispatchResult.toEvidenceFields(
            fallbackInstanceId = instanceId,
            fallbackOriginPackageName = originPackage,
            fallbackGuestServiceClassName = guestService,
            fallbackReason = reason,
            fallbackForeground = foreground,
            foregroundStatus = foregroundStatus,
            runtimeBindResult = runtimeBindResult,
            startId = startId
        )
        val hostStartCommandResult = hostStartCommandResult(
            guestStartCommandResult = evidence.startCommandResult,
            asyncDispatch = hostReturnMode == HOST_RETURN_MODE_ASYNC_DEFAULT
        )
        val stopDecision = evidence.stubStopDecision()
        val finalEvidence = evidence.copy(
            stubStopped = stopDecision.stop,
            stubStopDecision = stopDecision.reason,
            foregroundHeld = foreground && foregroundStatus == FOREGROUND_STATUS_STARTED && !stopDecision.stop,
            hostStartCommandResult = hostStartCommandResult,
            hostStartCommandReturnMode = hostReturnMode
        )
        if (finalEvidence.instanceId.isNotBlank()) {
            writeServiceEvidence(finalEvidence)
        }
        if (stopDecision.stop && foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (stopDecision.stop) {
            stopSelf(startId)
        }
        VirtualServiceIntentStore.clear(startRequest?.proxyToken)
        return hostStartCommandResult
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
                    "foregroundStatus" to evidence.foregroundStatus,
                    "runtimeBindStatus" to evidence.runtimeBindStatus,
                    "runtimeBindDetail" to evidence.runtimeBindDetail,
                    "runtimeBindProcessSlot" to evidence.runtimeBindProcessSlot.orEmpty(),
                    "runtimeBindErrorClassName" to evidence.runtimeBindErrorClassName,
                    "runtimeBindErrorMessage" to evidence.runtimeBindErrorMessage,
                    "foregroundHeld" to evidence.foregroundHeld,
                    "guestForegroundRequested" to evidence.guestForegroundRequested,
                    "guestForegroundNotificationId" to evidence.guestForegroundNotificationId,
                    "guestForegroundServiceType" to evidence.guestForegroundServiceType,
                    "guestForegroundLifecycleImplemented" to false,
                    "startId" to evidence.startId,
                    "stubStopped" to evidence.stubStopped,
                    "stubStopDecision" to evidence.stubStopDecision,
                    "guestRecordCached" to evidence.guestRecordCached,
                    "activeStartCount" to evidence.activeStartCount,
                    "activeBindCount" to evidence.activeBindCount,
                    "lifecycle" to evidence.lifecycle,
                    "lifecycleSuccess" to evidence.lifecycleSuccess,
                    "startCommandResult" to evidence.startCommandResult,
                    "hostStartCommandResult" to evidence.hostStartCommandResult,
                    "hostStartCommandReturnMode" to evidence.hostStartCommandReturnMode,
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
        foregroundStatus: String,
        runtimeBindResult: HostedServiceRuntimeBindResult,
        startId: Int
    ): ServiceEvidenceFields {
        val request = startRequest
        val status: String
        val lifecycle: String
        val detail: String
        val lifecycleSuccess: Boolean?
        val guestRecordCached: Boolean
        val startCommandResultValue: Int?
        val activeStartCount: Int
        val activeBindCount: Int
        val guestForegroundRequested: Boolean
        val guestForegroundNotificationId: Int?
        val guestForegroundServiceType: Int
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
                activeStartCount = lifecycleEvidence.activeStartCount
                activeBindCount = lifecycleEvidence.activeBindCount
                guestForegroundRequested = lifecycleEvidence.foreground
                guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
                guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
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
                activeStartCount = 0
                activeBindCount = 0
                guestForegroundRequested = false
                guestForegroundNotificationId = null
                guestForegroundServiceType = 0
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
                activeStartCount = 0
                activeBindCount = 0
                guestForegroundRequested = false
                guestForegroundNotificationId = null
                guestForegroundServiceType = 0
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
                activeStartCount = 0
                activeBindCount = 0
                guestForegroundRequested = false
                guestForegroundNotificationId = null
                guestForegroundServiceType = 0
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
                activeStartCount = lifecycleEvidence.activeStartCount
                activeBindCount = lifecycleEvidence.activeBindCount
                guestForegroundRequested = lifecycleEvidence.foreground
                guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
                guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
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
                activeStartCount = lifecycleEvidence.activeStartCount
                activeBindCount = lifecycleEvidence.activeBindCount
                guestForegroundRequested = lifecycleEvidence.foreground
                guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
                guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
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
                activeStartCount = lifecycleEvidence.activeStartCount
                activeBindCount = lifecycleEvidence.activeBindCount
                guestForegroundRequested = lifecycleEvidence.foreground
                guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
                guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
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
                activeStartCount = lifecycleEvidence.activeStartCount
                activeBindCount = lifecycleEvidence.activeBindCount
                guestForegroundRequested = lifecycleEvidence.foreground
                guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
                guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
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
                activeStartCount = 0
                activeBindCount = 0
                guestForegroundRequested = false
                guestForegroundNotificationId = null
                guestForegroundServiceType = 0
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
                activeStartCount = 0
                activeBindCount = 0
                guestForegroundRequested = false
                guestForegroundNotificationId = null
                guestForegroundServiceType = 0
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
            foregroundStatus = foregroundStatus,
            runtimeBindStatus = runtimeBindResult.status,
            runtimeBindDetail = runtimeBindResult.detail,
            runtimeBindProcessSlot = runtimeBindResult.processSlotForEvidence() ?: request?.processSlot,
            runtimeBindErrorClassName = (runtimeBindResult as? HostedServiceRuntimeBindResult.Failed)?.errorClassName,
            runtimeBindErrorMessage = (runtimeBindResult as? HostedServiceRuntimeBindResult.Failed)?.errorMessage,
            startId = startId,
            status = status,
            lifecycle = lifecycle,
            lifecycleSuccess = lifecycleSuccess,
            guestRecordCached = guestRecordCached,
            startCommandResult = startCommandResultValue,
            activeStartCount = activeStartCount,
            activeBindCount = activeBindCount,
            guestForegroundRequested = guestForegroundRequested,
            guestForegroundNotificationId = guestForegroundNotificationId,
            guestForegroundServiceType = guestForegroundServiceType,
            errorClassName = errorClassName,
            errorMessage = errorMessage,
            detail = detail,
            foregroundHeld = false,
            stubStopped = true,
            stubStopDecision = "UNDECIDED",
            hostStartCommandResult = START_NOT_STICKY,
            hostStartCommandReturnMode = HOST_RETURN_MODE_UNDECIDED
        )
    }

    private fun ServiceEvidenceFields.stubStopDecision(): StubStopDecision {
        if (status != "STARTED") {
            return StubStopDecision(stop = true, reason = "STOP_DISPATCH_$status")
        }
        if (foreground && foregroundStatus != FOREGROUND_STATUS_STARTED) {
            return StubStopDecision(stop = true, reason = "STOP_FOREGROUND_NOT_STARTED")
        }
        if (lifecycleSuccess != true) {
            return StubStopDecision(stop = true, reason = "STOP_LIFECYCLE_NOT_SUCCESSFUL")
        }
        if (activeStartCount > 0 || activeBindCount > 0 || foreground) {
            return StubStopDecision(stop = false, reason = "KEEP_GUEST_SERVICE_ACTIVE")
        }
        return StubStopDecision(stop = true, reason = "STOP_NO_ACTIVE_GUEST_RECORD")
    }

    private data class ServiceEvidenceFields(
        val instanceId: String,
        val originPackageName: String,
        val guestServiceClassName: String,
        val reason: String,
        val foreground: Boolean,
        val foregroundStatus: String,
        val runtimeBindStatus: String,
        val runtimeBindDetail: String,
        val runtimeBindProcessSlot: String?,
        val runtimeBindErrorClassName: String?,
        val runtimeBindErrorMessage: String?,
        val foregroundHeld: Boolean,
        val startId: Int,
        val status: String,
        val lifecycle: String,
        val lifecycleSuccess: Boolean?,
        val guestRecordCached: Boolean,
        val activeStartCount: Int,
        val activeBindCount: Int,
        val guestForegroundRequested: Boolean,
        val guestForegroundNotificationId: Int?,
        val guestForegroundServiceType: Int,
        val startCommandResult: Int?,
        val errorClassName: String?,
        val errorMessage: String?,
        val detail: String,
        val stubStopped: Boolean,
        val stubStopDecision: String,
        val hostStartCommandResult: Int,
        val hostStartCommandReturnMode: String
    )

    private data class StubStopDecision(
        val stop: Boolean,
        val reason: String
    )

    companion object {
        private const val TAG = "StubService"
        private const val FOREGROUND_NOTIFICATION_ID = 10042
        private const val FOREGROUND_CHANNEL_ID = "multiapp_proxy_service"
        private const val FOREGROUND_STATUS_STARTED = "STARTED"
        private const val HOST_RETURN_MODE_SYNC_GUEST_RESULT = "SYNC_GUEST_RESULT"
        private const val HOST_RETURN_MODE_ASYNC_DEFAULT = "ASYNC_HOST_ALREADY_RETURNED_DEFAULT"
        private const val HOST_RETURN_MODE_UNDECIDED = "UNDECIDED"

        internal fun shouldBindRuntimeAsync(
            instanceId: String,
            hasReusableRuntime: Boolean
        ): Boolean = instanceId.isNotBlank() && !hasReusableRuntime

        internal fun hostStartCommandResult(
            guestStartCommandResult: Int?,
            asyncDispatch: Boolean
        ): Int {
            return if (asyncDispatch) {
                Service.START_NOT_STICKY
            } else {
                guestStartCommandResult ?: Service.START_NOT_STICKY
            }
        }
    }

    private fun HostedServiceRuntimeBindResult.processSlotForEvidence(): String? = when (this) {
        is HostedServiceRuntimeBindResult.Bound -> processSlot
        is HostedServiceRuntimeBindResult.Failed -> processSlot
        is HostedServiceRuntimeBindResult.NotRequested -> null
    }

    private fun enterForegroundIfNeeded(foreground: Boolean): String {
        if (!foreground) return "SKIPPED"
        return runCatching {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            "STARTED"
        }.getOrElse { error ->
            Log.w(TAG, "Unable to enter foreground for hosted service proxy", error)
            "FAILED:${error.javaClass.name}:${error.message.orEmpty()}"
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "MultiApp service proxy",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FOREGROUND_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: com.multiapp.app.R.mipmap.ic_launcher)
            .setContentTitle("MultiApp")
            .setContentText("Running hosted service")
            .setOngoing(true)
            .build()
    }
}

class StubServiceV0 : StubService()
class StubServiceV1 : StubService()
class StubServiceV2 : StubService()
class StubServiceV3 : StubService()
class StubServiceV4 : StubService()
class StubServiceV5 : StubService()
class StubServiceV6 : StubService()
class StubServiceV7 : StubService()

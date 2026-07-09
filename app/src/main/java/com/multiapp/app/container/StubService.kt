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
import com.multiapp.core.engine.DefaultEngineServiceDispatcher
import com.multiapp.core.engine.DefaultEngineServiceRouter
import com.multiapp.core.engine.EngineServiceEvidenceFields
import com.multiapp.core.engine.EngineServiceDispatchRequest
import com.multiapp.core.engine.EngineServiceRuntimeBindEvidence
import com.multiapp.core.engine.EngineServiceRouter
import com.multiapp.core.engine.EngineServiceStartRoute
import com.multiapp.core.engine.toEngineEvidenceFields

/** Host-declared Service proxy slot for v2 hosted containers. */
open class StubService : Service() {
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }
    private val serviceRouter: EngineServiceRouter by lazy(LazyThreadSafetyMode.NONE) {
        DefaultEngineServiceRouter()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startRoute = serviceRouter.routeFromProxyIntent(packageName, intent)
        val launchInfo = serviceRouter.launchInfo(intent, startRoute)
        val instanceId = launchInfo.instanceId
        val originPackage = launchInfo.originPackageName
        val guestService = launchInfo.guestServiceClassName
        val reason = launchInfo.reason
        val foreground = launchInfo.foreground
        val foregroundStatus = enterForegroundIfNeeded(foreground)
        val hasReusableRuntime = serviceRouter.hasReusableRuntime(instanceId)
        if (shouldBindRuntimeAsync(instanceId, hasReusableRuntime)) {
            Log.i(
                TAG,
                "StubService scheduling async runtime bind: instanceId=$instanceId, " +
                    "origin=$originPackage, guest=$guestService, reason=$reason, " +
                    "foreground=$foreground, foregroundStatus=$foregroundStatus, startId=$startId"
            )
            bindRuntimeAndDispatchAsync(
                intent = intent,
                startRoute = startRoute,
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

        val runtimeBindResult = if (startRoute != null) {
            HostedServiceRuntimeBinder().ensureBound(this, startRoute)
        } else {
            HostedServiceRuntimeBinder().ensureBound(this, intent)
        }
        return dispatchServiceStart(
            intent = intent,
            startRoute = startRoute,
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
        startRoute: EngineServiceStartRoute?,
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
                    if (startRoute != null) {
                        HostedServiceRuntimeBinder().ensureBound(hostContext, startRoute)
                    } else {
                        HostedServiceRuntimeBinder().ensureBound(hostContext, intent)
                    }
                }.getOrElse { error ->
                    HostedServiceRuntimeBindResult.Failed(
                        instanceId = instanceId,
                        processSlot = startRoute?.processSlot,
                        errorClassName = error.javaClass.name,
                        errorMessage = error.message,
                        detail = "runtimeBindCrashed"
                    )
                }
                completionHandler.post {
                    dispatchServiceStart(
                        intent = intent,
                        startRoute = startRoute,
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
        startRoute: EngineServiceStartRoute?,
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
        val dispatchResult = DefaultEngineServiceDispatcher().dispatch(
            EngineServiceDispatchRequest(
                hostContext = this,
                proxyIntent = intent,
                route = startRoute,
                flags = flags,
                startId = startId
            )
        )
        val evidence = dispatchResult.toEngineEvidenceFields(
            fallbackInstanceId = instanceId,
            fallbackOriginPackageName = originPackage,
            fallbackGuestServiceClassName = guestService,
            fallbackReason = reason,
            fallbackForeground = foreground,
            foregroundStatus = foregroundStatus,
            runtimeBindEvidence = runtimeBindResult.toEngineRuntimeBindEvidence(),
            startId = startId,
            defaultHostStartCommandResult = START_NOT_STICKY,
            undecidedHostReturnMode = HOST_RETURN_MODE_UNDECIDED
        )
        val hostStartCommandResult = hostStartCommandResult(
            guestStartCommandResult = evidence.startCommandResult,
            asyncDispatch = hostReturnMode == HOST_RETURN_MODE_ASYNC_DEFAULT
        )
        val stopDecision = evidence.stubStopDecision(FOREGROUND_STATUS_STARTED)
        val finalEvidence = evidence.withHostOutcome(
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
        serviceRouter.clearProxyToken(startRoute)
        return hostStartCommandResult
    }

    private fun writeServiceEvidence(evidence: EngineServiceEvidenceFields) {
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

    private fun HostedServiceRuntimeBindResult.toEngineRuntimeBindEvidence(): EngineServiceRuntimeBindEvidence =
        EngineServiceRuntimeBindEvidence(
            status = status,
            detail = detail,
            processSlot = when (this) {
                is HostedServiceRuntimeBindResult.Bound -> processSlot
                is HostedServiceRuntimeBindResult.Failed -> processSlot
                is HostedServiceRuntimeBindResult.NotRequested -> null
            },
            errorClassName = (this as? HostedServiceRuntimeBindResult.Failed)?.errorClassName,
            errorMessage = (this as? HostedServiceRuntimeBindResult.Failed)?.errorMessage
        )

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

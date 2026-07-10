package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualServiceDispatchResult
import com.multiapp.core.loader.VirtualServiceLifecycleEvidence
import com.multiapp.core.loader.VirtualServiceStartRequest
import com.multiapp.core.model.engine.EngineResultStatus

data class EngineServiceStartRequestSnapshot(
    val instanceId: String,
    val originPackageName: String,
    val guestServiceClassName: String,
    val reason: String,
    val foreground: Boolean,
    val proxyToken: String?,
    val processSlot: String?
) {
    companion object {
        fun fromLoader(request: VirtualServiceStartRequest): EngineServiceStartRequestSnapshot =
            EngineServiceStartRequestSnapshot(
                instanceId = request.instanceId,
                originPackageName = request.originPackageName,
                guestServiceClassName = request.guestServiceClassName,
                reason = request.reason,
                foreground = request.foreground,
                proxyToken = request.proxyToken,
                processSlot = request.processSlot
            )
    }
}

data class EngineServiceLifecycleEvidence(
    val instanceId: String,
    val guestServiceClassName: String,
    val event: String,
    val success: Boolean,
    val cached: Boolean = false,
    val startCommandResult: Int? = null,
    val activeStartCount: Int = 0,
    val activeBindCount: Int = 0,
    val foreground: Boolean = false,
    val foregroundNotificationId: Int? = null,
    val foregroundServiceType: Int = 0,
    val errorClassName: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun fromLoader(evidence: VirtualServiceLifecycleEvidence): EngineServiceLifecycleEvidence =
            EngineServiceLifecycleEvidence(
                instanceId = evidence.instanceId,
                guestServiceClassName = evidence.guestServiceClassName,
                event = evidence.event.name,
                success = evidence.success,
                cached = evidence.cached,
                startCommandResult = evidence.startCommandResult,
                activeStartCount = evidence.activeStartCount,
                activeBindCount = evidence.activeBindCount,
                foreground = evidence.foreground,
                foregroundNotificationId = evidence.foregroundNotificationId,
                foregroundServiceType = evidence.foregroundServiceType,
                errorClassName = evidence.errorClassName,
                errorMessage = evidence.errorMessage
            )
    }
}

sealed class EngineServiceDispatchResult {
    abstract val startRequest: EngineServiceStartRequestSnapshot?

    data class ServiceStarted(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val cached: Boolean,
        val startCommandResult: Int,
        val lifecycleEvidence: EngineServiceLifecycleEvidence
    ) : EngineServiceDispatchResult()

    data class RuntimeNotBound(
        override val startRequest: EngineServiceStartRequestSnapshot
    ) : EngineServiceDispatchResult()

    data class RuntimeIncomplete(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val reason: String
    ) : EngineServiceDispatchResult()

    data class Unsupported(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val reason: String
    ) : EngineServiceDispatchResult()

    data class ServiceCreateFailed(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val errorClassName: String,
        val errorMessage: String?,
        val lifecycleEvidence: EngineServiceLifecycleEvidence
    ) : EngineServiceDispatchResult()

    data class ServiceAttachFailed(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val errorClassName: String,
        val errorMessage: String?,
        val lifecycleEvidence: EngineServiceLifecycleEvidence
    ) : EngineServiceDispatchResult()

    data class ServiceOnCreateFailed(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val errorClassName: String,
        val errorMessage: String?,
        val lifecycleEvidence: EngineServiceLifecycleEvidence
    ) : EngineServiceDispatchResult()

    data class ServiceOnStartCommandFailed(
        override val startRequest: EngineServiceStartRequestSnapshot,
        val cached: Boolean,
        val errorClassName: String,
        val errorMessage: String?,
        val lifecycleEvidence: EngineServiceLifecycleEvidence
    ) : EngineServiceDispatchResult()

    data class InvalidProxyIntent(val reason: String) : EngineServiceDispatchResult() {
        override val startRequest: EngineServiceStartRequestSnapshot? = null
    }

    data class InstanceNotFound(
        override val startRequest: EngineServiceStartRequestSnapshot
    ) : EngineServiceDispatchResult()

    companion object {
        fun fromLoader(result: VirtualServiceDispatchResult): EngineServiceDispatchResult =
            when (result) {
                is VirtualServiceDispatchResult.ServiceStarted -> ServiceStarted(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    cached = result.cached,
                    startCommandResult = result.startCommandResult,
                    lifecycleEvidence = EngineServiceLifecycleEvidence.fromLoader(result.lifecycleEvidence)
                )
                is VirtualServiceDispatchResult.RuntimeNotBound -> RuntimeNotBound(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest)
                )
                is VirtualServiceDispatchResult.RuntimeIncomplete -> RuntimeIncomplete(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    reason = result.reason
                )
                is VirtualServiceDispatchResult.Unsupported -> Unsupported(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    reason = result.reason
                )
                is VirtualServiceDispatchResult.ServiceCreateFailed -> ServiceCreateFailed(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    errorClassName = result.error.javaClass.name,
                    errorMessage = result.error.message,
                    lifecycleEvidence = EngineServiceLifecycleEvidence.fromLoader(result.lifecycleEvidence)
                )
                is VirtualServiceDispatchResult.ServiceAttachFailed -> ServiceAttachFailed(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    errorClassName = result.error.javaClass.name,
                    errorMessage = result.error.message,
                    lifecycleEvidence = EngineServiceLifecycleEvidence.fromLoader(result.lifecycleEvidence)
                )
                is VirtualServiceDispatchResult.ServiceOnCreateFailed -> ServiceOnCreateFailed(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    errorClassName = result.error.javaClass.name,
                    errorMessage = result.error.message,
                    lifecycleEvidence = EngineServiceLifecycleEvidence.fromLoader(result.lifecycleEvidence)
                )
                is VirtualServiceDispatchResult.ServiceOnStartCommandFailed -> ServiceOnStartCommandFailed(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest),
                    cached = result.cached,
                    errorClassName = result.error.javaClass.name,
                    errorMessage = result.error.message,
                    lifecycleEvidence = EngineServiceLifecycleEvidence.fromLoader(result.lifecycleEvidence)
                )
                is VirtualServiceDispatchResult.InvalidProxyIntent -> InvalidProxyIntent(result.reason)
                is VirtualServiceDispatchResult.InstanceNotFound -> InstanceNotFound(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(result.startRequest)
                )
            }
    }
}

fun EngineServiceDispatchResult.toVirtualServiceOperationResult(): VirtualServiceOperationResult? {
    val request = startRequest ?: return null
    val operation = if (request.foreground) {
        VirtualServiceOperation.START_FOREGROUND
    } else {
        VirtualServiceOperation.START
    }
    return when (this) {
        is EngineServiceDispatchResult.ServiceStarted -> VirtualServiceOperationResult(
            instanceId = request.instanceId,
            operation = operation,
            serviceClassName = request.guestServiceClassName,
            action = null,
            verdict = EngineResultStatus.PASS,
            reason = request.reason,
            started = true,
            foreground = request.foreground,
            startCommandResult = startCommandResult,
            processSlot = request.processSlot,
            activeStartCount = lifecycleEvidence.activeStartCount,
            activeBindCount = lifecycleEvidence.activeBindCount,
            cached = cached,
            message = if (cached) "loader_service_started_cached" else "loader_service_started"
        )
        is EngineServiceDispatchResult.RuntimeNotBound -> VirtualServiceOperationResult(
            instanceId = request.instanceId,
            operation = operation,
            serviceClassName = request.guestServiceClassName,
            action = null,
            verdict = EngineResultStatus.FAIL,
            reason = request.reason,
            foreground = request.foreground,
            message = "runtime_not_bound"
        )
        is EngineServiceDispatchResult.RuntimeIncomplete -> VirtualServiceOperationResult(
            instanceId = request.instanceId,
            operation = operation,
            serviceClassName = request.guestServiceClassName,
            action = null,
            verdict = EngineResultStatus.FAIL,
            reason = request.reason,
            foreground = request.foreground,
            message = "runtime_incomplete:$reason"
        )
        is EngineServiceDispatchResult.Unsupported -> VirtualServiceOperationResult(
            instanceId = request.instanceId,
            operation = operation,
            serviceClassName = request.guestServiceClassName,
            action = null,
            verdict = EngineResultStatus.UNSUPPORTED,
            reason = request.reason,
            foreground = request.foreground,
            message = "unsupported:$reason"
        )
        is EngineServiceDispatchResult.ServiceCreateFailed -> failedOperationResult(
            request = request,
            operation = operation,
            stage = "service_create_failed",
            foreground = request.foreground,
            errorClassName = errorClassName,
            errorMessage = errorMessage
        )
        is EngineServiceDispatchResult.ServiceAttachFailed -> failedOperationResult(
            request = request,
            operation = operation,
            stage = "service_attach_failed",
            foreground = request.foreground,
            errorClassName = errorClassName,
            errorMessage = errorMessage
        )
        is EngineServiceDispatchResult.ServiceOnCreateFailed -> failedOperationResult(
            request = request,
            operation = operation,
            stage = "service_on_create_failed",
            foreground = request.foreground,
            errorClassName = errorClassName,
            errorMessage = errorMessage
        )
        is EngineServiceDispatchResult.ServiceOnStartCommandFailed -> failedOperationResult(
            request = request,
            operation = operation,
            stage = "service_on_start_command_failed",
            foreground = request.foreground,
            errorClassName = errorClassName,
            errorMessage = errorMessage
        )
        is EngineServiceDispatchResult.InstanceNotFound -> VirtualServiceOperationResult(
            instanceId = request.instanceId,
            operation = operation,
            serviceClassName = request.guestServiceClassName,
            action = null,
            verdict = EngineResultStatus.FAIL,
            reason = request.reason,
            foreground = request.foreground,
            message = "instance_not_found"
        )
        is EngineServiceDispatchResult.InvalidProxyIntent -> null
    }
}

private fun failedOperationResult(
    request: EngineServiceStartRequestSnapshot,
    operation: VirtualServiceOperation,
    stage: String,
    foreground: Boolean,
    errorClassName: String,
    errorMessage: String?
): VirtualServiceOperationResult =
    VirtualServiceOperationResult(
        instanceId = request.instanceId,
        operation = operation,
        serviceClassName = request.guestServiceClassName,
        action = null,
        verdict = EngineResultStatus.FAIL,
        reason = request.reason,
        foreground = foreground,
        message = "$stage:$errorClassName:${errorMessage.orEmpty()}"
    )

data class EngineServiceRuntimeBindEvidence(
    val status: String,
    val detail: String,
    val processSlot: String?,
    val errorClassName: String?,
    val errorMessage: String?
)

data class EngineServiceEvidenceFields(
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
) {
    fun withHostOutcome(
        stubStopped: Boolean,
        stubStopDecision: String,
        foregroundHeld: Boolean,
        hostStartCommandResult: Int,
        hostStartCommandReturnMode: String
    ): EngineServiceEvidenceFields = copy(
        stubStopped = stubStopped,
        stubStopDecision = stubStopDecision,
        foregroundHeld = foregroundHeld,
        hostStartCommandResult = hostStartCommandResult,
        hostStartCommandReturnMode = hostStartCommandReturnMode
    )

    fun stubStopDecision(foregroundStartedStatus: String): EngineServiceStubStopDecision {
        if (status != "STARTED") {
            return EngineServiceStubStopDecision(stop = true, reason = "STOP_DISPATCH_$status")
        }
        if (foreground && foregroundStatus != foregroundStartedStatus) {
            return EngineServiceStubStopDecision(stop = true, reason = "STOP_FOREGROUND_NOT_STARTED")
        }
        if (lifecycleSuccess != true) {
            return EngineServiceStubStopDecision(stop = true, reason = "STOP_LIFECYCLE_NOT_SUCCESSFUL")
        }
        if (activeStartCount > 0 || activeBindCount > 0 || foreground) {
            return EngineServiceStubStopDecision(stop = false, reason = "KEEP_GUEST_SERVICE_ACTIVE")
        }
        return EngineServiceStubStopDecision(stop = true, reason = "STOP_NO_ACTIVE_GUEST_RECORD")
    }
}

data class EngineServiceStubStopDecision(
    val stop: Boolean,
    val reason: String
)

fun EngineServiceDispatchResult.toEngineEvidenceFields(
    fallbackInstanceId: String,
    fallbackOriginPackageName: String,
    fallbackGuestServiceClassName: String,
    fallbackReason: String,
    fallbackForeground: Boolean,
    foregroundStatus: String,
    runtimeBindEvidence: EngineServiceRuntimeBindEvidence,
    startId: Int,
    defaultHostStartCommandResult: Int,
    undecidedHostReturnMode: String
): EngineServiceEvidenceFields {
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
        is EngineServiceDispatchResult.ServiceStarted -> {
            status = "STARTED"
            lifecycle = lifecycleEvidence.event
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
        is EngineServiceDispatchResult.RuntimeNotBound -> {
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
        is EngineServiceDispatchResult.RuntimeIncomplete -> {
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
        is EngineServiceDispatchResult.Unsupported -> {
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
        is EngineServiceDispatchResult.ServiceCreateFailed -> {
            status = "CREATE_FAILED"
            lifecycle = lifecycleEvidence.event
            detail = this.errorMessage ?: this.errorClassName
            lifecycleSuccess = lifecycleEvidence.success
            guestRecordCached = lifecycleEvidence.cached
            startCommandResultValue = lifecycleEvidence.startCommandResult
            activeStartCount = lifecycleEvidence.activeStartCount
            activeBindCount = lifecycleEvidence.activeBindCount
            guestForegroundRequested = lifecycleEvidence.foreground
            guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
            guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
            errorClassName = lifecycleEvidence.errorClassName ?: this.errorClassName
            errorMessage = lifecycleEvidence.errorMessage ?: this.errorMessage
        }
        is EngineServiceDispatchResult.ServiceAttachFailed -> {
            status = "ATTACH_FAILED"
            lifecycle = lifecycleEvidence.event
            detail = this.errorMessage ?: this.errorClassName
            lifecycleSuccess = lifecycleEvidence.success
            guestRecordCached = lifecycleEvidence.cached
            startCommandResultValue = lifecycleEvidence.startCommandResult
            activeStartCount = lifecycleEvidence.activeStartCount
            activeBindCount = lifecycleEvidence.activeBindCount
            guestForegroundRequested = lifecycleEvidence.foreground
            guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
            guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
            errorClassName = lifecycleEvidence.errorClassName ?: this.errorClassName
            errorMessage = lifecycleEvidence.errorMessage ?: this.errorMessage
        }
        is EngineServiceDispatchResult.ServiceOnCreateFailed -> {
            status = "ON_CREATE_FAILED"
            lifecycle = lifecycleEvidence.event
            detail = this.errorMessage ?: this.errorClassName
            lifecycleSuccess = lifecycleEvidence.success
            guestRecordCached = lifecycleEvidence.cached
            startCommandResultValue = lifecycleEvidence.startCommandResult
            activeStartCount = lifecycleEvidence.activeStartCount
            activeBindCount = lifecycleEvidence.activeBindCount
            guestForegroundRequested = lifecycleEvidence.foreground
            guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
            guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
            errorClassName = lifecycleEvidence.errorClassName ?: this.errorClassName
            errorMessage = lifecycleEvidence.errorMessage ?: this.errorMessage
        }
        is EngineServiceDispatchResult.ServiceOnStartCommandFailed -> {
            status = "ON_START_COMMAND_FAILED"
            lifecycle = lifecycleEvidence.event
            detail = this.errorMessage ?: this.errorClassName
            lifecycleSuccess = lifecycleEvidence.success
            guestRecordCached = lifecycleEvidence.cached
            startCommandResultValue = lifecycleEvidence.startCommandResult
            activeStartCount = lifecycleEvidence.activeStartCount
            activeBindCount = lifecycleEvidence.activeBindCount
            guestForegroundRequested = lifecycleEvidence.foreground
            guestForegroundNotificationId = lifecycleEvidence.foregroundNotificationId
            guestForegroundServiceType = lifecycleEvidence.foregroundServiceType
            errorClassName = lifecycleEvidence.errorClassName ?: this.errorClassName
            errorMessage = lifecycleEvidence.errorMessage ?: this.errorMessage
        }
        is EngineServiceDispatchResult.InvalidProxyIntent -> {
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
        is EngineServiceDispatchResult.InstanceNotFound -> {
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
    return EngineServiceEvidenceFields(
        instanceId = request?.instanceId ?: fallbackInstanceId,
        originPackageName = request?.originPackageName ?: fallbackOriginPackageName,
        guestServiceClassName = request?.guestServiceClassName ?: fallbackGuestServiceClassName,
        reason = request?.reason ?: fallbackReason,
        foreground = request?.foreground ?: fallbackForeground,
        foregroundStatus = foregroundStatus,
        runtimeBindStatus = runtimeBindEvidence.status,
        runtimeBindDetail = runtimeBindEvidence.detail,
        runtimeBindProcessSlot = runtimeBindEvidence.processSlot ?: request?.processSlot,
        runtimeBindErrorClassName = runtimeBindEvidence.errorClassName,
        runtimeBindErrorMessage = runtimeBindEvidence.errorMessage,
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
        hostStartCommandResult = defaultHostStartCommandResult,
        hostStartCommandReturnMode = undecidedHostReturnMode
    )
}

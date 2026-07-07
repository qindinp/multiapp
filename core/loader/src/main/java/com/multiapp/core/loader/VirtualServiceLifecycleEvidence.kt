package com.multiapp.core.loader

data class VirtualServiceLifecycleEvidence(
    val instanceId: String,
    val guestServiceClassName: String,
    val event: Event,
    val success: Boolean,
    val cached: Boolean = false,
    val startCommandResult: Int? = null,
    val activeStartCount: Int = 0,
    val activeBindCount: Int = 0,
    val foreground: Boolean = false,
    val foregroundNotificationId: Int? = null,
    val foregroundServiceType: Int = 0,
    val idleStopRequested: Boolean = false,
    val idleStopReason: String = "",
    val hostStopServiceReturnValue: Boolean? = null,
    val idleStopDetail: String = "",
    val errorClassName: String? = null,
    val errorMessage: String? = null
) {
    enum class Event {
        CREATED_AND_STARTED,
        STARTED_CACHED,
        CREATE_FAILED,
        ATTACH_FAILED,
        ON_CREATE_FAILED,
        ON_START_COMMAND_FAILED,
        STOPPED,
        STOP_NOT_FOUND,
        ON_DESTROY_FAILED
    }

    companion object {
        fun from(result: VirtualServiceRuntimeResult): VirtualServiceLifecycleEvidence {
            val request = result.startRequest
            return when (result) {
                is VirtualServiceRuntimeResult.CreatedAndStarted -> base(
                    request = request,
                    event = Event.CREATED_AND_STARTED,
                    success = true,
                    startCommandResult = result.startCommandResult,
                    activeStartCount = result.activeStartCount,
                    activeBindCount = result.activeBindCount,
                    foreground = result.foreground,
                    foregroundNotificationId = result.foregroundNotificationId,
                    foregroundServiceType = result.foregroundServiceType
                )
                is VirtualServiceRuntimeResult.StartedCached -> base(
                    request = request,
                    event = Event.STARTED_CACHED,
                    success = true,
                    cached = true,
                    startCommandResult = result.startCommandResult,
                    activeStartCount = result.activeStartCount,
                    activeBindCount = result.activeBindCount,
                    foreground = result.foreground,
                    foregroundNotificationId = result.foregroundNotificationId,
                    foregroundServiceType = result.foregroundServiceType
                )
                is VirtualServiceRuntimeResult.CreateFailed -> base(
                    request = request,
                    event = Event.CREATE_FAILED,
                    success = false,
                    error = result.error
                )
                is VirtualServiceRuntimeResult.AttachFailed -> base(
                    request = request,
                    event = Event.ATTACH_FAILED,
                    success = false,
                    error = result.error
                )
                is VirtualServiceRuntimeResult.OnCreateFailed -> base(
                    request = request,
                    event = Event.ON_CREATE_FAILED,
                    success = false,
                    error = result.error
                )
                is VirtualServiceRuntimeResult.OnStartCommandFailed -> base(
                    request = request,
                    event = Event.ON_START_COMMAND_FAILED,
                    success = false,
                    cached = result.cached,
                    error = result.error
                )
            }
        }

        fun from(result: VirtualServiceRuntimeStopResult): VirtualServiceLifecycleEvidence {
            val request = result.stopRequest
            return when (result) {
                is VirtualServiceRuntimeStopResult.Stopped -> base(
                    request = request,
                    event = Event.STOPPED,
                    success = true,
                    idleStopResult = result.idleStopResult
                )
                is VirtualServiceRuntimeStopResult.NotFound -> base(
                    request = request,
                    event = Event.STOP_NOT_FOUND,
                    success = false
                )
                is VirtualServiceRuntimeStopResult.OnDestroyFailed -> base(
                    request = request,
                    event = Event.ON_DESTROY_FAILED,
                    success = false,
                    error = result.error
                )
            }
        }

        private fun base(
            request: VirtualServiceStartRequest,
            event: Event,
            success: Boolean,
            cached: Boolean = false,
            startCommandResult: Int? = null,
            activeStartCount: Int = 0,
            activeBindCount: Int = 0,
            foreground: Boolean = false,
            foregroundNotificationId: Int? = null,
            foregroundServiceType: Int = 0,
            idleStopResult: HostServiceIdleStopResult = HostServiceIdleStopResult.notRequested("notStopped"),
            error: Throwable? = null
        ): VirtualServiceLifecycleEvidence = VirtualServiceLifecycleEvidence(
            instanceId = request.instanceId,
            guestServiceClassName = request.guestServiceClassName,
            event = event,
            success = success,
            cached = cached,
            startCommandResult = startCommandResult,
            activeStartCount = activeStartCount,
            activeBindCount = activeBindCount,
            foreground = foreground,
            foregroundNotificationId = foregroundNotificationId,
            foregroundServiceType = foregroundServiceType,
            idleStopRequested = idleStopResult.idleStopRequested,
            idleStopReason = idleStopResult.idleStopReason,
            hostStopServiceReturnValue = idleStopResult.hostStopServiceReturnValue,
            idleStopDetail = idleStopResult.detail,
            errorClassName = error?.javaClass?.name,
            errorMessage = error?.message
        )

        private fun base(
            request: VirtualServiceStopRequest,
            event: Event,
            success: Boolean,
            idleStopResult: HostServiceIdleStopResult = HostServiceIdleStopResult.notRequested("notStopped"),
            error: Throwable? = null
        ): VirtualServiceLifecycleEvidence = VirtualServiceLifecycleEvidence(
            instanceId = request.instanceId,
            guestServiceClassName = request.guestServiceClassName,
            event = event,
            success = success,
            idleStopRequested = idleStopResult.idleStopRequested,
            idleStopReason = idleStopResult.idleStopReason,
            hostStopServiceReturnValue = idleStopResult.hostStopServiceReturnValue,
            idleStopDetail = idleStopResult.detail,
            errorClassName = error?.javaClass?.name,
            errorMessage = error?.message
        )
    }
}

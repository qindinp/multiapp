package com.multiapp.core.loader

data class VirtualServiceLifecycleEvidence(
    val instanceId: String,
    val guestServiceClassName: String,
    val event: Event,
    val success: Boolean,
    val cached: Boolean = false,
    val startCommandResult: Int? = null,
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
                    startCommandResult = result.startCommandResult
                )
                is VirtualServiceRuntimeResult.StartedCached -> base(
                    request = request,
                    event = Event.STARTED_CACHED,
                    success = true,
                    cached = true,
                    startCommandResult = result.startCommandResult
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
                    success = true
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
            error: Throwable? = null
        ): VirtualServiceLifecycleEvidence = VirtualServiceLifecycleEvidence(
            instanceId = request.instanceId,
            guestServiceClassName = request.guestServiceClassName,
            event = event,
            success = success,
            cached = cached,
            startCommandResult = startCommandResult,
            errorClassName = error?.javaClass?.name,
            errorMessage = error?.message
        )

        private fun base(
            request: VirtualServiceStopRequest,
            event: Event,
            success: Boolean,
            error: Throwable? = null
        ): VirtualServiceLifecycleEvidence = VirtualServiceLifecycleEvidence(
            instanceId = request.instanceId,
            guestServiceClassName = request.guestServiceClassName,
            event = event,
            success = success,
            errorClassName = error?.javaClass?.name,
            errorMessage = error?.message
        )
    }
}

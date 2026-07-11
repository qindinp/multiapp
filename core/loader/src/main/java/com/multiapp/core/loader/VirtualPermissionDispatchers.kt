package com.multiapp.core.loader

data class VirtualPermissionCheckRequest(
    val instanceId: String,
    val packageName: String,
    val permissionName: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(permissionName.isNotBlank()) { "permissionName must not be blank" }
    }
}

data class VirtualPermissionCheckDispatchResult(
    val handled: Boolean,
    val granted: Boolean,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(handled || !granted) { "unhandled permission checks cannot grant access" }
    }

    companion object {
        fun unavailable(reason: String) = VirtualPermissionCheckDispatchResult(
            handled = false,
            granted = false,
            reason = reason
        )
    }
}

fun interface VirtualPermissionCheckDispatcher {
    fun dispatch(request: VirtualPermissionCheckRequest): VirtualPermissionCheckDispatchResult
}

object VirtualPermissionCheckDispatchers {
    @Volatile
    private var dispatcher: VirtualPermissionCheckDispatcher? = null

    fun install(dispatcher: VirtualPermissionCheckDispatcher) {
        this.dispatcher = dispatcher
    }

    fun reset() {
        dispatcher = null
    }

    fun dispatch(request: VirtualPermissionCheckRequest): VirtualPermissionCheckDispatchResult =
        dispatcher?.dispatch(request)
            ?: VirtualPermissionCheckDispatchResult.unavailable("engine_permission_dispatcher_unavailable")
}

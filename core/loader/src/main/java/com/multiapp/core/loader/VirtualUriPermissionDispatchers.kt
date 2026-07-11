package com.multiapp.core.loader

import android.content.Context
import android.net.Uri
import com.multiapp.core.model.virtual.VirtualContextConfig

enum class VirtualUriPermissionOperation {
    GRANT,
    REVOKE,
    CHECK,
    TAKE_PERSISTABLE,
    RELEASE_PERSISTABLE
}

data class VirtualUriPermissionRequest(
    val operation: VirtualUriPermissionOperation,
    val uri: Uri,
    val modeFlags: Int,
    val targetPackageName: String? = null,
    val pid: Int = -1,
    val uid: Int = -1
) {
    init {
        require(modeFlags >= 0) { "modeFlags must not be negative" }
        require(targetPackageName == null || targetPackageName.isNotBlank()) {
            "targetPackageName must not be blank"
        }
        require(pid >= -1) { "pid must be -1 or non-negative" }
        require(uid >= -1) { "uid must be -1 or non-negative" }
    }
}

data class VirtualUriPermissionResult(
    val handled: Boolean,
    val success: Boolean,
    val granted: Boolean,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(handled || !success && !granted) {
            "unhandled URI permissions cannot be successful or granted"
        }
    }

    companion object {
        fun notHandled(reason: String): VirtualUriPermissionResult = VirtualUriPermissionResult(
            handled = false,
            success = false,
            granted = false,
            reason = reason
        )
    }
}

fun interface VirtualUriPermissionDispatcher {
    fun dispatch(request: VirtualUriPermissionRequest): VirtualUriPermissionResult
}

data class VirtualUriPermissionDispatcherFactoryRequest(
    val hostContext: Context,
    val config: VirtualContextConfig
)

fun interface VirtualUriPermissionDispatcherFactory {
    fun create(request: VirtualUriPermissionDispatcherFactoryRequest): VirtualUriPermissionDispatcher?
}

/** Engine-owned extension point for Context URI grant/check/revoke semantics. */
object VirtualUriPermissionDispatcherFactories {
    @Volatile
    private var factory: VirtualUriPermissionDispatcherFactory? = null

    fun install(factory: VirtualUriPermissionDispatcherFactory) {
        this.factory = factory
    }

    fun reset() {
        factory = null
        VirtualUriPermissionRuntimeBindings.reset()
    }

    fun createOrNull(request: VirtualUriPermissionDispatcherFactoryRequest): VirtualUriPermissionDispatcher? =
        factory?.create(request)
}

/** One hosted process owns one active instance-scoped URI permission dispatcher. */
object VirtualUriPermissionRuntimeBindings {
    @Volatile
    private var activeDispatcher: VirtualUriPermissionDispatcher? = null

    fun bindActive(dispatcher: VirtualUriPermissionDispatcher) {
        activeDispatcher = dispatcher
    }

    fun dispatch(request: VirtualUriPermissionRequest): VirtualUriPermissionResult =
        activeDispatcher?.dispatch(request)
            ?: VirtualUriPermissionResult.notHandled("active_uri_permission_dispatcher_unavailable")

    fun reset() {
        activeDispatcher = null
    }
}

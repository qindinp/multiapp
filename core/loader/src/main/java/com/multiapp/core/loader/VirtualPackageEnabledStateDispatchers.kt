package com.multiapp.core.loader

enum class VirtualPackageEnabledStateOperation {
    QUERY,
    SET
}

enum class VirtualPackageEnabledStateTarget {
    APPLICATION,
    COMPONENT
}

enum class VirtualPackageEnabledComponentType {
    ACTIVITY,
    SERVICE,
    RECEIVER,
    PROVIDER
}

data class VirtualPackageEnabledStateRequest(
    val instanceId: String,
    val packageName: String,
    val operation: VirtualPackageEnabledStateOperation,
    val target: VirtualPackageEnabledStateTarget,
    val componentType: VirtualPackageEnabledComponentType? = null,
    val className: String? = null,
    val newState: Int? = null,
    val flags: Int = 0
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        when (target) {
            VirtualPackageEnabledStateTarget.APPLICATION -> {
                require(componentType == null && className == null) {
                    "application enabled-state request must not contain a component"
                }
            }

            VirtualPackageEnabledStateTarget.COMPONENT -> {
                require(componentType != null && !className.isNullOrBlank()) {
                    "component enabled-state request requires type and className"
                }
            }
        }
        when (operation) {
            VirtualPackageEnabledStateOperation.QUERY ->
                require(newState == null) { "query request must not contain newState" }

            VirtualPackageEnabledStateOperation.SET ->
                require(newState != null) { "set request requires newState" }
        }
    }
}

data class VirtualPackageEnabledStateDispatchResult(
    val authoritative: Boolean,
    val found: Boolean,
    val enabledState: Int? = null,
    val changed: Boolean = false,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(authoritative || !found && enabledState == null && !changed) {
            "non-authoritative enabled-state result cannot expose package state"
        }
        require(!found || enabledState != null) { "found enabled-state result requires enabledState" }
        require(found || !changed) { "missing enabled-state target cannot be changed" }
    }

    companion object {
        fun unavailable(reason: String) = VirtualPackageEnabledStateDispatchResult(
            authoritative = false,
            found = false,
            reason = reason
        )
    }
}

fun interface VirtualPackageEnabledStateDispatcher {
    fun dispatch(request: VirtualPackageEnabledStateRequest): VirtualPackageEnabledStateDispatchResult
}

/** Engine-owned authority for per-instance application and component enabled state. */
object VirtualPackageEnabledStateDispatchers {
    @Volatile
    private var dispatcher: VirtualPackageEnabledStateDispatcher? = null

    fun install(dispatcher: VirtualPackageEnabledStateDispatcher) {
        this.dispatcher = dispatcher
    }

    fun reset() {
        dispatcher = null
    }

    fun dispatch(request: VirtualPackageEnabledStateRequest): VirtualPackageEnabledStateDispatchResult =
        runCatching { dispatcher?.dispatch(request) }
            .getOrNull()
            ?: VirtualPackageEnabledStateDispatchResult.unavailable(
                "engine_package_enabled_state_dispatcher_unavailable"
            )
}

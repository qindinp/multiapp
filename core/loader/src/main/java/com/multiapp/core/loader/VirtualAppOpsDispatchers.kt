package com.multiapp.core.loader

data class VirtualAppOpsDispatchRequest(
    val instanceId: String,
    val methodName: String,
    val opCode: Int? = null,
    val uid: Int = -1,
    val packageName: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(methodName.isNotBlank()) { "methodName must not be blank" }
        require(opCode == null || opCode >= 0) { "opCode must not be negative" }
        require(uid >= -1) { "uid must be -1 or non-negative" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }
}

data class VirtualAppOpsDispatchResult(
    val handled: Boolean,
    val mode: Int? = null,
    val blockSystemCall: Boolean = false,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(handled || mode == null && !blockSystemCall) {
            "unhandled AppOps result cannot override or block"
        }
    }

    companion object {
        fun passthrough(reason: String) = VirtualAppOpsDispatchResult(
            handled = false,
            reason = reason
        )
    }
}

fun interface VirtualAppOpsDispatcher {
    fun dispatch(request: VirtualAppOpsDispatchRequest): VirtualAppOpsDispatchResult
}

object VirtualAppOpsDispatchers {
    @Volatile
    private var dispatcher: VirtualAppOpsDispatcher? = null

    fun install(dispatcher: VirtualAppOpsDispatcher) {
        this.dispatcher = dispatcher
    }

    fun reset() {
        dispatcher = null
    }

    fun dispatch(request: VirtualAppOpsDispatchRequest): VirtualAppOpsDispatchResult =
        dispatcher?.dispatch(request) ?: VirtualAppOpsDispatchResult.passthrough("engine_dispatcher_unavailable")
}

/** One hosted process owns one active instance/process slot. */
object VirtualAppOpsRuntimeBindings {
    data class ActiveBinding(
        val instanceId: String,
        val primaryPackageName: String
    )

    @Volatile
    private var activeInstanceId: String? = null
    @Volatile
    private var activePackages: Set<String> = emptySet()
    @Volatile
    private var primaryPackageName: String? = null

    fun bindActive(instanceId: String, packageNames: Collection<String>) {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        val packages = packageNames.filter { it.isNotBlank() }.toCollection(linkedSetOf())
        require(packages.isNotEmpty()) { "packageNames must not be empty" }
        activePackages = packages
        primaryPackageName = packages.first()
        activeInstanceId = instanceId
    }

    fun resolve(packageName: String): String? =
        activeInstanceId?.takeIf { packageName in activePackages }

    fun active(): ActiveBinding? {
        val instanceId = activeInstanceId ?: return null
        val packageName = primaryPackageName ?: return null
        return ActiveBinding(instanceId, packageName)
    }

    fun reset() {
        activePackages = emptySet()
        primaryPackageName = null
        activeInstanceId = null
    }
}

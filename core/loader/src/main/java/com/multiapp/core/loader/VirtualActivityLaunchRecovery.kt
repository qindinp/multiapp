package com.multiapp.core.loader

data class VirtualActivityLaunchRecoveryRequest(
    val instanceId: String,
    val previousRuntimeEpoch: Long,
    val previousEngineSessionId: String?,
    val processSlot: String,
    val proxyActivityClassName: String,
    val guestActivityClassName: String,
    val restoreActivityId: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(previousRuntimeEpoch >= 0L) { "previousRuntimeEpoch must not be negative" }
        require(previousEngineSessionId == null || previousEngineSessionId.isNotBlank()) {
            "previousEngineSessionId must not be blank"
        }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        require(guestActivityClassName.isNotBlank()) { "guestActivityClassName must not be blank" }
        require(restoreActivityId.isNotBlank()) { "restoreActivityId must not be blank" }
    }
}

data class VirtualActivityLaunchRecoveryResult(
    val recovered: Boolean,
    val identity: VirtualActivityLaunchIdentity?,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(recovered == (identity != null)) {
            "recovered result must contain exactly one launch identity"
        }
    }
}

fun interface VirtualActivityLaunchRecoveryHandler {
    fun recover(request: VirtualActivityLaunchRecoveryRequest): VirtualActivityLaunchRecoveryResult
}

/** Synchronous pre-attach recovery seam installed by the engine layer. */
object VirtualActivityLaunchRecovery {
    @Volatile
    private var handler: VirtualActivityLaunchRecoveryHandler? = null

    fun install(handler: VirtualActivityLaunchRecoveryHandler) {
        this.handler = handler
    }

    fun recover(request: VirtualActivityLaunchRecoveryRequest): VirtualActivityLaunchRecoveryResult {
        val current = handler ?: return rejected("activity_launch_recovery_unavailable")
        return runCatching { current.recover(request) }.getOrElse { error ->
            rejected("activity_launch_recovery_failed:${error.javaClass.name}")
        }
    }

    internal fun clearForTests() {
        handler = null
    }

    private fun rejected(reason: String) = VirtualActivityLaunchRecoveryResult(
        recovered = false,
        identity = null,
        reason = reason
    )
}

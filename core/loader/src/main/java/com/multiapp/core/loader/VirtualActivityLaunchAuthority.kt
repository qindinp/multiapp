package com.multiapp.core.loader

import android.app.Activity
import android.content.Intent

data class VirtualActivityLaunchIdentity(
    val capabilityToken: String,
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val proxyActivityClassName: String,
    val guestActivityClassName: String
) {
    init {
        require(capabilityToken.isNotBlank()) { "capabilityToken must not be blank" }
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        require(guestActivityClassName.isNotBlank()) { "guestActivityClassName must not be blank" }
    }
}

data class VirtualActivityLaunchAuthorityResult(
    val accepted: Boolean,
    val reason: String
)

fun interface VirtualActivityLaunchValidator {
    fun authorize(identity: VirtualActivityLaunchIdentity): VirtualActivityLaunchAuthorityResult
}

fun interface VirtualActivityResumeObserver {
    fun onActivityResumeCompleted(activity: Activity, identity: VirtualActivityLaunchIdentity)
}

object VirtualActivityLaunchAuthority {
    @Volatile
    private var validator: VirtualActivityLaunchValidator? = null

    @Volatile
    private var resumeObserver: VirtualActivityResumeObserver? = null

    fun install(
        validator: VirtualActivityLaunchValidator,
        resumeObserver: VirtualActivityResumeObserver
    ) {
        this.validator = validator
        this.resumeObserver = resumeObserver
    }

    fun authorize(identity: VirtualActivityLaunchIdentity): VirtualActivityLaunchAuthorityResult {
        val current = validator ?: return VirtualActivityLaunchAuthorityResult(
            accepted = false,
            reason = "activity_launch_validator_unavailable"
        )
        return runCatching { current.authorize(identity) }.getOrElse { error ->
            VirtualActivityLaunchAuthorityResult(
                accepted = false,
                reason = "activity_launch_validator_failed:${error.javaClass.name}"
            )
        }
    }

    fun notifyResumeCompleted(activity: Activity) {
        val identity = activity.intent?.toVirtualActivityLaunchIdentity(
            proxyActivityClassName = activity.intent?.getStringExtra(
                VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME
            )
        ) ?: return
        runCatching { resumeObserver?.onActivityResumeCompleted(activity, identity) }
    }

    internal fun clearForTests() {
        validator = null
        resumeObserver = null
    }
}

internal fun Intent.toVirtualActivityLaunchIdentity(
    proxyActivityClassName: String?
): VirtualActivityLaunchIdentity? {
    val capabilityToken = getStringExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val instanceId = getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val runtimeEpoch = getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, 0L)
        .takeIf { it > 0L }
        ?: return null
    val engineSessionId = getStringExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val processSlot = getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val proxyClass = proxyActivityClassName?.takeIf { it.isNotBlank() }
        ?: return null
    val guestActivityClassName = getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        VirtualActivityLaunchIdentity(
            capabilityToken = capabilityToken,
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            proxyActivityClassName = proxyClass,
            guestActivityClassName = guestActivityClassName
        )
    }.getOrNull()
}

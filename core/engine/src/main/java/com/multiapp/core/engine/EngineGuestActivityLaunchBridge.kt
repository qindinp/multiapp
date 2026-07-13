package com.multiapp.core.engine

import android.app.Activity
import android.content.Intent
import com.multiapp.core.loader.VirtualActivityLaunchAuthority
import com.multiapp.core.loader.VirtualActivityLaunchAuthorityResult
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.loader.VirtualActivityLaunchValidator
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.loader.VirtualActivityResumeObserver

fun interface EngineGuestActivityLaunchValidator {
    fun authorize(identity: EngineActivityLaunchIdentity): EngineActivityLaunchAuthorization
}

fun interface EngineGuestActivityResumeObserver {
    fun onActivityResumeCompleted(activity: Activity, identity: EngineActivityLaunchIdentity)
}

object EngineGuestActivityLaunchBridge {
    fun install(
        validator: EngineGuestActivityLaunchValidator,
        resumeObserver: EngineGuestActivityResumeObserver
    ) {
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator { identity ->
                validator.authorize(identity.toEngineIdentity()).let { result ->
                    VirtualActivityLaunchAuthorityResult(result.accepted, result.reason)
                }
            },
            resumeObserver = VirtualActivityResumeObserver { activity, identity ->
                resumeObserver.onActivityResumeCompleted(activity, identity.toEngineIdentity())
            }
        )
    }

    fun authorizeProxyIntent(
        proxyIntent: Intent,
        proxyActivityClassName: String
    ): EngineActivityLaunchAuthorization {
        val identity = proxyIntent.toEngineLaunchIdentity(proxyActivityClassName)
            ?: return EngineActivityLaunchAuthorization(
                accepted = false,
                idempotent = false,
                reason = "invalid_activity_launch_identity"
            )
        val result = VirtualActivityLaunchAuthority.authorize(identity.toLoaderIdentity())
        return EngineActivityLaunchAuthorization(
            accepted = result.accepted,
            idempotent = false,
            reason = result.reason
        )
    }
}

private fun VirtualActivityLaunchIdentity.toEngineIdentity() = EngineActivityLaunchIdentity(
    capabilityToken = capabilityToken,
    instanceId = instanceId,
    runtimeEpoch = runtimeEpoch,
    engineSessionId = engineSessionId,
    processSlot = processSlot,
    proxyActivityClassName = proxyActivityClassName,
    guestActivityClassName = guestActivityClassName
)

private fun EngineActivityLaunchIdentity.toLoaderIdentity() = VirtualActivityLaunchIdentity(
    capabilityToken = capabilityToken,
    instanceId = instanceId,
    runtimeEpoch = runtimeEpoch,
    engineSessionId = engineSessionId,
    processSlot = processSlot,
    proxyActivityClassName = proxyActivityClassName,
    guestActivityClassName = guestActivityClassName
)

private fun Intent.toEngineLaunchIdentity(proxyActivityClassName: String): EngineActivityLaunchIdentity? {
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
    val guestActivityClassName = getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        EngineActivityLaunchIdentity(
            capabilityToken = capabilityToken,
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            proxyActivityClassName = proxyActivityClassName,
            guestActivityClassName = guestActivityClassName
        )
    }.getOrNull()
}

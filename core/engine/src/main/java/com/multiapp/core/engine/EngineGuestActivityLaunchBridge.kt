package com.multiapp.core.engine

import android.app.Activity
import android.content.Intent
import com.multiapp.core.loader.VirtualActivityLaunchAuthority
import com.multiapp.core.loader.VirtualActivityLaunchAuthorityResult
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.loader.VirtualActivityLaunchRecovery
import com.multiapp.core.loader.VirtualActivityLaunchRecoveryHandler
import com.multiapp.core.loader.VirtualActivityLaunchRecoveryRequest
import com.multiapp.core.loader.VirtualActivityLaunchRecoveryResult
import com.multiapp.core.loader.VirtualActivityLaunchValidator
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.loader.VirtualActivityResumeObserver

fun interface EngineGuestActivityLaunchValidator {
    fun authorize(identity: EngineActivityLaunchIdentity): EngineActivityLaunchAuthorization
}

fun interface EngineGuestActivityResumeObserver {
    fun onActivityResumeCompleted(activity: Activity, identity: EngineActivityLaunchIdentity)
}

data class EngineGuestActivityRecoveryRequest(
    val instanceId: String,
    val previousRuntimeEpoch: Long,
    val previousEngineSessionId: String?,
    val processSlot: String,
    val proxyActivityClassName: String,
    val guestActivityClassName: String,
    val restoreActivityId: String
)

data class EngineGuestActivityRecoveryResult(
    val recovered: Boolean,
    val identity: EngineActivityLaunchIdentity?,
    val reason: String
)

fun interface EngineGuestActivityRecoveryHandler {
    fun recover(request: EngineGuestActivityRecoveryRequest): EngineGuestActivityRecoveryResult
}

object EngineGuestActivityLaunchBridge {
    fun installRecovery(handler: EngineGuestActivityRecoveryHandler) {
        VirtualActivityLaunchRecovery.install(
            VirtualActivityLaunchRecoveryHandler { request ->
                handler.recover(request.toEngineRequest()).let { result ->
                    VirtualActivityLaunchRecoveryResult(
                        recovered = result.recovered,
                        identity = result.identity?.toLoaderIdentity(),
                        reason = result.reason
                    )
                }
            }
        )
    }

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

private fun VirtualActivityLaunchRecoveryRequest.toEngineRequest() = EngineGuestActivityRecoveryRequest(
    instanceId = instanceId,
    previousRuntimeEpoch = previousRuntimeEpoch,
    previousEngineSessionId = previousEngineSessionId,
    processSlot = processSlot,
    proxyActivityClassName = proxyActivityClassName,
    guestActivityClassName = guestActivityClassName,
    restoreActivityId = restoreActivityId
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

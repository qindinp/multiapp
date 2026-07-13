package com.multiapp.app.container

import android.app.Activity
import android.app.Application
import android.os.Process
import com.multiapp.core.engine.EngineActivityLaunchAuthorization
import com.multiapp.core.engine.EngineActivityLaunchIdentity
import com.multiapp.core.engine.EngineGuestActivityLaunchBridge
import com.multiapp.core.engine.EngineGuestActivityLaunchValidator
import com.multiapp.core.engine.EngineGuestActivityResumeObserver
import com.multiapp.core.engine.EngineRuntimeForegroundAck
import com.multiapp.core.engine.EngineRuntimeIpcClients
import java.util.concurrent.Executors

internal data class EngineGuestForegroundAckRequest(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int,
    val capabilityToken: String? = null
)

internal fun interface EngineGuestForegroundAckTransport {
    fun acknowledge(request: EngineGuestForegroundAckRequest): EngineRuntimeForegroundAck?
}

internal fun interface EngineGuestActivityLaunchTransport {
    fun authorize(identity: EngineActivityLaunchIdentity): EngineActivityLaunchAuthorization?
}

internal class EngineGuestForegroundAcknowledger(
    private val ackTransport: EngineGuestForegroundAckTransport = EngineGuestForegroundAckTransport { request ->
        if (request.processId <= 0 || request.processId != Process.myPid() || request.capabilityToken.isNullOrBlank()) {
            null
        } else {
            EngineRuntimeIpcClients.acknowledgeActivityResumed(
                instanceId = request.instanceId,
                runtimeEpoch = request.runtimeEpoch,
                engineSessionId = request.engineSessionId,
                processSlot = request.processSlot,
                capabilityToken = request.capabilityToken
            )
        }
    },
    private val launchTransport: EngineGuestActivityLaunchTransport =
        EngineGuestActivityLaunchTransport(EngineRuntimeIpcClients::authorizeActivityLaunch),
    private val dispatchAck: ((() -> Unit) -> Unit) = { task -> ACK_EXECUTOR.execute(task) }
) {
    private var registration: Registration? = null

    @Synchronized
    fun register(
        guestApplication: Application,
        guestClassLoader: ClassLoader,
        request: EngineGuestForegroundAckRequest
    ): Boolean {
        if (
            request.instanceId.isBlank() || request.runtimeEpoch <= 0L ||
            request.engineSessionId.isBlank() || request.processSlot.isBlank() ||
            request.processId <= 0 || request.capabilityToken != null
        ) {
            return false
        }
        val current = registration
        if (current != null) {
            if (request.runtimeEpoch < current.request.runtimeEpoch) return false
            if (
                request.runtimeEpoch == current.request.runtimeEpoch &&
                request.engineSessionId != current.request.engineSessionId
            ) {
                return false
            }
            if (request.runtimeEpoch == current.request.runtimeEpoch && request != current.request) {
                return false
            }
            if (request == current.request) {
                return current.guestApplication === guestApplication &&
                    current.guestClassLoader === guestClassLoader
            }
        }
        installLoaderAuthority()
        registration = Registration(
            guestApplication = guestApplication,
            guestClassLoader = guestClassLoader,
            request = request
        )
        return true
    }

    @Synchronized
    internal fun registrationCount(): Int = if (registration == null) 0 else 1

    @Synchronized
    internal fun unregister(request: EngineGuestForegroundAckRequest): Boolean {
        val current = registration ?: return false
        if (current.request != request.copy(capabilityToken = null)) return false
        registration = null
        return true
    }

    private fun installLoaderAuthority() {
        EngineGuestActivityLaunchBridge.install(
            validator = EngineGuestActivityLaunchValidator { identity ->
                authorizeLaunch(identity).let { decision ->
                    EngineActivityLaunchAuthorization(
                        accepted = decision.accepted,
                        idempotent = false,
                        reason = decision.reason
                    )
                }
            },
            resumeObserver = EngineGuestActivityResumeObserver(::onResumeCompleted)
        )
    }

    private fun authorizeLaunch(identity: EngineActivityLaunchIdentity): AuthorityDecision {
        val current = synchronized(this) { registration }
            ?: return AuthorityDecision(false, "guest_runtime_generation_missing")
        if (!current.request.matches(identity)) {
            return AuthorityDecision(false, "guest_runtime_generation_mismatch")
        }
        val result = runCatching { launchTransport.authorize(identity) }.getOrNull()
            ?: return AuthorityDecision(false, "engine_launch_authority_unavailable")
        return AuthorityDecision(result.accepted, result.reason)
    }

    private fun onResumeCompleted(activity: Activity, identity: EngineActivityLaunchIdentity) {
        val current = synchronized(this) {
            val active = registration ?: return
            if (!active.request.matches(identity)) return
            if (activity.application !== active.guestApplication) return
            if (activity.javaClass.classLoader !== active.guestClassLoader) return
            if (!active.inFlightCapabilities.add(identity.capabilityToken)) return
            active
        }
        val task = {
            try {
                val request = current.request.copy(capabilityToken = identity.capabilityToken)
                val ack = runCatching { ackTransport.acknowledge(request) }.getOrNull()
                if (ack != null && ack.completesRegistration()) {
                    synchronized(this) {
                        if (registration === current) registration = null
                    }
                }
            } finally {
                synchronized(this) {
                    current.inFlightCapabilities.remove(identity.capabilityToken)
                }
            }
        }
        runCatching { dispatchAck(task) }.onFailure {
            synchronized(this) {
                current.inFlightCapabilities.remove(identity.capabilityToken)
            }
        }
    }

    private fun EngineGuestForegroundAckRequest.matches(identity: EngineActivityLaunchIdentity): Boolean =
        instanceId == identity.instanceId &&
            runtimeEpoch == identity.runtimeEpoch &&
            engineSessionId == identity.engineSessionId &&
            processSlot == identity.processSlot

    private fun EngineRuntimeForegroundAck.completesRegistration(): Boolean =
        accepted || state in TERMINAL_RUNTIME_STATES

    private data class AuthorityDecision(
        val accepted: Boolean,
        val reason: String
    )

    private data class Registration(
        val guestApplication: Application,
        val guestClassLoader: ClassLoader,
        val request: EngineGuestForegroundAckRequest,
        val inFlightCapabilities: MutableSet<String> = mutableSetOf()
    )

    companion object {
        private val TERMINAL_RUNTIME_STATES = setOf("STOPPED", "DEAD")
        private val ACK_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "multiapp-foreground-ack").apply { isDaemon = true }
        }

        val global = EngineGuestForegroundAcknowledger()
    }
}

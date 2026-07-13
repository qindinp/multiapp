package com.multiapp.app.container

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.multiapp.core.engine.EngineActivityLaunchIdentity
import com.multiapp.core.engine.EngineGuestActivityLaunchBridge
import com.multiapp.core.engine.EngineGuestActivityRecoveryHandler
import com.multiapp.core.engine.EngineGuestActivityRecoveryRequest
import com.multiapp.core.engine.EngineGuestActivityRecoveryResult
import com.multiapp.core.engine.EngineProcessClientAttachResult
import com.multiapp.core.engine.EngineProcessClientIdentity
import com.multiapp.core.engine.EngineRecentsRestoreCapabilityResult
import com.multiapp.core.engine.EngineRuntimeForegroundAck
import com.multiapp.core.engine.EngineRuntimeIpcClients
import com.multiapp.core.engine.EngineRuntimeIpcSnapshot
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.model.engine.VirtualRuntimeState

internal interface EngineGuestRecentsRecoveryTransport {
    fun queryRuntime(instanceId: String): EngineRuntimeIpcSnapshot?
    fun processRestarted(
        identity: EngineProcessClientIdentity,
        processToken: IBinder
    ): EngineProcessClientAttachResult?

    fun markProcessPrewarmed(identity: EngineProcessClientIdentity): EngineRuntimeForegroundAck?
    fun issueRestoreCapability(
        identity: EngineProcessClientIdentity,
        restoreActivityId: String
    ): EngineRecentsRestoreCapabilityResult?
}

internal class EngineGuestRecentsRecoveryCoordinator(
    context: Context,
    private val transport: EngineGuestRecentsRecoveryTransport = IpcRecoveryTransport,
    private val runtimeEngineFactory: (Context) -> HostedRuntimeEngine = ::hostedRuntimeEngineFrom,
    private val foregroundAcknowledger: EngineGuestForegroundAcknowledger =
        EngineGuestForegroundAcknowledger.global,
    private val processIdProvider: () -> Int = Process::myPid,
    private val processNameProvider: () -> String = Application::getProcessName,
    private val processToken: IBinder = Binder()
) {
    private val hostContext = context.applicationContext ?: context

    fun install() {
        EngineGuestActivityLaunchBridge.installRecovery(
            EngineGuestActivityRecoveryHandler(::recover)
        )
    }

    @Synchronized
    internal fun recover(request: EngineGuestActivityRecoveryRequest): EngineGuestActivityRecoveryResult {
        val processId = processIdProvider()
        if (processId <= 0) return rejected("recovery_process_id_invalid")
        val processName = runCatching(processNameProvider).getOrNull()
        if (processName != request.processSlot) {
            return rejected("recovery_process_slot_mismatch")
        }
        val snapshot = transport.queryRuntime(request.instanceId)
            ?: return rejected("recovery_runtime_authority_unavailable")
        if (!snapshot.found) return rejected(snapshot.reason ?: "recovery_runtime_not_found")
        if (snapshot.processSlot != request.processSlot) {
            return rejected("recovery_runtime_process_slot_mismatch")
        }
        val identity = resolveLiveIdentity(snapshot, processId)
            ?: return rejected(lastIdentityFailureReason)

        val runtimeEngine = runCatching { runtimeEngineFactory(hostContext) }.getOrElse { error ->
            return rejected("recovery_runtime_engine_unavailable:${error.javaClass.simpleName}")
        }
        val bootstrap = runCatching {
            runtimeEngine.reusableResult(
                instanceId = request.instanceId,
                providerHookEnabled = false,
                processSlot = request.processSlot
            ) ?: runtimeEngine.bindApplication(
                instanceId = request.instanceId,
                providerHookEnabled = false,
                processSlot = request.processSlot
            ).result
        }.getOrElse { error ->
            return rejected("recovery_bind_application_failed:${error.javaClass.simpleName}")
        }
        val guestApplication = bootstrap.guestApplication
            ?: return rejected("recovery_guest_application_missing")
        val guestClassLoader = bootstrap.guestClassLoader
            ?: return rejected("recovery_guest_classloader_missing")
        if (!bootstrap.success || bootstrap.instanceId != request.instanceId) {
            return rejected("recovery_guest_runtime_not_ready")
        }
        if (bootstrap.processSlot != request.processSlot) {
            return rejected("recovery_bootstrap_process_slot_mismatch")
        }

        val prewarm = transport.markProcessPrewarmed(identity)
            ?: return rejected("recovery_prewarm_authority_unavailable")
        if (!prewarm.accepted || prewarm.state !in PREWARMED_STATES) {
            return rejected("recovery_prewarm_rejected:${prewarm.reason}")
        }
        val ackRequest = EngineGuestForegroundAckRequest(
            instanceId = identity.instanceId,
            runtimeEpoch = identity.runtimeEpoch,
            engineSessionId = identity.engineSessionId,
            processSlot = identity.processSlot,
            processId = identity.processId
        )
        if (!foregroundAcknowledger.register(guestApplication, guestClassLoader, ackRequest)) {
            return rejected("recovery_foreground_ack_registration_failed")
        }
        val capability = transport.issueRestoreCapability(identity, request.restoreActivityId)
        val launchIdentity = capability?.identity
        if (
            capability == null || !capability.accepted || launchIdentity == null ||
            launchIdentity.instanceId != request.instanceId ||
            launchIdentity.processSlot != request.processSlot ||
            launchIdentity.proxyActivityClassName != request.proxyActivityClassName
        ) {
            foregroundAcknowledger.unregister(ackRequest)
            return rejected(
                "recovery_capability_rejected:${capability?.reason ?: "ipc_unavailable"}"
            )
        }
        return EngineGuestActivityRecoveryResult(
            recovered = true,
            identity = launchIdentity,
            reason = "recents_runtime_rebound_and_capability_refreshed"
        )
    }

    private var lastIdentityFailureReason: String = "recovery_runtime_identity_invalid"

    private fun resolveLiveIdentity(
        snapshot: EngineRuntimeIpcSnapshot,
        processId: Int
    ): EngineProcessClientIdentity? {
        val runtimeEpoch = snapshot.runtimeEpoch.takeIf { it > 0L }
            ?: return identityFailure("recovery_runtime_epoch_invalid")
        val engineSessionId = snapshot.engineSessionId?.takeIf { it.isNotBlank() }
            ?: return identityFailure("recovery_engine_session_missing")
        val processSlot = snapshot.processSlot?.takeIf { it.isNotBlank() }
            ?: return identityFailure("recovery_process_slot_missing")
        val currentIdentity = EngineProcessClientIdentity(
            instanceId = snapshot.instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            processId = processId
        )
        return when (snapshot.runtimeState) {
            VirtualRuntimeState.DEAD.name -> {
                val restarted = transport.processRestarted(currentIdentity, processToken)
                    ?: return identityFailure("recovery_process_restart_unavailable")
                if (!restarted.accepted || !restarted.liveAuthority) {
                    return identityFailure("recovery_process_restart_rejected:${restarted.reason}")
                }
                restarted.identity
                    ?.takeIf { identity ->
                        identity.instanceId == snapshot.instanceId &&
                            identity.processSlot == processSlot &&
                            identity.processId == processId
                    }
                    ?: identityFailure("recovery_successor_identity_invalid")
            }
            VirtualRuntimeState.CREATED.name,
            VirtualRuntimeState.PREWARMED.name,
            VirtualRuntimeState.RUNNING.name -> {
                if (
                    snapshot.liveAuthority && snapshot.processId == processId &&
                    snapshot.processName == processSlot
                ) {
                    currentIdentity
                } else {
                    identityFailure("recovery_live_runtime_authority_missing")
                }
            }
            else -> identityFailure("recovery_runtime_not_restartable:${snapshot.runtimeState}")
        }
    }

    private fun identityFailure(reason: String): EngineProcessClientIdentity? {
        lastIdentityFailureReason = reason
        return null
    }

    private fun rejected(reason: String) = EngineGuestActivityRecoveryResult(
        recovered = false,
        identity = null,
        reason = reason
    )

    private object IpcRecoveryTransport : EngineGuestRecentsRecoveryTransport {
        override fun queryRuntime(instanceId: String): EngineRuntimeIpcSnapshot? =
            EngineRuntimeIpcClients.queryRuntime(instanceId)

        override fun processRestarted(
            identity: EngineProcessClientIdentity,
            processToken: IBinder
        ): EngineProcessClientAttachResult? =
            EngineRuntimeIpcClients.processRestarted(identity, processToken)

        override fun markProcessPrewarmed(
            identity: EngineProcessClientIdentity
        ): EngineRuntimeForegroundAck? = EngineRuntimeIpcClients.markProcessPrewarmed(identity)

        override fun issueRestoreCapability(
            identity: EngineProcessClientIdentity,
            restoreActivityId: String
        ): EngineRecentsRestoreCapabilityResult? =
            EngineRuntimeIpcClients.issueRecentsRestoreCapability(identity, restoreActivityId)
    }

    companion object {
        private val PREWARMED_STATES = setOf(
            VirtualRuntimeState.PREWARMED.name,
            VirtualRuntimeState.RUNNING.name
        )

        @Volatile
        private var installed: EngineGuestRecentsRecoveryCoordinator? = null

        fun install(context: Context) {
            if (installed != null) return
            synchronized(this) {
                if (installed != null) return
                EngineGuestRecentsRecoveryCoordinator(context).also { coordinator ->
                    coordinator.install()
                    installed = coordinator
                }
            }
        }
    }
}

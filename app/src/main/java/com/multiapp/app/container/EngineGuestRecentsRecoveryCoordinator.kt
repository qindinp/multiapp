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
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal interface EngineGuestRecentsRecoveryTransport {
    fun queryRuntime(instanceId: String): EngineRuntimeIpcSnapshot?
    fun processRestarted(
        identity: EngineProcessClientIdentity,
        processToken: IBinder
    ): EngineProcessClientAttachResult?

    fun markProcessPrewarmed(identity: EngineProcessClientIdentity): EngineRuntimeForegroundAck?
    fun abandonProcessClient(
        identity: EngineProcessClientIdentity,
        reason: String
    ): EngineRuntimeForegroundAck?
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
    private val processToken: IBinder = Binder(),
    private val recoveryTimeoutMs: Long = DEFAULT_RECOVERY_TIMEOUT_MS,
    private val abandonAuthorityTimeoutMs: Long = DEFAULT_ABANDON_AUTHORITY_TIMEOUT_MS,
    private val watchdogScheduler: ScheduledExecutorService = WATCHDOG_EXECUTOR,
    private val abandonExecutor: ExecutorService = ABANDON_EXECUTOR,
    private val processTerminator: (Int) -> Unit = Process::killProcess
) {
    private val hostContext = context.applicationContext ?: context

    init {
        require(recoveryTimeoutMs > 0L) { "recoveryTimeoutMs must be positive" }
        require(abandonAuthorityTimeoutMs > 0L) {
            "abandonAuthorityTimeoutMs must be positive"
        }
    }

    fun install() {
        EngineGuestActivityLaunchBridge.installRecovery(
            EngineGuestActivityRecoveryHandler(::recover)
        )
    }

    @Synchronized
    internal fun recover(request: EngineGuestActivityRecoveryRequest): EngineGuestActivityRecoveryResult {
        val processId = processIdProvider()
        if (processId <= 0) return rejected("recovery_process_id_invalid")
        if (EngineProcessBootstrapIpc.processSlotIndex(request.processSlot) == null) {
            return rejected("recovery_process_slot_unsupported")
        }
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

        val watchdog = armWatchdog(identity)
            ?: return rejected("recovery_watchdog_unavailable")
        val result = runCatching { recoverBoundRuntime(request, identity) }.getOrElse { error ->
            rejected("recovery_unhandled_failure:${error.javaClass.simpleName}")
        }
        return if (watchdog.completeBeforeTimeout()) {
            result
        } else {
            rejected("recovery_bind_timeout")
        }
    }

    private fun recoverBoundRuntime(
        request: EngineGuestActivityRecoveryRequest,
        identity: EngineProcessClientIdentity
    ): EngineGuestActivityRecoveryResult {
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

    private fun armWatchdog(identity: EngineProcessClientIdentity): RecoveryWatchdog? {
        val state = AtomicReference(RecoveryWatchdogState.PENDING)
        val future = runCatching {
            watchdogScheduler.schedule(
                {
                    if (!state.compareAndSet(
                            RecoveryWatchdogState.PENDING,
                            RecoveryWatchdogState.TIMED_OUT
                        )
                    ) {
                        return@schedule
                    }
                    val abandon = runCatching {
                        abandonExecutor.submit {
                            transport.abandonProcessClient(identity, "recents_bind_timeout")
                        }
                    }.getOrNull()
                    if (abandon != null) {
                        runCatching {
                            abandon.get(abandonAuthorityTimeoutMs, TimeUnit.MILLISECONDS)
                        }
                        abandon.cancel(true)
                    }
                    runCatching { processTerminator(identity.processId) }
                },
                recoveryTimeoutMs,
                TimeUnit.MILLISECONDS
            )
        }.getOrNull() ?: return null
        return RecoveryWatchdog(future, state)
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

        override fun abandonProcessClient(
            identity: EngineProcessClientIdentity,
            reason: String
        ): EngineRuntimeForegroundAck? =
            EngineRuntimeIpcClients.abandonProcessClient(identity, reason)

        override fun issueRestoreCapability(
            identity: EngineProcessClientIdentity,
            restoreActivityId: String
        ): EngineRecentsRestoreCapabilityResult? =
            EngineRuntimeIpcClients.issueRecentsRestoreCapability(identity, restoreActivityId)
    }

    companion object {
        const val DEFAULT_RECOVERY_TIMEOUT_MS = 45_000L
        private const val DEFAULT_ABANDON_AUTHORITY_TIMEOUT_MS = 500L

        private val PREWARMED_STATES = setOf(
            VirtualRuntimeState.PREWARMED.name,
            VirtualRuntimeState.RUNNING.name
        )

        @Volatile
        private var installed: EngineGuestRecentsRecoveryCoordinator? = null

        private val WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "multiapp-recents-watchdog").apply { isDaemon = true }
        }
        private val ABANDON_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "multiapp-recents-abandon").apply { isDaemon = true }
        }

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

    private data class RecoveryWatchdog(
        val future: ScheduledFuture<*>,
        val state: AtomicReference<RecoveryWatchdogState>
    ) {
        fun completeBeforeTimeout(): Boolean {
            if (state.compareAndSet(
                    RecoveryWatchdogState.PENDING,
                    RecoveryWatchdogState.COMPLETED
                )
            ) {
                future.cancel(false)
            }
            return state.get() == RecoveryWatchdogState.COMPLETED
        }
    }

    private enum class RecoveryWatchdogState {
        PENDING,
        COMPLETED,
        TIMED_OUT
    }
}

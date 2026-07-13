package com.multiapp.app.container

import android.app.Application
import android.content.Context
import android.os.IBinder
import com.multiapp.core.engine.EngineActivityLaunchIdentity
import com.multiapp.core.engine.EngineGuestActivityRecoveryRequest
import com.multiapp.core.engine.EngineGuestActivityRecoveryResult
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineProcessAttachOperation
import com.multiapp.core.engine.EngineProcessClientAttachResult
import com.multiapp.core.engine.EngineProcessClientIdentity
import com.multiapp.core.engine.EngineRecentsRestoreCapabilityResult
import com.multiapp.core.engine.EngineRecentsRestoreCapabilityStatus
import com.multiapp.core.engine.EngineRuntimeForegroundAck
import com.multiapp.core.engine.EngineRuntimeIpcSnapshot
import com.multiapp.core.engine.HostedRuntimeBindOutcome
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.model.engine.VirtualRuntimeState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EngineGuestRecentsRecoveryCoordinatorTest {
    @Test
    fun `dead recents runtime is rebound before a fresh capability is returned`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val application = mockk<Application>(relaxed = true)
        val guestClassLoader = ClassLoader.getSystemClassLoader()
        val bootstrap = mockk<EngineHostedBootstrapResult> {
            every { success } returns true
            every { instanceId } returns INSTANCE_ID
            every { processSlot } returns PROCESS_SLOT
            every { guestApplication } returns application
            every { this@mockk.guestClassLoader } returns guestClassLoader
        }
        val runtimeEngine = mockk<HostedRuntimeEngine> {
            every { reusableResult(INSTANCE_ID, false, PROCESS_SLOT) } returns null
            every { bindApplication(INSTANCE_ID, false, PROCESS_SLOT) } returns
                HostedRuntimeBindOutcome(bootstrap, ranBootstrapOnThisThread = true)
        }
        val acknowledger = mockk<EngineGuestForegroundAcknowledger>(relaxed = true) {
            every { register(application, guestClassLoader, any()) } returns true
        }
        val transport = RecordingRecoveryTransport()
        val coordinator = EngineGuestRecentsRecoveryCoordinator(
            context = context,
            transport = transport,
            runtimeEngineFactory = { runtimeEngine },
            foregroundAcknowledger = acknowledger,
            processIdProvider = { PROCESS_ID },
            processNameProvider = { PROCESS_SLOT },
            processToken = mockk<IBinder>(relaxed = true)
        )

        val result = coordinator.recover(request())

        assertTrue(result.recovered, result.reason)
        assertEquals("fresh-capability", result.identity?.capabilityToken)
        assertEquals(
            listOf("query", "restart", "prewarm", "capability"),
            transport.operations
        )
        verify(exactly = 1) { runtimeEngine.bindApplication(INSTANCE_ID, false, PROCESS_SLOT) }
        verify(exactly = 1) { acknowledger.register(application, guestClassLoader, any()) }
    }

    @Test
    fun `wrong Android process cannot recover another process slot`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val transport = RecordingRecoveryTransport()
        val runtimeEngine = mockk<HostedRuntimeEngine>(relaxed = true)
        val coordinator = EngineGuestRecentsRecoveryCoordinator(
            context = context,
            transport = transport,
            runtimeEngineFactory = { runtimeEngine },
            processIdProvider = { PROCESS_ID },
            processNameProvider = { "com.multiapp.app:v7" },
            processToken = mockk<IBinder>(relaxed = true)
        )

        val result = coordinator.recover(request())

        assertFalse(result.recovered)
        assertEquals("recovery_process_slot_mismatch", result.reason)
        assertTrue(transport.operations.isEmpty())
        verify(exactly = 0) { runtimeEngine.bindApplication(any(), any(), any()) }
    }

    @Test
    fun `default host process cannot enter guest recycle path`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val transport = RecordingRecoveryTransport()
        val runtimeEngine = mockk<HostedRuntimeEngine>(relaxed = true)
        val terminatedProcessIds = mutableListOf<Int>()
        val coordinator = EngineGuestRecentsRecoveryCoordinator(
            context = context,
            transport = transport,
            runtimeEngineFactory = { runtimeEngine },
            processIdProvider = { PROCESS_ID },
            processNameProvider = { HOST_PROCESS },
            processToken = mockk<IBinder>(relaxed = true),
            processTerminator = terminatedProcessIds::add
        )

        val result = coordinator.recover(request().copy(processSlot = HOST_PROCESS))

        assertFalse(result.recovered)
        assertEquals("recovery_process_slot_unsupported", result.reason)
        assertTrue(transport.operations.isEmpty())
        assertTrue(terminatedProcessIds.isEmpty())
        verify(exactly = 0) { runtimeEngine.bindApplication(any(), any(), any()) }
    }

    @Test
    fun `bind timeout abandons generation and terminates only the guest process`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val application = mockk<Application>(relaxed = true)
        val guestClassLoader = ClassLoader.getSystemClassLoader()
        val bootstrap = mockk<EngineHostedBootstrapResult> {
            every { success } returns true
            every { instanceId } returns INSTANCE_ID
            every { processSlot } returns PROCESS_SLOT
            every { guestApplication } returns application
            every { this@mockk.guestClassLoader } returns guestClassLoader
        }
        val bindEntered = CountDownLatch(1)
        val releaseBind = CountDownLatch(1)
        val processTerminated = CountDownLatch(1)
        val runtimeEngine = mockk<HostedRuntimeEngine> {
            every { reusableResult(INSTANCE_ID, false, PROCESS_SLOT) } returns null
            every { bindApplication(INSTANCE_ID, false, PROCESS_SLOT) } answers {
                bindEntered.countDown()
                releaseBind.await(10, TimeUnit.SECONDS)
                HostedRuntimeBindOutcome(bootstrap, ranBootstrapOnThisThread = true)
            }
        }
        val transport = RecordingRecoveryTransport()
        val watchdogScheduler = Executors.newSingleThreadScheduledExecutor()
        val recoveryExecutor = Executors.newSingleThreadExecutor()
        try {
            val coordinator = EngineGuestRecentsRecoveryCoordinator(
                context = context,
                transport = transport,
                runtimeEngineFactory = { runtimeEngine },
                foregroundAcknowledger = mockk(relaxed = true),
                processIdProvider = { PROCESS_ID },
                processNameProvider = { PROCESS_SLOT },
                processToken = mockk(relaxed = true),
                recoveryTimeoutMs = 20L,
                watchdogScheduler = watchdogScheduler,
                processTerminator = { processId ->
                    assertEquals(PROCESS_ID, processId)
                    processTerminated.countDown()
                }
            )

            val future = recoveryExecutor.submit<EngineGuestActivityRecoveryResult> {
                coordinator.recover(request())
            }
            assertTrue(bindEntered.await(5, TimeUnit.SECONDS))
            assertTrue(processTerminated.await(5, TimeUnit.SECONDS))
            releaseBind.countDown()
            val result = future.get(5, TimeUnit.SECONDS)

            assertFalse(result.recovered)
            assertEquals("recovery_bind_timeout", result.reason)
            assertTrue(transport.operations.contains("abandon"))
        } finally {
            releaseBind.countDown()
            recoveryExecutor.shutdownNow()
            watchdogScheduler.shutdownNow()
        }
    }

    @Test
    fun `blocked abandon authority cannot prevent guest process termination`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val bindEntered = CountDownLatch(1)
        val releaseBind = CountDownLatch(1)
        val abandonEntered = CountDownLatch(1)
        val releaseAbandon = CountDownLatch(1)
        val processTerminated = CountDownLatch(1)
        val runtimeEngine = mockk<HostedRuntimeEngine> {
            every { reusableResult(INSTANCE_ID, false, PROCESS_SLOT) } returns null
            every { bindApplication(INSTANCE_ID, false, PROCESS_SLOT) } answers {
                bindEntered.countDown()
                releaseBind.await(10, TimeUnit.SECONDS)
                throw IllegalStateException("bind released after watchdog")
            }
        }
        val transport = RecordingRecoveryTransport {
            abandonEntered.countDown()
            releaseAbandon.await(10, TimeUnit.SECONDS)
        }
        val watchdogScheduler = Executors.newSingleThreadScheduledExecutor()
        val abandonExecutor = Executors.newCachedThreadPool()
        val recoveryExecutor = Executors.newSingleThreadExecutor()
        try {
            val coordinator = EngineGuestRecentsRecoveryCoordinator(
                context = context,
                transport = transport,
                runtimeEngineFactory = { runtimeEngine },
                foregroundAcknowledger = mockk(relaxed = true),
                processIdProvider = { PROCESS_ID },
                processNameProvider = { PROCESS_SLOT },
                processToken = mockk(relaxed = true),
                recoveryTimeoutMs = 20L,
                abandonAuthorityTimeoutMs = 20L,
                watchdogScheduler = watchdogScheduler,
                abandonExecutor = abandonExecutor,
                processTerminator = { processId ->
                    assertEquals(PROCESS_ID, processId)
                    processTerminated.countDown()
                }
            )

            val future = recoveryExecutor.submit<EngineGuestActivityRecoveryResult> {
                coordinator.recover(request())
            }
            assertTrue(bindEntered.await(5, TimeUnit.SECONDS))
            assertTrue(abandonEntered.await(5, TimeUnit.SECONDS))
            assertTrue(processTerminated.await(5, TimeUnit.SECONDS))
            releaseBind.countDown()
            val result = future.get(5, TimeUnit.SECONDS)

            assertFalse(result.recovered)
            assertEquals("recovery_bind_timeout", result.reason)
            assertTrue(transport.operations.contains("abandon"))
        } finally {
            releaseBind.countDown()
            releaseAbandon.countDown()
            recoveryExecutor.shutdownNow()
            abandonExecutor.shutdownNow()
            watchdogScheduler.shutdownNow()
        }
    }

    private fun request() = EngineGuestActivityRecoveryRequest(
        instanceId = INSTANCE_ID,
        previousRuntimeEpoch = OLD_EPOCH,
        previousEngineSessionId = OLD_SESSION,
        processSlot = PROCESS_SLOT,
        proxyActivityClassName = PROXY_ACTIVITY,
        guestActivityClassName = GUEST_ACTIVITY,
        restoreActivityId = RESTORE_ACTIVITY_ID
    )

    private class RecordingRecoveryTransport(
        private val onAbandon: () -> Unit = {}
    ) : EngineGuestRecentsRecoveryTransport {
        val operations = Collections.synchronizedList(mutableListOf<String>())

        override fun queryRuntime(instanceId: String): EngineRuntimeIpcSnapshot {
            operations += "query"
            return EngineRuntimeIpcSnapshot(
                found = true,
                instanceId = instanceId,
                processSlot = PROCESS_SLOT,
                proxySlot = PROXY_ACTIVITY,
                runtimeEpoch = OLD_EPOCH,
                engineSessionId = OLD_SESSION,
                evidenceSessionId = "old-evidence",
                runtimeState = VirtualRuntimeState.DEAD.name,
                processId = null,
                processName = null,
                reason = "runtime_state_dead",
                liveAuthority = false
            )
        }

        override fun processRestarted(
            identity: EngineProcessClientIdentity,
            processToken: IBinder
        ): EngineProcessClientAttachResult {
            operations += "restart"
            return EngineProcessClientAttachResult(
                accepted = true,
                idempotent = false,
                liveAuthority = true,
                operation = EngineProcessAttachOperation.PROCESS_RESTARTED,
                identity = identity.copy(
                    runtimeEpoch = OLD_EPOCH + 1L,
                    engineSessionId = NEW_SESSION
                ),
                runtimeState = VirtualRuntimeState.CREATED,
                reason = "restart_generation_allocated_and_client_attached"
            )
        }

        override fun markProcessPrewarmed(
            identity: EngineProcessClientIdentity
        ): EngineRuntimeForegroundAck {
            operations += "prewarm"
            return EngineRuntimeForegroundAck(
                accepted = true,
                idempotent = false,
                state = VirtualRuntimeState.PREWARMED.name,
                reason = "guest_application_bound_and_runtime_prewarmed"
            )
        }

        override fun abandonProcessClient(
            identity: EngineProcessClientIdentity,
            reason: String
        ): EngineRuntimeForegroundAck {
            operations += "abandon"
            onAbandon()
            return EngineRuntimeForegroundAck(
                accepted = true,
                idempotent = false,
                state = VirtualRuntimeState.DEAD.name,
                reason = reason
            )
        }

        override fun issueRestoreCapability(
            identity: EngineProcessClientIdentity,
            restoreActivityId: String
        ): EngineRecentsRestoreCapabilityResult {
            operations += "capability"
            assertEquals(RESTORE_ACTIVITY_ID, restoreActivityId)
            return EngineRecentsRestoreCapabilityResult(
                accepted = true,
                status = EngineRecentsRestoreCapabilityStatus.ISSUED,
                identity = EngineActivityLaunchIdentity(
                    capabilityToken = "fresh-capability",
                    instanceId = identity.instanceId,
                    runtimeEpoch = identity.runtimeEpoch,
                    engineSessionId = identity.engineSessionId,
                    processSlot = identity.processSlot,
                    proxyActivityClassName = PROXY_ACTIVITY,
                    guestActivityClassName = GUEST_ACTIVITY
                ),
                restoreActivityId = restoreActivityId,
                reusedPersistedSystemActivityToken = false,
                reason = "fresh_restore_capability_issued"
            )
        }
    }

    private companion object {
        const val INSTANCE_ID = "instance-recents"
        const val PROCESS_ID = 4_321
        const val HOST_PROCESS = "com.multiapp.app"
        const val PROCESS_SLOT = "com.multiapp.app:v2"
        const val PROXY_ACTIVITY = "com.multiapp.app.container.ProxyActivity2"
        const val GUEST_ACTIVITY = "com.test.app.MainActivity"
        const val RESTORE_ACTIVITY_ID = "activity-root"
        const val OLD_EPOCH = 7L
        const val OLD_SESSION = "old-session"
        const val NEW_SESSION = "new-session"
    }
}

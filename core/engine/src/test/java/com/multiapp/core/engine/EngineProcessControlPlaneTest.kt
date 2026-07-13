package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualTaskRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineProcessControlPlaneTest {
    @Test
    fun `attach binds complete identity to live token and is idempotent`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val controlPlane = EngineProcessControlPlane(registry)
        val token = liveToken()
        val identity = identity()

        val attached = controlPlane.attachClient(identity, token.binder, PROCESS_ID)
        val repeated = controlPlane.attachClient(identity, token.binder, PROCESS_ID)

        assertTrue(attached.accepted)
        assertFalse(attached.idempotent)
        assertTrue(attached.liveAuthority)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals(1, token.linkCount.get())
        assertEquals(PROCESS_ID, registry.get(INSTANCE_ID)?.processId)
        assertEquals(PROCESS_SLOT, registry.get(INSTANCE_ID)?.processName)
        assertTrue(controlPlane.authorize(INSTANCE_ID, PROCESS_ID).allowed)
    }

    @Test
    fun `stale generation and old pid fail closed before linking token`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime(runtimeEpoch = EPOCH + 1, processId = PROCESS_ID + 1))
        val controlPlane = EngineProcessControlPlane(registry)
        val staleToken = liveToken()
        val oldPidToken = liveToken()

        val stale = controlPlane.attachClient(identity(), staleToken.binder, PROCESS_ID)
        val oldPid = controlPlane.attachClient(
            identity(runtimeEpoch = EPOCH + 1),
            oldPidToken.binder,
            PROCESS_ID
        )

        assertFalse(stale.accepted)
        assertEquals("runtime_generation_mismatch", stale.reason)
        assertFalse(oldPid.accepted)
        assertEquals("process_id_mismatch", oldPid.reason)
        assertEquals(0, staleToken.linkCount.get())
        assertEquals(0, oldPidToken.linkCount.get())
    }

    @Test
    fun `caller process name must match the assigned process slot`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val controlPlane = EngineProcessControlPlane(registry)
        val token = liveToken()

        val result = controlPlane.attachClient(
            identity = identity(),
            clientToken = token.binder,
            callingPid = PROCESS_ID,
            callingProcessName = "$HOST_PACKAGE:v7"
        )

        assertFalse(result.accepted)
        assertEquals("calling_process_slot_mismatch", result.reason)
        assertEquals(0, token.linkCount.get())
    }

    @Test
    fun `live client promotes created runtime to prewarmed exactly once`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val controlPlane = EngineProcessControlPlane(registry)
        val token = liveToken()
        val identity = identity()
        assertTrue(controlPlane.attachClient(identity, token.binder, PROCESS_ID).accepted)

        val promoted = controlPlane.markPrewarmed(identity, PROCESS_ID, PROCESS_SLOT)
        val repeated = controlPlane.markPrewarmed(identity, PROCESS_ID, PROCESS_SLOT)
        val wrongProcess = controlPlane.markPrewarmed(
            identity,
            PROCESS_ID,
            "$HOST_PACKAGE:v7"
        )

        assertTrue(promoted.accepted)
        assertFalse(promoted.idempotent)
        assertEquals(VirtualRuntimeState.PREWARMED, promoted.runtimeState)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals(VirtualRuntimeState.PREWARMED, registry.get(INSTANCE_ID)?.state)
        assertFalse(wrongProcess.accepted)
        assertEquals("calling_process_slot_mismatch", wrongProcess.reason)
    }

    @Test
    fun `binder death revokes authority and only marks exact pid generation dead`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val controlPlane = EngineProcessControlPlane(registry)
        val token = liveToken()
        val identity = identity()
        assertTrue(controlPlane.attachClient(identity, token.binder, PROCESS_ID).accepted)

        token.recipient.captured.binderDied()

        assertFalse(controlPlane.authorize(INSTANCE_ID, PROCESS_ID).allowed)
        assertEquals(VirtualRuntimeState.DEAD, registry.get(INSTANCE_ID)?.state)
        assertNull(registry.get(INSTANCE_ID)?.processId)
        assertNull(registry.get(INSTANCE_ID)?.processName)
    }

    @Test
    fun `process restart atomically allocates a new generation and binds the new pid`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val deathRegistry = EngineProcessDeathRegistry()
        val controlPlane = EngineProcessControlPlane(
            runtimeRegistry = registry,
            deathRegistry = deathRegistry,
            engineSessionFactory = { "$SESSION_ID-restarted" },
            evidenceSessionFactory = { "evidence-restarted" }
        )
        val oldToken = liveToken()
        assertTrue(controlPlane.attachClient(identity(), oldToken.binder, PROCESS_ID).accepted)
        oldToken.recipient.captured.binderDied()

        val restartedToken = liveToken()
        val restarted = controlPlane.processRestarted(
            identity(processId = PROCESS_ID + 1),
            restartedToken.binder,
            PROCESS_ID + 1
        )

        assertTrue(restarted.accepted)
        assertEquals(EPOCH + 1, restarted.identity?.runtimeEpoch)
        assertEquals("$SESSION_ID-restarted", restarted.identity?.engineSessionId)
        assertEquals(PROCESS_ID + 1, restarted.identity?.processId)
        assertEquals(
            EngineRecentsRestoreCapabilityStatus.RESTORE_RECORD_SELECTION_REQUIRED,
            restarted.restoreCapabilityStatus
        )
        assertTrue(controlPlane.authorize(INSTANCE_ID, PROCESS_ID + 1).allowed)
        assertFalse(controlPlane.authorize(INSTANCE_ID, PROCESS_ID).allowed)

        val replayToken = liveToken()
        val replay = controlPlane.processRestarted(
            identity(processId = PROCESS_ID + 2),
            replayToken.binder,
            PROCESS_ID + 2
        )
        assertFalse(replay.accepted)
        assertEquals("runtime_generation_mismatch", replay.reason)
        assertEquals(0, replayToken.linkCount.get())
    }

    @Test
    fun `server restart invalidation permits one authoritative dead generation restart`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val firstServer = EngineProcessControlPlane(registry)
        val token = liveToken()
        val identity = identity()
        assertTrue(firstServer.attachClient(identity, token.binder, PROCESS_ID).accepted)
        assertEquals(
            VirtualRuntimeState.PREWARMED,
            registry.markPrewarmedIfCurrent(
                INSTANCE_ID,
                EPOCH,
                SESSION_ID,
                PROCESS_ID,
                PROCESS_SLOT
            )?.state
        )

        registry.invalidateEphemeralProcessStates("test_engine_server_restart")
        val restartedServer = EngineProcessControlPlane(
            runtimeRegistry = registry,
            engineSessionFactory = { "$SESSION_ID-server-restarted" },
            evidenceSessionFactory = { "evidence-server-restarted" }
        )
        val reconnectToken = liveToken()
        val reconnect = restartedServer.processRestarted(
            identity.copy(processId = PROCESS_ID + 1),
            reconnectToken.binder,
            PROCESS_ID + 1
        )

        assertTrue(reconnect.accepted)
        assertEquals(EPOCH + 1, reconnect.identity?.runtimeEpoch)
        assertEquals("$SESSION_ID-server-restarted", reconnect.identity?.engineSessionId)
        assertEquals(1, reconnectToken.linkCount.get())
        assertTrue(restartedServer.authorize(INSTANCE_ID, PROCESS_ID + 1).allowed)
        assertFalse(restartedServer.authorize(INSTANCE_ID, PROCESS_ID).allowed)
    }

    @Test
    fun `durable running snapshot without live binder is never authority`() {
        val registry = EngineRuntimeRegistry()
        registry.register(
            runtime(processId = PROCESS_ID).copy(state = VirtualRuntimeState.RUNNING)
        )
        val controlPlane = EngineProcessControlPlane(registry)
        val reconnectToken = liveToken()

        val authority = controlPlane.authorize(INSTANCE_ID, PROCESS_ID)
        val reconnect = controlPlane.processRestarted(
            identity(),
            reconnectToken.binder,
            PROCESS_ID
        )

        assertFalse(authority.allowed)
        assertEquals("live_client_authority_missing", authority.reason)
        assertFalse(reconnect.accepted)
        assertEquals("runtime_not_restartable:RUNNING", reconnect.reason)
        assertEquals(0, reconnectToken.linkCount.get())
    }

    @Test
    fun `recents restore issues fresh capability from persisted record for new generation`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val deathRegistry = EngineProcessDeathRegistry()
        val capabilityTokens = ArrayDeque(listOf("old-capability", "new-capability"))
        val capabilities = EngineActivityLaunchCapabilityRegistry(
            tokenFactory = { capabilityTokens.removeFirst() }
        )
        val controlPlane = EngineProcessControlPlane(registry, deathRegistry, capabilities)
        val oldToken = liveToken()
        assertTrue(controlPlane.attachClient(identity(), oldToken.binder, PROCESS_ID).accepted)
        val oldCapability = capabilities.issue(
            runtime = requireNotNull(registry.get(INSTANCE_ID)),
            processId = PROCESS_ID,
            proxyActivityClassName = PROXY_ACTIVITY,
            guestActivityClassName = GUEST_ACTIVITY
        )
        oldToken.recipient.captured.binderDied()

        val restartedToken = liveToken()
        val restarted = controlPlane.processRestarted(
            identity(processId = PROCESS_ID + 1),
            restartedToken.binder,
            PROCESS_ID + 1
        )
        assertTrue(restarted.accepted)
        val restartedIdentity = requireNotNull(restarted.identity)
        val persistedRecord = VirtualActivityRecord(
            token = "persisted-virtual-activity-token",
            activityId = "activity-root",
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            guestActivityClassName = GUEST_ACTIVITY,
            proxyActivityClassName = PROXY_ACTIVITY,
            taskId = 7,
            state = VirtualActivityState.STOPPED
        )
        val issuer = EngineRecentsRestoreCapabilityIssuer(
            runtimeRegistry = registry,
            processControlPlane = controlPlane,
            activityLaunchCapabilities = capabilities,
            taskStateProvider = {
                VirtualActivityTaskState(
                    instanceId = INSTANCE_ID,
                    verdict = com.multiapp.core.model.engine.EngineResultStatus.PARTIAL,
                    taskCount = 1,
                    activityCount = 1,
                    tasks = listOf(
                        VirtualTaskRecord(
                            taskId = 7,
                            affinity = "$ORIGIN_PACKAGE:$INSTANCE_ID",
                            activities = listOf(persistedRecord)
                        )
                    ),
                    message = "persisted_restore_record"
                )
            }
        )

        val restored = issuer.issue(restartedIdentity, "activity-root", PROCESS_ID + 1)

        assertTrue(restored.accepted)
        assertEquals(EngineRecentsRestoreCapabilityStatus.ISSUED, restored.status)
        assertEquals("new-capability", restored.identity?.capabilityToken)
        assertEquals(EPOCH + 1, restored.identity?.runtimeEpoch)
        assertEquals(PROXY_ACTIVITY, restored.identity?.proxyActivityClassName)
        assertEquals(GUEST_ACTIVITY, restored.identity?.guestActivityClassName)
        assertFalse(restored.reusedPersistedSystemActivityToken)
        assertFalse(capabilities.authorize(oldCapability, PROCESS_ID).accepted)
        assertFalse(issuer.issue(restartedIdentity, "activity-root", PROCESS_ID).accepted)
        assertFalse(issuer.issue(identity(), "activity-root", PROCESS_ID).accepted)
    }

    private fun runtime(
        runtimeEpoch: Long = EPOCH,
        engineSessionId: String = SESSION_ID,
        processId: Int? = null
    ) = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        dataRoot = "build/tmp/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/$INSTANCE_ID",
            activities = listOf(ResolvedComponent(GUEST_ACTIVITY, exported = true))
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PROCESS_SLOT,
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity0",
        evidenceSessionId = "evidence-$runtimeEpoch",
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = processId,
        processName = processId?.let { PROCESS_SLOT },
        state = VirtualRuntimeState.CREATED
    )

    private fun identity(
        runtimeEpoch: Long = EPOCH,
        engineSessionId: String = SESSION_ID,
        processId: Int = PROCESS_ID
    ) = EngineProcessClientIdentity(
        instanceId = INSTANCE_ID,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = PROCESS_SLOT,
        processId = processId
    )

    private fun liveToken(): TestToken {
        val recipient = slot<IBinder.DeathRecipient>()
        val linkCount = AtomicInteger()
        val unlinkCount = AtomicInteger()
        val binder = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } returns true
            every { linkToDeath(capture(recipient), 0) } answers {
                linkCount.incrementAndGet()
            }
            every { unlinkToDeath(any(), 0) } answers {
                unlinkCount.incrementAndGet()
                true
            }
        }
        return TestToken(binder, recipient, linkCount, unlinkCount)
    }

    private data class TestToken(
        val binder: IBinder,
        val recipient: io.mockk.CapturingSlot<IBinder.DeathRecipient>,
        val linkCount: AtomicInteger,
        val unlinkCount: AtomicInteger
    )

    private companion object {
        const val INSTANCE_ID = "instance-control-plane"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-control-plane"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val PROXY_ACTIVITY = "$HOST_PACKAGE.container.ProxyActivity0"
        const val GUEST_ACTIVITY = "$ORIGIN_PACKAGE.MainActivity"
        const val PROCESS_ID = 4100
        const val EPOCH = 41L
        const val SESSION_ID = "engine-session-41"
    }
}

package com.multiapp.core.engine

import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineComponentProcessClientRegistryTest {
    @Test
    fun `procfs probe reads cmdline process name and stat starttime`() {
        val processId = 2468
        val processStartTicks = 987_654L
        val fieldsAfterName = buildList {
            add("S")
            repeat(18) { index -> add((index + 1).toString()) }
            add(processStartTicks.toString())
            add("0")
        }.joinToString(" ")
        val cmdlineReads = mutableListOf<Int>()
        val statReads = mutableListOf<Int>()
        val probe = EngineComponentProcessIdentityProbe.procfs(
            readCmdline = { requestedPid ->
                cmdlineReads += requestedPid
                "$REMOTE_SLOT\u0000--guest-arg\u0000".toByteArray()
            },
            readStat = { requestedPid ->
                statReads += requestedPid
                "$requestedPid (slot ) name) $fieldsAfterName"
            }
        )

        val observed = probe.read(processId)

        assertEquals(
            EngineComponentProcessHostIdentity(REMOTE_SLOT, processStartTicks),
            observed
        )
        assertEquals(listOf(processId), cmdlineReads)
        assertEquals(listOf(processId), statReads)
    }

    @Test
    fun `identity validates complete process binding and derives its key`() {
        val identity = identity()

        assertEquals(
            EngineComponentProcessClientKey(INSTANCE_ID, REMOTE_PROCESS),
            identity.key
        )
        assertFailsWith<IllegalArgumentException> { identity(processSlot = " ") }
        assertFailsWith<IllegalArgumentException> { identity(processId = 0) }
        assertFailsWith<IllegalArgumentException> { identity(processEpoch = 0L) }
        assertFailsWith<IllegalArgumentException> { identity(clientSessionId = " ") }
        assertFailsWith<IllegalArgumentException> {
            identity(effectiveGuestProcessName = "$REMOTE_PROCESS ")
        }
    }

    @Test
    fun `same identity and token attach is idempotent and linked once`() {
        val registry = registry()
        val token = liveToken(registry)
        val identity = identity()

        val attached = registry.attach(identity, token.binder)
        val repeated = registry.attach(identity, token.binder)

        assertTrue(attached.accepted)
        assertFalse(attached.idempotent)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals(1, token.linkCount.get())
        assertTrue(registry.isAuthoritative(identity, token.binder))
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `one instance can own multiple custom process clients and query both indexes`() {
        val registry = registry()
        val remoteToken = liveToken(registry)
        val workerToken = liveToken(registry)
        val remote = identity()
        val worker = identity(
            effectiveGuestProcessName = WORKER_PROCESS,
            processSlot = WORKER_SLOT,
            processId = WORKER_PID
        )

        assertTrue(registry.attach(remote, remoteToken.binder).accepted)
        assertTrue(registry.attach(worker, workerToken.binder).accepted)

        val byKey = registry.queryByKey(INSTANCE_ID, WORKER_PROCESS)
        val byPid = registry.queryByPid(REMOTE_PID)
        assertTrue(byKey.found)
        assertEquals(worker, byKey.identity)
        assertSame(workerToken.binder, byKey.clientToken)
        assertTrue(byPid.found)
        assertEquals(remote, byPid.identity)
        assertSame(remoteToken.binder, byPid.clientToken)
        assertEquals(2, registry.activeCount())
    }

    @Test
    fun `same generation slot pid or token replay fails closed`() {
        val registry = registry()
        val currentToken = liveToken(registry)
        val forgedToken = liveToken(registry)
        val current = identity()
        assertTrue(registry.attach(current, currentToken.binder).accepted)

        val slotConflict = registry.attach(
            current.copy(processSlot = "$HOST_PACKAGE:v9"),
            forgedToken.binder
        )
        val pidConflict = registry.attach(
            current.copy(processId = REMOTE_PID + 1),
            forgedToken.binder
        )
        val tokenReplay = registry.attach(current, forgedToken.binder)

        assertFalse(slotConflict.accepted)
        assertEquals("component_process_generation_conflict", slotConflict.reason)
        assertFalse(pidConflict.accepted)
        assertEquals("component_process_generation_conflict", pidConflict.reason)
        assertFalse(tokenReplay.accepted)
        assertEquals("component_process_generation_replayed", tokenReplay.reason)
        assertEquals(0, forgedToken.linkCount.get())
        assertTrue(registry.isAuthoritative(current, currentToken.binder))
    }

    @Test
    fun `different keys cannot claim one live slot or pid`() {
        val registry = registry()
        val current = identity()
        assertTrue(registry.attach(current, liveToken(registry).binder).accepted)

        val slotConflict = registry.attach(
            identity(
                effectiveGuestProcessName = WORKER_PROCESS,
                processSlot = REMOTE_SLOT,
                processId = WORKER_PID
            ),
            liveToken(registry).binder
        )
        val pidConflict = registry.attach(
            identity(
                effectiveGuestProcessName = WORKER_PROCESS,
                processSlot = WORKER_SLOT,
                processId = REMOTE_PID
            ),
            liveToken(registry).binder
        )

        assertFalse(slotConflict.accepted)
        assertEquals("component_process_slot_conflict", slotConflict.reason)
        assertFalse(pidConflict.accepted)
        assertEquals("component_process_pid_conflict", pidConflict.reason)
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `new generation replaces all stale instance clients and old generation cannot replay`() {
        val registry = registry()
        val remoteToken = liveToken(registry)
        val workerToken = liveToken(registry)
        val replacementToken = liveToken(registry)
        val replayToken = liveToken(registry)
        val remote = identity()
        val worker = identity(
            effectiveGuestProcessName = WORKER_PROCESS,
            processSlot = WORKER_SLOT,
            processId = WORKER_PID
        )
        val replacement = remote.copy(
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "$SESSION_ID-next",
            processId = REMOTE_PID + 10,
            processStartTicks = (REMOTE_PID + 10L) * 10L
        )
        assertTrue(registry.attach(remote, remoteToken.binder).accepted)
        assertTrue(registry.attach(worker, workerToken.binder).accepted)

        val replaced = registry.attach(replacement, replacementToken.binder)
        val replay = registry.attach(remote, replayToken.binder)

        assertTrue(replaced.accepted)
        assertTrue(replaced.replacedGeneration)
        assertEquals(1, remoteToken.unlinkCount.get())
        assertEquals(1, workerToken.unlinkCount.get())
        assertFalse(registry.isAuthoritative(worker, workerToken.binder))
        assertFalse(replay.accepted)
        assertEquals("component_process_generation_stale", replay.reason)
        assertEquals(0, replayToken.linkCount.get())
        assertTrue(registry.isAuthoritative(replacement, replacementToken.binder))
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `failed newer attach keeps current generation authoritative`() {
        val registry = registry()
        val currentToken = liveToken(registry)
        val current = identity()
        val replacement = current.copy(
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "$SESSION_ID-next",
            processId = REMOTE_PID + 1
        )
        assertTrue(registry.attach(current, currentToken.binder).accepted)

        val result = registry.attach(replacement, deadToken(registry))

        assertFalse(result.accepted)
        assertEquals("component_process_client_token_not_alive", result.reason)
        assertTrue(registry.isAuthoritative(current, currentToken.binder))
        assertEquals(0, currentToken.unlinkCount.get())
    }

    @Test
    fun `Binder death removes authority once and invokes callback outside the lock`() {
        val registry = registry()
        val token = liveToken(registry)
        val identity = identity()
        var deaths = 0
        assertTrue(
            registry.attach(identity, token.binder) {
                assertFalse(Thread.holdsLock(registry))
                deaths++
            }.accepted
        )

        token.die()
        token.die()

        assertEquals(1, deaths)
        assertEquals(0, registry.activeCount())
        assertFalse(registry.queryByKey(identity.key).found)
        assertEquals("component_process_client_not_live", registry.queryByKey(identity.key).reason)
        assertFalse(registry.attach(identity, liveToken(registry).binder).accepted)
        val restarted = identity.copy(
            processEpoch = PROCESS_EPOCH + 1,
            clientSessionId = "$CLIENT_SESSION_ID-next",
            processId = REMOTE_PID + 1,
            processStartTicks = (REMOTE_PID + 1L) * 10L
        )
        assertTrue(registry.attach(restarted, liveToken(registry).binder).accepted)
        assertTrue(registry.isAuthoritative(restarted))
    }

    @Test
    fun `authoritative checks bind full identity token slot and pid`() {
        val registry = registry()
        val token = liveToken(registry)
        val otherToken = liveToken(registry)
        val identity = identity()
        assertTrue(registry.attach(identity, token.binder).accepted)

        assertTrue(registry.isAuthoritative(identity))
        assertTrue(registry.isAuthoritative(identity, token.binder))
        assertFalse(registry.isAuthoritative(identity, otherToken.binder))
        assertFalse(registry.isAuthoritative(identity.copy(processSlot = WORKER_SLOT), token.binder))
        assertFalse(registry.isAuthoritative(identity.copy(processId = WORKER_PID), token.binder))
        assertFalse(
            registry.isAuthoritative(
                identity.copy(engineSessionId = "$SESSION_ID-forged"),
                token.binder
            )
        )
        assertFalse(registry.queryByPid(WORKER_PID).found)
        assertEquals("component_process_pid_invalid", registry.queryByPid(0).reason)
    }

    @Test
    fun `exact remove unlinks outside lock and keeps replay tombstone`() {
        val registry = registry()
        val token = liveToken(registry)
        val identity = identity()
        assertTrue(registry.attach(identity, token.binder).accepted)

        assertFalse(registry.remove(identity.copy(processId = WORKER_PID), token.binder))
        assertTrue(registry.remove(identity, token.binder))

        assertEquals(1, token.unlinkCount.get())
        assertEquals(0, registry.activeCount())
        val replay = registry.attach(identity, liveToken(registry).binder)
        assertFalse(replay.accepted)
        assertEquals("component_process_generation_replayed", replay.reason)
        val restarted = identity.copy(
            processEpoch = PROCESS_EPOCH + 1,
            clientSessionId = "$CLIENT_SESSION_ID-next",
            processId = REMOTE_PID + 1,
            processStartTicks = (REMOTE_PID + 1L) * 10L
        )
        assertTrue(registry.attach(restarted, liveToken(registry).binder).accepted)
    }

    @Test
    fun `process restart replaces only its key and keeps sibling process authoritative`() {
        val registry = registry()
        val remoteToken = liveToken(registry)
        val workerToken = liveToken(registry)
        val replacementToken = liveToken(registry)
        val remote = identity()
        val worker = identity(
            effectiveGuestProcessName = WORKER_PROCESS,
            processSlot = WORKER_SLOT,
            processId = WORKER_PID
        )
        assertTrue(registry.attach(remote, remoteToken.binder).accepted)
        assertTrue(registry.attach(worker, workerToken.binder).accepted)

        val replacement = remote.copy(
            processEpoch = PROCESS_EPOCH + 1,
            clientSessionId = "$CLIENT_SESSION_ID-next",
            processId = REMOTE_PID + 10,
            processStartTicks = (REMOTE_PID + 10L) * 10L
        )
        val replaced = registry.attach(replacement, replacementToken.binder)

        assertTrue(replaced.accepted)
        assertTrue(replaced.replacedGeneration)
        assertEquals(1, remoteToken.unlinkCount.get())
        assertEquals(0, workerToken.unlinkCount.get())
        assertTrue(registry.isAuthoritative(replacement, replacementToken.binder))
        assertTrue(registry.isAuthoritative(worker, workerToken.binder))
        assertEquals(2, registry.activeCount())
    }

    @Test
    fun `generation and instance revoke cover every custom process`() {
        val registry = registry()
        val remoteToken = liveToken(registry)
        val workerToken = liveToken(registry)
        val remote = identity()
        val worker = identity(
            effectiveGuestProcessName = WORKER_PROCESS,
            processSlot = WORKER_SLOT,
            processId = WORKER_PID
        )
        assertTrue(registry.attach(remote, remoteToken.binder).accepted)
        assertTrue(registry.attach(worker, workerToken.binder).accepted)

        assertEquals(2, registry.revokeGeneration(INSTANCE_ID, EPOCH, SESSION_ID))
        assertEquals(0, registry.activeCount())
        assertEquals(1, remoteToken.unlinkCount.get())
        assertEquals(1, workerToken.unlinkCount.get())
        val lateReplay = registry.attach(
            identity(
                effectiveGuestProcessName = "$ORIGIN_PACKAGE:late",
                processSlot = "$HOST_PACKAGE:v8",
                processId = 8008
            ),
            liveToken(registry).binder
        )
        assertFalse(lateReplay.accepted)
        assertEquals("component_process_generation_revoked", lateReplay.reason)

        assertEquals(0, registry.revokeInstance(INSTANCE_ID))
        assertTrue(registry.attach(remote, liveToken(registry).binder).accepted)
    }

    @Test
    fun `newer concurrent attach wins and older pending client cannot resurrect`() {
        val registry = registry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val oldToken = blockingToken(registry, enteredLink, releaseLink)
        val newToken = liveToken(registry)
        val oldIdentity = identity()
        val newIdentity = oldIdentity.copy(
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "$SESSION_ID-next",
            processId = REMOTE_PID + 1,
            processStartTicks = (REMOTE_PID + 1L) * 10L
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldResult = executor.submit<EngineComponentProcessClientAttachResult> {
                registry.attach(oldIdentity, oldToken.binder)
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))

            val newResult = registry.attach(newIdentity, newToken.binder)
            releaseLink.countDown()

            assertTrue(newResult.accepted)
            assertFalse(oldResult.get(5, TimeUnit.SECONDS).accepted)
            assertTrue(registry.isAuthoritative(newIdentity, newToken.binder))
            assertFalse(registry.isAuthoritative(oldIdentity, oldToken.binder))
            assertEquals(1, registry.activeCount())
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `generation revoke cancels pending Binder link without resurrection`() {
        val registry = registry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val token = blockingToken(registry, enteredLink, releaseLink)
        val identity = identity()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<EngineComponentProcessClientAttachResult> {
                registry.attach(identity, token.binder)
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))

            assertEquals(1, registry.revokeGeneration(INSTANCE_ID, EPOCH, SESSION_ID))
            releaseLink.countDown()

            assertFalse(result.get(5, TimeUnit.SECONDS).accepted)
            assertFalse(registry.queryByKey(identity.key).found)
            assertEquals(0, registry.activeCount())
            val replay = registry.attach(identity, liveToken(registry).binder)
            assertFalse(replay.accepted)
            assertEquals("component_process_generation_revoked", replay.reason)
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `synchronous Binder death during link never publishes authority`() {
        val registry = registry()
        val token = synchronousDeathToken(registry)
        var deaths = 0

        val result = registry.attach(identity(), token) { deaths++ }

        assertFalse(result.accepted)
        assertEquals("component_process_client_token_not_alive", result.reason)
        assertEquals(0, deaths)
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun `probe failure retires old client and higher process epoch can reuse pid`() {
        val identity = identity()
        var observed: EngineComponentProcessHostIdentity? = identity.hostIdentity()
        lateinit var registry: EngineComponentProcessClientRegistry
        registry = EngineComponentProcessClientRegistry(
            EngineComponentProcessIdentityProbe {
                assertFalse(Thread.holdsLock(registry))
                observed
            }
        )
        val oldToken = liveToken(registry)
        var authorityDeaths = 0
        assertTrue(
            registry.attach(identity, oldToken.binder) {
                assertFalse(Thread.holdsLock(registry))
                authorityDeaths++
            }.accepted
        )

        observed = null
        val stale = registry.queryByKey(identity.key)

        assertFalse(stale.found)
        assertEquals("component_process_client_not_live", stale.reason)
        assertEquals(1, oldToken.unlinkCount.get())
        assertEquals(1, authorityDeaths)
        assertEquals(0, registry.activeCount())

        val restarted = identity.copy(
            processEpoch = PROCESS_EPOCH + 1L,
            clientSessionId = "$CLIENT_SESSION_ID-next",
            processStartTicks = identity.processStartTicks + 1L
        )
        observed = restarted.hostIdentity()
        val newToken = liveToken(registry)

        val attached = registry.attach(restarted, newToken.binder)

        assertTrue(attached.accepted)
        assertTrue(attached.replacedGeneration)
        assertTrue(registry.isAuthoritative(restarted, newToken.binder))
    }

    @Test
    fun `query retires client when proc process name or starttime changes`() {
        val identity = identity()
        val changedIdentities = listOf(
            identity.hostIdentity().copy(processName = WORKER_SLOT),
            identity.hostIdentity().copy(processStartTicks = identity.processStartTicks + 1L)
        )

        changedIdentities.forEach { changedIdentity ->
            var observed = identity.hostIdentity()
            lateinit var registry: EngineComponentProcessClientRegistry
            registry = EngineComponentProcessClientRegistry(
                EngineComponentProcessIdentityProbe {
                    assertFalse(Thread.holdsLock(registry))
                    observed
                }
            )
            val token = liveToken(registry)
            assertTrue(registry.attach(identity, token.binder).accepted)
            assertTrue(registry.queryByPid(identity.processId).found)

            observed = changedIdentity
            val stale = registry.queryByPid(identity.processId)

            assertFalse(stale.found)
            assertEquals("component_process_client_not_live", stale.reason)
            assertEquals(1, token.unlinkCount.get())
            assertEquals(0, registry.activeCount())
        }
    }

    private fun identity(
        runtimeEpoch: Long = EPOCH,
        engineSessionId: String = SESSION_ID,
        processEpoch: Long = PROCESS_EPOCH,
        clientSessionId: String = CLIENT_SESSION_ID,
        effectiveGuestProcessName: String = REMOTE_PROCESS,
        processSlot: String = REMOTE_SLOT,
        processId: Int = REMOTE_PID,
        processStartTicks: Long = processId.toLong() * 10L
    ) = EngineComponentProcessClientIdentity(
        instanceId = INSTANCE_ID,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processEpoch = processEpoch,
        clientSessionId = clientSessionId,
        effectiveGuestProcessName = effectiveGuestProcessName,
        processSlot = processSlot,
        processId = processId,
        processStartTicks = processStartTicks
    )

    private fun registry() = EngineComponentProcessClientRegistry(
        EngineComponentProcessIdentityProbe { processId ->
            when (processId) {
                WORKER_PID -> EngineComponentProcessHostIdentity(
                    processName = WORKER_SLOT,
                    processStartTicks = processId.toLong() * 10L
                )
                REMOTE_PID, REMOTE_PID + 1, REMOTE_PID + 10 -> {
                    EngineComponentProcessHostIdentity(
                        processName = REMOTE_SLOT,
                        processStartTicks = processId.toLong() * 10L
                    )
                }
                else -> null
            }
        }
    )

    private fun EngineComponentProcessClientIdentity.hostIdentity() =
        EngineComponentProcessHostIdentity(
            processName = processSlot,
            processStartTicks = processStartTicks
        )

    private fun liveToken(registry: EngineComponentProcessClientRegistry): TestToken {
        val alive = AtomicBoolean(true)
        val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val linkCount = AtomicInteger()
        val unlinkCount = AtomicInteger()
        val binder = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } answers {
                assertFalse(Thread.holdsLock(registry))
                alive.get()
            }
            every { linkToDeath(any(), 0) } answers {
                assertFalse(Thread.holdsLock(registry))
                linkCount.incrementAndGet()
                recipients += firstArg<IBinder.DeathRecipient>()
            }
            every { unlinkToDeath(any(), 0) } answers {
                assertFalse(Thread.holdsLock(registry))
                unlinkCount.incrementAndGet()
                recipients.remove(firstArg<IBinder.DeathRecipient>())
                true
            }
        }
        return TestToken(binder, alive, recipients, linkCount, unlinkCount)
    }

    private fun blockingToken(
        registry: EngineComponentProcessClientRegistry,
        enteredLink: CountDownLatch,
        releaseLink: CountDownLatch
    ): TestToken {
        val token = liveToken(registry)
        every { token.binder.linkToDeath(any(), 0) } answers {
            assertFalse(Thread.holdsLock(registry))
            token.linkCount.incrementAndGet()
            token.recipients += firstArg<IBinder.DeathRecipient>()
            enteredLink.countDown()
            check(releaseLink.await(5, TimeUnit.SECONDS)) {
                "timed out waiting to release component process Binder link"
            }
        }
        return token
    }

    private fun deadToken(registry: EngineComponentProcessClientRegistry): IBinder =
        mockk(relaxed = true) {
            every { isBinderAlive } answers {
                assertFalse(Thread.holdsLock(registry))
                false
            }
            every { linkToDeath(any(), 0) } answers {
                assertFalse(Thread.holdsLock(registry))
            }
            every { unlinkToDeath(any(), 0) } answers {
                assertFalse(Thread.holdsLock(registry))
                true
            }
        }

    private fun synchronousDeathToken(registry: EngineComponentProcessClientRegistry): IBinder {
        val alive = AtomicBoolean(true)
        return mockk(relaxed = true) {
            every { isBinderAlive } answers {
                assertFalse(Thread.holdsLock(registry))
                alive.get()
            }
            every { linkToDeath(any(), 0) } answers {
                assertFalse(Thread.holdsLock(registry))
                alive.set(false)
                firstArg<IBinder.DeathRecipient>().binderDied()
            }
            every { unlinkToDeath(any(), 0) } answers {
                assertFalse(Thread.holdsLock(registry))
                true
            }
        }
    }

    private data class TestToken(
        val binder: IBinder,
        val alive: AtomicBoolean,
        val recipients: CopyOnWriteArrayList<IBinder.DeathRecipient>,
        val linkCount: AtomicInteger,
        val unlinkCount: AtomicInteger
    ) {
        fun die() {
            alive.set(false)
            recipients.toList().forEach(IBinder.DeathRecipient::binderDied)
        }
    }

    private companion object {
        const val INSTANCE_ID = "instance-component-process"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val REMOTE_PROCESS = "$ORIGIN_PACKAGE:remote"
        const val WORKER_PROCESS = "$ORIGIN_PACKAGE:worker"
        const val REMOTE_SLOT = "$HOST_PACKAGE:v4"
        const val WORKER_SLOT = "$HOST_PACKAGE:v5"
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
        const val PROCESS_EPOCH = 1L
        const val CLIENT_SESSION_ID = "component-client-session-1"
        const val REMOTE_PID = 4242
        const val WORKER_PID = 4343
    }
}

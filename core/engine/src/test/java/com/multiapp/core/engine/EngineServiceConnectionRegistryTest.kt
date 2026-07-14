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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineServiceConnectionRegistryTest {
    @Test
    fun `one Binder owns multiple Service records and links to death once`() {
        val registry = EngineServiceConnectionRegistry()
        val token = liveToken()
        val first = binding(component = FIRST_SERVICE)
        val second = binding(component = SECOND_SERVICE)

        val firstResult = registry.bind(first, token.binder)
        val secondResult = registry.bind(second, token.binder)
        val repeated = registry.bind(first, token.binder)
        val queried = registry.query(token.binder)

        assertTrue(firstResult.accepted)
        assertFalse(firstResult.idempotent)
        assertTrue(secondResult.accepted)
        assertFalse(secondResult.idempotent)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals("service_connection_binding_already_registered", repeated.reason)
        assertEquals(1, token.linkCount.get())
        assertTrue(queried.found)
        assertEquals(listOf(first, second), queried.bindings)
        assertEquals(1, registry.activeConnectionCount())
        assertEquals(2, registry.activeBindingCount())
        assertSame(first, registry.query(token.binder, first).bindings.single())
    }

    @Test
    fun `one Binder cannot claim another instance generation or process binding`() {
        val registry = EngineServiceConnectionRegistry()
        val token = liveToken()
        val authoritative = binding()
        assertTrue(registry.bind(authoritative, token.binder).accepted)

        val otherInstance = registry.bind(
            authoritative.copy(instanceId = "instance-other"),
            token.binder
        )
        val otherGeneration = registry.bind(
            authoritative.copy(
                runtimeEpoch = RUNTIME_EPOCH + 1,
                engineSessionId = "$ENGINE_SESSION_ID-next"
            ),
            token.binder
        )
        val otherProcess = registry.bind(
            authoritative.copy(processSlot = "$HOST_PACKAGE:v4", processId = PROCESS_ID + 1),
            token.binder
        )

        assertFalse(otherInstance.accepted)
        assertEquals("service_connection_instance_mismatch", otherInstance.reason)
        assertFalse(otherGeneration.accepted)
        assertEquals("service_connection_generation_mismatch", otherGeneration.reason)
        assertFalse(otherProcess.accepted)
        assertEquals("service_connection_process_binding_mismatch", otherProcess.reason)
        assertEquals(1, token.linkCount.get())
        assertEquals(listOf(authoritative), registry.query(token.binder).bindings)
    }

    @Test
    fun `unbind removes one record and remove-all unlinks only after the final record`() {
        val registry = EngineServiceConnectionRegistry()
        val token = liveToken()
        val first = binding(component = FIRST_SERVICE)
        val second = binding(component = SECOND_SERVICE)
        registry.bind(first, token.binder)
        registry.bind(second, token.binder)

        val unbound = registry.unbind(token.binder, first)

        assertTrue(unbound.removed)
        assertEquals(listOf(first), unbound.bindings)
        assertEquals(0, token.unlinkCount.get())
        assertEquals(listOf(second), registry.query(token.binder).bindings)

        val removed = registry.removeAll(token.binder)

        assertTrue(removed.removed)
        assertEquals(listOf(second), removed.bindings)
        assertEquals(1, token.unlinkCount.get())
        assertFalse(registry.query(token.binder).found)
        assertFalse(registry.removeAll(token.binder).removed)
    }

    @Test
    fun `Binder death atomically removes all records and invokes one callback outside the lock`() {
        val registry = EngineServiceConnectionRegistry()
        val token = liveToken()
        val first = binding(component = FIRST_SERVICE)
        val second = binding(component = SECOND_SERVICE)
        val callbackCount = AtomicInteger()
        var deadBindings = emptyList<EngineServiceConnectionBindingRecord>()
        val callbackOutsideLock = AtomicBoolean()
        registry.bind(first, token.binder) { removed ->
            callbackCount.incrementAndGet()
            deadBindings = removed
            val executor = Executors.newSingleThreadExecutor()
            try {
                callbackOutsideLock.set(
                    !executor.submit<Boolean> { registry.query(token.binder).found }
                        .get(2, TimeUnit.SECONDS)
                )
            } finally {
                executor.shutdownNow()
            }
        }
        registry.bind(second, token.binder)

        token.die()
        token.die()

        assertEquals(1, callbackCount.get())
        assertEquals(listOf(first, second), deadBindings)
        assertTrue(callbackOutsideLock.get())
        assertEquals(0, registry.activeConnectionCount())
        assertEquals(0, registry.activeBindingCount())
        assertEquals("service_connection_not_live", registry.query(token.binder).reason)
    }

    @Test
    fun `generation revoke removes every matching record and rejects replay from its tombstone`() {
        val registry = EngineServiceConnectionRegistry()
        val firstToken = liveToken()
        val secondToken = liveToken()
        registry.bind(binding(component = FIRST_SERVICE), firstToken.binder)
        registry.bind(binding(component = SECOND_SERVICE), secondToken.binder)

        assertEquals(
            2,
            registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID)
        )
        assertEquals(1, firstToken.unlinkCount.get())
        assertEquals(1, secondToken.unlinkCount.get())
        assertEquals(0, registry.activeBindingCount())

        val replayToken = liveToken()
        val replay = registry.bind(binding(), replayToken.binder)
        assertFalse(replay.accepted)
        assertEquals("service_connection_generation_revoked", replay.reason)
        assertEquals(0, replayToken.linkCount.get())

        val successorToken = liveToken()
        val successor = registry.bind(
            binding(
                runtimeEpoch = RUNTIME_EPOCH + 1,
                engineSessionId = "$ENGINE_SESSION_ID-next",
                processId = PROCESS_ID + 1
            ),
            successorToken.binder
        )
        assertTrue(successor.accepted)
        assertTrue(successor.replacedGeneration)

        val stale = registry.bind(binding(), liveToken().binder)
        assertFalse(stale.accepted)
        assertEquals("service_connection_generation_stale", stale.reason)
    }

    @Test
    fun `new generation atomically replaces old connections without firing death callbacks`() {
        val registry = EngineServiceConnectionRegistry()
        val oldToken = liveToken()
        val newToken = liveToken()
        var oldDeaths = 0
        val oldBinding = binding()
        val newBinding = binding(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-next",
            processId = PROCESS_ID + 1
        )
        registry.bind(oldBinding, oldToken.binder) { oldDeaths++ }

        val replacement = registry.bind(newBinding, newToken.binder)

        assertTrue(replacement.accepted)
        assertTrue(replacement.replacedGeneration)
        assertEquals(1, oldToken.unlinkCount.get())
        assertEquals(0, oldDeaths)
        assertFalse(registry.query(oldToken.binder).found)
        assertEquals(listOf(newBinding), registry.query(newToken.binder).bindings)

        val replay = registry.bind(oldBinding, liveToken().binder)
        assertFalse(replay.accepted)
        assertEquals("service_connection_generation_stale", replay.reason)
    }

    @Test
    fun `instance revoke clears live state and generation tombstones`() {
        val registry = EngineServiceConnectionRegistry()
        val token = liveToken()
        registry.bind(binding(), token.binder)
        assertEquals(
            1,
            registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID)
        )

        assertEquals(0, registry.revokeInstance(INSTANCE_ID))
        assertEquals(0, registry.tombstoneCount())

        val recreated = registry.bind(binding(), liveToken().binder)
        assertTrue(recreated.accepted)
        assertFalse(recreated.replacedGeneration)
    }

    @Test
    fun `process revoke removes only matching process connections`() {
        val registry = EngineServiceConnectionRegistry()
        val firstToken = liveToken()
        val secondToken = liveToken()
        val first = binding()
        val second = binding(
            component = SECOND_SERVICE,
            processSlot = "$HOST_PACKAGE:v4",
            processId = PROCESS_ID + 1
        )
        assertTrue(registry.bind(first, firstToken.binder).accepted)
        assertTrue(registry.bind(second, secondToken.binder).accepted)

        val removed = registry.revokeProcess(
            instanceId = first.instanceId,
            runtimeEpoch = first.runtimeEpoch,
            engineSessionId = first.engineSessionId,
            processSlot = first.processSlot,
            processId = first.processId
        )

        assertEquals(1, removed)
        assertFalse(registry.query(firstToken.binder).found)
        assertEquals(listOf(second), registry.query(secondToken.binder).bindings)
        assertEquals(1, firstToken.unlinkCount.get())
        assertEquals(0, secondToken.unlinkCount.get())
    }

    @Test
    fun `process revoke publishes released bindings after detaching authority`() {
        val registry = EngineServiceConnectionRegistry()
        val token = liveToken()
        val released = mutableListOf<List<EngineServiceConnectionBindingRecord>>()
        val record = binding()
        assertTrue(registry.bind(record, token.binder) { released += it }.accepted)

        val removed = registry.revokeProcess(
            instanceId = record.instanceId,
            runtimeEpoch = record.runtimeEpoch,
            engineSessionId = record.engineSessionId,
            processSlot = record.processSlot,
            processId = record.processId
        )

        assertEquals(1, removed)
        assertEquals(listOf(listOf(record)), released)
        assertFalse(registry.query(token.binder).found)
    }

    @Test
    fun `concurrent records share one pending Binder death link`() {
        val registry = EngineServiceConnectionRegistry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val token = blockingToken(enteredLink, releaseLink)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstResult = executor.submit<EngineServiceConnectionRegistrationResult> {
                registry.bind(binding(component = FIRST_SERVICE), token.binder)
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))
            val secondResult = executor.submit<EngineServiceConnectionRegistrationResult> {
                secondStarted.countDown()
                registry.bind(binding(component = SECOND_SERVICE), token.binder)
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            releaseLink.countDown()

            assertTrue(firstResult.get(5, TimeUnit.SECONDS).accepted)
            assertTrue(secondResult.get(5, TimeUnit.SECONDS).accepted)
            assertEquals(1, token.linkCount.get())
            assertEquals(2, registry.query(token.binder).bindings.size)
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `generation revoke cancels a pending Binder link without resurrection`() {
        val registry = EngineServiceConnectionRegistry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val token = blockingToken(enteredLink, releaseLink)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<EngineServiceConnectionRegistrationResult> {
                registry.bind(binding(), token.binder)
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))

            assertEquals(
                1,
                registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID)
            )
            releaseLink.countDown()

            val rejected = result.get(5, TimeUnit.SECONDS)
            assertFalse(rejected.accepted)
            assertEquals("service_connection_generation_revoked", rejected.reason)
            assertEquals(1, token.unlinkCount.get())
            assertEquals(0, registry.activeBindingCount())
            val replay = registry.bind(binding(), liveToken().binder)
            assertFalse(replay.accepted)
            assertEquals("service_connection_generation_revoked", replay.reason)
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `synchronous Binder death while linking never publishes records or callbacks`() {
        val registry = EngineServiceConnectionRegistry()
        val token = synchronousDeathToken()
        var callbacks = 0

        val result = registry.bind(binding(), token) { callbacks++ }

        assertFalse(result.accepted)
        assertEquals("service_connection_binder_not_alive", result.reason)
        assertEquals(0, callbacks)
        assertEquals(0, registry.activeBindingCount())
        assertEquals("service_connection_not_live", registry.query(token).reason)
    }

    private fun binding(
        component: String = FIRST_SERVICE,
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID,
        processSlot: String = PROCESS_SLOT,
        processId: Int = PROCESS_ID
    ) = EngineServiceConnectionBindingRecord(
        instanceId = INSTANCE_ID,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        processId = processId,
        component = component
    )

    private fun liveToken(): TestToken {
        val alive = AtomicBoolean(true)
        val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val linkCount = AtomicInteger()
        val unlinkCount = AtomicInteger()
        val binder = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } answers { alive.get() }
            every { linkToDeath(any(), 0) } answers {
                linkCount.incrementAndGet()
                recipients += firstArg<IBinder.DeathRecipient>()
            }
            every { unlinkToDeath(any(), 0) } answers {
                unlinkCount.incrementAndGet()
                recipients.remove(firstArg<IBinder.DeathRecipient>())
                true
            }
        }
        return TestToken(binder, alive, recipients, linkCount, unlinkCount)
    }

    private fun blockingToken(
        enteredLink: CountDownLatch,
        releaseLink: CountDownLatch
    ): TestToken {
        val token = liveToken()
        every { token.binder.linkToDeath(any(), 0) } answers {
            token.linkCount.incrementAndGet()
            token.recipients += firstArg<IBinder.DeathRecipient>()
            enteredLink.countDown()
            check(releaseLink.await(5, TimeUnit.SECONDS)) {
                "timed out waiting to release Binder link"
            }
        }
        return token
    }

    private fun synchronousDeathToken(): IBinder {
        val alive = AtomicBoolean(true)
        return mockk(relaxed = true) {
            every { isBinderAlive } answers { alive.get() }
            every { linkToDeath(any(), 0) } answers {
                alive.set(false)
                firstArg<IBinder.DeathRecipient>().binderDied()
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
        const val INSTANCE_ID = "instance-service-connection"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val FIRST_SERVICE = "$ORIGIN_PACKAGE.FirstService"
        const val SECOND_SERVICE = "$ORIGIN_PACKAGE.SecondService"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v3"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val PROCESS_ID = 4242
    }
}

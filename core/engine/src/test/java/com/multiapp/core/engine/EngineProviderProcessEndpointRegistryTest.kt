package com.multiapp.core.engine

import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
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

class EngineProviderProcessEndpointRegistryTest {
    @Test
    fun `registration and authoritative query preserve the complete endpoint identity`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val token = liveToken()
        val identity = identity()

        val registered = registry.register(identity, token.binder)
        val repeated = registry.register(identity, token.binder)
        val queried = registry.query(identity)

        assertTrue(registered.accepted)
        assertFalse(registered.idempotent)
        assertFalse(registered.replacedGeneration)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals(1, token.linkCount.get())
        assertTrue(queried.found)
        assertEquals(identity, queried.identity)
        assertSame(token.binder, queried.endpointBinder)
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `new runtime generation replaces old endpoint and old generation cannot replay`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val oldToken = liveToken()
        val newToken = liveToken()
        val replayToken = liveToken()
        val oldIdentity = identity()
        val newIdentity = identity(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-next",
            processId = PROCESS_ID + 1
        )
        var oldDeaths = 0
        var newDeaths = 0
        assertTrue(registry.register(oldIdentity, oldToken.binder) { oldDeaths++ }.accepted)

        val replacement = registry.register(newIdentity, newToken.binder) { newDeaths++ }
        val replay = registry.register(oldIdentity, replayToken.binder)

        assertTrue(replacement.accepted)
        assertTrue(replacement.replacedGeneration)
        assertEquals(1, oldToken.unlinkCount.get())
        assertFalse(replay.accepted)
        assertEquals("endpoint_generation_stale", replay.reason)
        assertEquals(0, replayToken.linkCount.get())
        assertFalse(registry.query(oldIdentity).found)
        assertEquals("endpoint_generation_stale", registry.query(oldIdentity).reason)
        assertTrue(registry.query(newIdentity).found)

        oldToken.die()
        assertEquals(0, oldDeaths)
        newToken.die()
        newToken.die()
        assertEquals(1, newDeaths)
        assertFalse(registry.query(newIdentity).found)
        assertEquals("endpoint_not_live", registry.query(newIdentity).reason)
    }

    @Test
    fun `same generation cannot change component process binding or Binder`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val currentToken = liveToken()
        val forgedToken = liveToken()
        val current = identity()
        assertTrue(registry.register(current, currentToken.binder).accepted)

        val componentConflict = registry.register(
            current.copy(providerClassName = "$ORIGIN_PACKAGE.OtherProvider"),
            forgedToken.binder
        )
        val processConflict = registry.register(
            current.copy(
                effectiveProcessName = "$ORIGIN_PACKAGE:other",
                processSlot = "$HOST_PACKAGE:v5"
            ),
            forgedToken.binder
        )
        val binderReplay = registry.register(current, forgedToken.binder)

        assertFalse(componentConflict.accepted)
        assertEquals("endpoint_generation_conflict", componentConflict.reason)
        assertFalse(processConflict.accepted)
        assertEquals("endpoint_generation_conflict", processConflict.reason)
        assertFalse(binderReplay.accepted)
        assertEquals("endpoint_generation_replayed", binderReplay.reason)
        assertEquals(0, forgedToken.linkCount.get())
        assertTrue(registry.query(current).found)
    }

    @Test
    fun `failed generation replacement keeps the previous endpoint authoritative`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val currentToken = liveToken()
        val deadToken = deadToken()
        val current = identity()
        val successor = identity(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-next",
            processId = PROCESS_ID + 1
        )
        assertTrue(registry.register(current, currentToken.binder).accepted)

        val replacement = registry.register(successor, deadToken)

        assertFalse(replacement.accepted)
        assertEquals("endpoint_binder_not_alive", replacement.reason)
        assertTrue(registry.query(current).found)
        assertEquals(0, currentToken.unlinkCount.get())
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `shared process Binder death clears every authority and invokes each callback once`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val token = liveToken()
        val data = identity(guestAuthority = DATA_AUTHORITY)
        val files = identity(guestAuthority = FILES_AUTHORITY)
        val deaths = mutableListOf<String>()
        assertTrue(registry.register(data, token.binder) { deaths += it.guestAuthority }.accepted)
        assertTrue(registry.register(files, token.binder) { deaths += it.guestAuthority }.accepted)
        assertEquals(2, registry.activeCount())

        token.die()

        assertEquals(setOf(DATA_AUTHORITY, FILES_AUTHORITY), deaths.toSet())
        assertEquals(2, deaths.size)
        assertEquals(0, registry.activeCount())
        assertEquals("endpoint_not_live", registry.query(data).reason)
        assertEquals("endpoint_not_live", registry.query(files).reason)
        assertFalse(registry.register(data, liveToken().binder).accepted)
    }

    @Test
    fun `explicit Binder death and generation revoke retain tombstones until instance revoke`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val sharedToken = liveToken()
        val data = identity(guestAuthority = DATA_AUTHORITY)
        val files = identity(guestAuthority = FILES_AUTHORITY)
        var deaths = 0
        assertTrue(registry.register(data, sharedToken.binder) { deaths++ }.accepted)
        assertTrue(registry.register(files, sharedToken.binder) { deaths++ }.accepted)

        assertEquals(2, registry.handleBinderDeath(sharedToken.binder))
        assertEquals(2, deaths)
        assertEquals(0, registry.activeCount())
        assertEquals(0, registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH + 1, ENGINE_SESSION_ID))
        assertEquals(0, registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID))
        assertFalse(registry.register(data, liveToken().binder).accepted)

        assertEquals(0, registry.revokeInstance(INSTANCE_ID))
        assertTrue(registry.register(data, liveToken().binder).accepted)
    }

    @Test
    fun `newer concurrent registration wins and older pending endpoint cannot resurrect`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val oldToken = blockingToken(enteredLink, releaseLink)
        val newToken = liveToken()
        val oldIdentity = identity()
        val newIdentity = identity(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-next",
            processId = PROCESS_ID + 1
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldResult = executor.submit<EngineProviderProcessEndpointRegistrationResult> {
                registry.register(oldIdentity, oldToken.binder)
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))

            val newResult = registry.register(newIdentity, newToken.binder)
            releaseLink.countDown()

            assertTrue(newResult.accepted)
            assertFalse(oldResult.get(5, TimeUnit.SECONDS).accepted)
            assertTrue(registry.query(newIdentity).found)
            assertFalse(registry.query(oldIdentity).found)
            assertEquals(1, registry.activeCount())
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `generation revoke cancels pending Binder link without state resurrection`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val token = blockingToken(enteredLink, releaseLink)
        val endpointIdentity = identity()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<EngineProviderProcessEndpointRegistrationResult> {
                registry.register(endpointIdentity, token.binder)
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))

            assertEquals(
                1,
                registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID)
            )
            releaseLink.countDown()

            assertFalse(result.get(5, TimeUnit.SECONDS).accepted)
            assertEquals(0, registry.activeCount())
            assertFalse(registry.query(endpointIdentity).found)
            val replay = registry.register(endpointIdentity, liveToken().binder)
            assertFalse(replay.accepted)
            assertEquals("endpoint_generation_replayed", replay.reason)
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `synchronous Binder death while linking cannot publish an endpoint`() {
        val registry = EngineProviderProcessEndpointRegistry()
        val endpointBinder = synchronousDeathToken()
        var deaths = 0

        val result = registry.register(identity(), endpointBinder) { deaths++ }

        assertFalse(result.accepted)
        assertEquals(0, deaths)
        assertEquals(0, registry.activeCount())
        assertEquals("endpoint_binder_not_alive", result.reason)
    }

    private fun identity(
        guestAuthority: String = DATA_AUTHORITY,
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID,
        processId: Int = PROCESS_ID
    ) = EngineProviderProcessEndpointIdentity(
        instanceId = INSTANCE_ID,
        guestAuthority = guestAuthority,
        providerClassName = PROVIDER_CLASS,
        declaredProcessName = DECLARED_PROCESS,
        effectiveProcessName = EFFECTIVE_PROCESS,
        processSlot = PROCESS_SLOT,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = processId
    )

    private fun liveToken(): TestToken {
        val alive = AtomicBoolean(true)
        val recipients = mutableListOf<IBinder.DeathRecipient>()
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

    private fun blockingToken(enteredLink: CountDownLatch, releaseLink: CountDownLatch): TestToken {
        val token = liveToken()
        every { token.binder.linkToDeath(any(), 0) } answers {
            token.linkCount.incrementAndGet()
            token.recipients += firstArg<IBinder.DeathRecipient>()
            enteredLink.countDown()
            check(releaseLink.await(5, TimeUnit.SECONDS)) { "timed out waiting to release Binder link" }
        }
        return token
    }

    private fun deadToken(): IBinder = mockk(relaxed = true) {
        every { isBinderAlive } returns false
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
        val recipients: MutableList<IBinder.DeathRecipient>,
        val linkCount: AtomicInteger,
        val unlinkCount: AtomicInteger
    ) {
        fun die() {
            alive.set(false)
            recipients.toList().forEach(IBinder.DeathRecipient::binderDied)
        }
    }

    private companion object {
        const val INSTANCE_ID = "instance-provider-process"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val DATA_AUTHORITY = "$ORIGIN_PACKAGE.data"
        const val FILES_AUTHORITY = "$ORIGIN_PACKAGE.files"
        const val PROVIDER_CLASS = "$ORIGIN_PACKAGE.RemoteProvider"
        const val DECLARED_PROCESS = ":provider"
        const val EFFECTIVE_PROCESS = "$ORIGIN_PACKAGE$DECLARED_PROCESS"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v4"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val PROCESS_ID = 4242
    }
}

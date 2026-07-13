package com.multiapp.core.engine

import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineProcessDeathRegistryTest {
    @Test
    fun `new generation unlinks old recipient and old callback is ignored`() {
        val registry = EngineProcessDeathRegistry()
        val firstToken = liveToken()
        val secondToken = liveToken()
        var firstDeaths = 0
        var secondDeaths = 0

        assertTrue(registry.register(INSTANCE_ID, 1L, "session-1", firstToken.binder) { firstDeaths++ })
        assertTrue(registry.register(INSTANCE_ID, 2L, "session-2", secondToken.binder) { secondDeaths++ })

        assertEquals(1, firstToken.unlinkCount.get())
        firstToken.recipient.captured.binderDied()
        assertEquals(0, firstDeaths)
        assertEquals(1, registry.size())

        secondToken.recipient.captured.binderDied()
        secondToken.recipient.captured.binderDied()
        assertEquals(1, secondDeaths)
        assertEquals(0, registry.size())
    }

    @Test
    fun `matching removal unlinks recipient and stale removal cannot remove current`() {
        val registry = EngineProcessDeathRegistry()
        val token = liveToken()
        assertTrue(registry.register(INSTANCE_ID, 2L, "session-2", token.binder) {})

        assertFalse(registry.remove(INSTANCE_ID, 1L, "session-1"))
        assertTrue(registry.remove(INSTANCE_ID, 2L, "session-2"))

        assertEquals(1, token.unlinkCount.get())
        assertEquals(0, registry.size())
    }

    @Test
    fun `failed replacement keeps existing death registration`() {
        val registry = EngineProcessDeathRegistry()
        val firstToken = liveToken()
        val failedToken = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(any(), 0) } throws IllegalStateException("dead")
            every { isBinderAlive } returns false
        }
        var firstDeaths = 0
        assertTrue(registry.register(INSTANCE_ID, 1L, "session-1", firstToken.binder) { firstDeaths++ })

        assertFalse(registry.register(INSTANCE_ID, 2L, "session-2", failedToken) {})
        assertEquals(1, registry.size())
        firstToken.recipient.captured.binderDied()
        assertEquals(1, firstDeaths)
    }

    @Test
    fun `stale generation cannot replace or unlink current recipient`() {
        val registry = EngineProcessDeathRegistry()
        val currentToken = liveToken()
        val staleToken = liveToken()
        var currentDeaths = 0
        assertTrue(registry.register(INSTANCE_ID, 2L, "session-2", currentToken.binder) { currentDeaths++ })

        assertFalse(registry.register(INSTANCE_ID, 1L, "session-1", staleToken.binder) {})
        assertEquals(0, staleToken.linkCount.get())
        assertEquals(0, currentToken.unlinkCount.get())
        currentToken.recipient.captured.binderDied()
        assertEquals(1, currentDeaths)
        val postDeathStaleToken = liveToken()
        assertFalse(registry.register(INSTANCE_ID, 1L, "session-1", postDeathStaleToken.binder) {})
        assertEquals(0, postDeathStaleToken.linkCount.get())
    }

    @Test
    fun `synchronous death while linking cannot retain registration or invoke callback`() {
        val registry = EngineProcessDeathRegistry()
        val recipient = slot<IBinder.DeathRecipient>()
        val unlinkCount = AtomicInteger()
        val token = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } returns true
            every { linkToDeath(capture(recipient), 0) } answers {
                recipient.captured.binderDied()
            }
            every { unlinkToDeath(any(), 0) } answers {
                unlinkCount.incrementAndGet()
                true
            }
        }
        var deaths = 0

        assertFalse(registry.register(INSTANCE_ID, 1L, "session-1", token) { deaths++ })
        assertEquals(0, deaths)
        assertEquals(0, registry.size())
        assertEquals(1, unlinkCount.get())
    }

    @Test
    fun `new generation wins concurrent pending registration`() {
        val registry = EngineProcessDeathRegistry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val oldToken = blockingToken(enteredLink, releaseLink)
        val newToken = liveToken()
        val executor = Executors.newSingleThreadExecutor()
        var oldDeaths = 0
        var newDeaths = 0
        try {
            val oldResult = executor.submit<Boolean> {
                registry.register(INSTANCE_ID, 1L, "session-1", oldToken.binder) { oldDeaths++ }
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))
            assertTrue(registry.register(INSTANCE_ID, 2L, "session-2", newToken.binder) { newDeaths++ })
            releaseLink.countDown()

            assertFalse(oldResult.get(5, TimeUnit.SECONDS))
            oldToken.recipient.captured.binderDied()
            assertEquals(0, oldDeaths)
            newToken.recipient.captured.binderDied()
            assertEquals(1, newDeaths)
            assertEquals(0, registry.size())
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `remove instance cancels pending link without resurrection`() {
        val registry = EngineProcessDeathRegistry()
        val enteredLink = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val token = blockingToken(enteredLink, releaseLink)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                registry.register(INSTANCE_ID, 1L, "session-1", token.binder) {}
            }
            assertTrue(enteredLink.await(5, TimeUnit.SECONDS))
            assertTrue(registry.removeInstance(INSTANCE_ID))
            releaseLink.countDown()

            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, registry.size())
        } finally {
            releaseLink.countDown()
            executor.shutdownNow()
        }
    }

    private fun liveToken(): TestToken {
        val recipient = slot<IBinder.DeathRecipient>()
        val unlinkCount = AtomicInteger()
        val linkCount = AtomicInteger()
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

    private fun blockingToken(enteredLink: CountDownLatch, releaseLink: CountDownLatch): TestToken {
        val recipient = slot<IBinder.DeathRecipient>()
        val linkCount = AtomicInteger()
        val unlinkCount = AtomicInteger()
        val binder = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } returns true
            every { linkToDeath(capture(recipient), 0) } answers {
                linkCount.incrementAndGet()
                enteredLink.countDown()
                check(releaseLink.await(5, TimeUnit.SECONDS)) { "timed out waiting to release linkToDeath" }
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
        const val INSTANCE_ID = "instance-death-registry"
    }
}

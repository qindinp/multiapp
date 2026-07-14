package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.engine.ipc.IEngineRuntimeService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EngineRuntimeServiceConnectionTest {
    @Test
    fun `old binder death cannot clear a newer connection`() {
        val first = serviceHandle()
        val second = serviceHandle()
        val services = ArrayDeque(listOf(first.candidate, second.candidate))
        val connection = EngineRuntimeServiceConnection {
            if (services.isEmpty()) null else services.removeFirst()
        }

        assertSame(first.service, connection.active())
        assertEquals(first.serverGenerationId, connection.activeServerGenerationId())
        first.alive.value = false
        assertSame(second.service, connection.active())
        assertEquals(second.serverGenerationId, connection.activeServerGenerationId())

        first.recipient.captured.binderDied()

        assertSame(second.service, connection.active())
    }

    @Test
    fun `synchronous binder death is not published as an active connection`() {
        val recipient = slot<IBinder.DeathRecipient>()
        var alive = true
        val binder = mockk<IBinder> {
            every { isBinderAlive } answers { alive }
            every { linkToDeath(capture(recipient), 0) } answers {
                alive = false
                recipient.captured.binderDied()
            }
            every { unlinkToDeath(any(), 0) } returns true
        }
        val service = mockk<IEngineRuntimeService> {
            every { asBinder() } returns binder
        }
        val connection = EngineRuntimeServiceConnection {
            candidate(service, binder, "server-sync-death")
        }

        assertNull(connection.active())
    }

    @Test
    fun `binder death callback clears current generation and reconnects to successor`() {
        val first = serviceHandle()
        val second = serviceHandle()
        val services = ArrayDeque(listOf(first.candidate, second.candidate))
        val connection = EngineRuntimeServiceConnection {
            if (services.isEmpty()) null else services.removeFirst()
        }

        assertSame(first.service, connection.active())
        first.recipient.captured.binderDied()

        assertSame(second.service, connection.active())
        assertEquals(second.serverGenerationId, connection.activeServerGenerationId())
    }

    @Test
    fun `concurrent callers establish only one live connection`() {
        val handle = serviceHandle()
        val connectorCalls = AtomicInteger(0)
        val connection = EngineRuntimeServiceConnection {
            connectorCalls.incrementAndGet()
            handle.candidate
        }
        val callerCount = 8
        val ready = CountDownLatch(callerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(callerCount)

        try {
            val futures = List(callerCount) {
                executor.submit<IEngineRuntimeService?> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    connection.active()
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()

            futures.forEach { future ->
                assertSame(handle.service, future.get(5, TimeUnit.SECONDS))
            }
            assertEquals(1, connectorCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun serviceHandle(): ServiceHandle {
        val recipient = slot<IBinder.DeathRecipient>()
        val alive = MutableBoolean(true)
        val binder = mockk<IBinder> {
            every { isBinderAlive } answers { alive.value }
            every { linkToDeath(capture(recipient), 0) } just Runs
            every { unlinkToDeath(any(), 0) } returns true
        }
        val service = mockk<IEngineRuntimeService> {
            every { asBinder() } returns binder
        }
        val serverGenerationId = "server-generation-${System.identityHashCode(service)}"
        return ServiceHandle(
            service = service,
            candidate = candidate(service, binder, serverGenerationId),
            recipient = recipient,
            alive = alive,
            serverGenerationId = serverGenerationId
        )
    }

    private fun candidate(
        service: IEngineRuntimeService,
        binder: IBinder,
        serverGenerationId: String
    ) = EngineRuntimeServiceCandidate(
        service = service,
        serverGenerationId = serverGenerationId,
        serverProcessId = 1234,
        serverProcessName = "com.multiapp.app:engine"
    ).also {
        assertSame(binder, it.service.asBinder())
    }

    private data class ServiceHandle(
        val service: IEngineRuntimeService,
        val candidate: EngineRuntimeServiceCandidate,
        val recipient: io.mockk.CapturingSlot<IBinder.DeathRecipient>,
        val alive: MutableBoolean,
        val serverGenerationId: String
    )

    private data class MutableBoolean(var value: Boolean)
}

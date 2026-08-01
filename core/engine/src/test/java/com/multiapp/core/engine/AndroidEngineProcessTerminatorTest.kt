package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProcessSlotContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidEngineProcessTerminatorTest {

    @Test
    fun `rejects unknown slots and the host process`() {
        val invalidSlot = fixture(
            running = emptyList(),
            probe = { EngineProcessProbe(exists = false) }
        ).terminator.terminateAndAwait(INSTANCE_ID, "$HOST_PACKAGE:v${EngineProcessSlotContract.PROCESS_SLOT_COUNT}", null)
        val nonCanonicalSlot = fixture(
            running = emptyList(),
            probe = { EngineProcessProbe(exists = false) }
        ).terminator.terminateAndAwait(INSTANCE_ID, "$HOST_PACKAGE:v02", null)

        val hostProcess = fixture(
            running = listOf(process(HOST_PID)),
            probe = { matchingProbe() }
        ).terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, HOST_PID)

        assertFalse(invalidSlot.confirmed)
        assertEquals("INVALID_GUEST_PROCESS_SLOT", invalidSlot.status)
        assertFalse(nonCanonicalSlot.confirmed)
        assertEquals("INVALID_GUEST_PROCESS_SLOT", nonCanonicalSlot.status)
        assertFalse(hostProcess.confirmed)
        assertEquals("HOST_PROCESS_REJECTED", hostProcess.status)
    }

    @Test
    fun `accepts high declared slots such as v9 v16 and v23`() {
        listOf("v9", "v16", "v23").forEach { slot ->
            val fixture = fixture(
                running = emptyList(),
                probe = { EngineProcessProbe(exists = false) }
            )

            val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, "$HOST_PACKAGE:$slot", null)

            assertTrue(result.confirmed, "expected confirmed for slot $slot")
            assertEquals("NOT_RUNNING", result.status)
        }
    }

    @Test
    fun `rejects a running slot whose pid differs from runtime authority`() {
        val fixture = fixture(
            running = listOf(process(GUEST_PID)),
            probe = { matchingProbe() }
        )

        val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, GUEST_PID + 1)

        assertFalse(result.confirmed)
        assertEquals("PROCESS_ID_MISMATCH", result.status)
        assertTrue(fixture.killedProcessIds.isEmpty())
    }

    @Test
    fun `reports not running when no slot process or expected pid exists`() {
        val fixture = fixture(
            running = emptyList(),
            probe = { EngineProcessProbe(exists = false) }
        )

        val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, null)

        assertTrue(result.confirmed)
        assertEquals("NOT_RUNNING", result.status)
        assertTrue(fixture.killedProcessIds.isEmpty())
    }

    @Test
    fun `confirms termination only after proc identity disappears`() {
        var probeCount = 0
        val fixture = fixture(
            running = listOf(process(GUEST_PID)),
            probe = {
                probeCount += 1
                if (probeCount == 1) matchingProbe() else EngineProcessProbe(exists = false)
            }
        )

        val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, GUEST_PID)

        assertTrue(result.confirmed)
        assertEquals("TERMINATED", result.status)
        assertEquals(listOf(GUEST_PID), fixture.killedProcessIds)
    }

    @Test
    fun `fails closed when proc identity cannot be verified`() {
        val fixture = fixture(
            running = listOf(process(GUEST_PID)),
            probe = { EngineProcessProbe(exists = true, failure = "permission denied") }
        )

        val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, GUEST_PID)

        assertFalse(result.confirmed)
        assertEquals("PROCESS_IDENTITY_UNVERIFIED", result.status)
        assertTrue(fixture.killedProcessIds.isEmpty())
    }

    @Test
    fun `returns timeout while the exact target pid remains alive`() {
        val fixture = fixture(
            running = listOf(process(GUEST_PID)),
            probe = { matchingProbe() },
            timeoutMs = 20L,
            pollMs = 10L
        )

        val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, GUEST_PID)

        assertFalse(result.confirmed)
        assertEquals("TERMINATION_TIMEOUT", result.status)
        assertEquals(listOf(GUEST_PID), fixture.killedProcessIds)
    }

    @Test
    fun `restores interrupt status and returns an explicit interrupted result`() {
        val fixture = fixture(
            running = listOf(process(GUEST_PID)),
            probe = { matchingProbe() },
            sleeper = { throw InterruptedException("cancelled") }
        )

        val result = fixture.terminator.terminateAndAwait(INSTANCE_ID, PROCESS_SLOT, GUEST_PID)

        assertFalse(result.confirmed)
        assertEquals("TERMINATION_INTERRUPTED", result.status)
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()
    }

    private fun fixture(
        running: List<EngineRunningProcess>,
        probe: (Int) -> EngineProcessProbe,
        timeoutMs: Long = 50L,
        pollMs: Long = 10L,
        sleeper: ((Long) -> Unit)? = null
    ): Fixture {
        var nowNanos = 0L
        val killed = mutableListOf<Int>()
        val actualSleeper = sleeper ?: { durationMs: Long ->
            nowNanos += durationMs * 1_000_000L
        }
        return Fixture(
            terminator = AndroidEngineProcessTerminator(
                hostPackageName = HOST_PACKAGE,
                hostUid = HOST_UID,
                hostProcessId = { HOST_PID },
                runningProcesses = { running },
                processProbe = probe,
                processKiller = killed::add,
                nanoClock = { nowNanos },
                sleeper = actualSleeper,
                terminationTimeoutMs = timeoutMs,
                terminationPollMs = pollMs
            ),
            killedProcessIds = killed
        )
    }

    private fun process(processId: Int) = EngineRunningProcess(
        processName = PROCESS_SLOT,
        uid = HOST_UID,
        processId = processId
    )

    private fun matchingProbe() = EngineProcessProbe(
        exists = true,
        commandLine = PROCESS_SLOT
    )

    private data class Fixture(
        val terminator: AndroidEngineProcessTerminator,
        val killedProcessIds: List<Int>
    )

    private companion object {
        const val HOST_PACKAGE = "com.multiapp.app"
        const val HOST_UID = 10_001
        const val HOST_PID = 100
        const val GUEST_PID = 200
        const val PROCESS_SLOT = "$HOST_PACKAGE:v2"
        const val INSTANCE_ID = "instance-1"
    }
}

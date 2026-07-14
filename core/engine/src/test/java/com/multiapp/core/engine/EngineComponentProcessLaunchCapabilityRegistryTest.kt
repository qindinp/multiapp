package com.multiapp.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineComponentProcessLaunchCapabilityRegistryTest {
    @Test
    fun `issue is idempotent until consume and replay is rejected`() {
        var now = 100L
        var session = 0
        val registry = EngineComponentProcessLaunchCapabilityRegistry(
            nanoTime = { now },
            clientSessionIdFactory = { "client-session-${++session}" },
            capabilityFactory = { "capability-${session.toString().padStart(40, '0')}" },
            ttlNanos = 50L
        )

        val first = registry.issue(assignment())
        val repeated = registry.issue(assignment())
        val consumed = registry.consume(requireNotNull(first.identity).attachCapability)
        val replay = registry.consume(requireNotNull(first.identity).attachCapability)
        val successor = registry.issue(assignment())

        assertTrue(first.accepted)
        assertFalse(first.idempotent)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals(first.identity, repeated.identity)
        assertTrue(consumed.accepted)
        assertFalse(replay.accepted)
        assertEquals("component_process_launch_capability_not_found", replay.reason)
        assertEquals(2L, successor.identity?.processEpoch)
        assertEquals("client-session-2", successor.identity?.clientSessionId)
    }

    @Test
    fun `expired capability cannot attach and replacement advances process epoch`() {
        var now = 10L
        var session = 0
        val registry = EngineComponentProcessLaunchCapabilityRegistry(
            nanoTime = { now },
            clientSessionIdFactory = { "session-${++session}" },
            capabilityFactory = { "capability-${session.toString().padStart(40, '0')}" },
            ttlNanos = 5L
        )
        val first = requireNotNull(registry.issue(assignment()).identity)

        now = first.expiresAtNanos
        val expired = registry.consume(first.attachCapability)
        val replacement = requireNotNull(registry.issue(assignment()).identity)

        assertFalse(expired.accepted)
        assertEquals("component_process_launch_capability_expired", expired.reason)
        assertEquals(first.processEpoch + 1L, replacement.processEpoch)
        assertTrue(replacement.expiresAtNanos > replacement.issuedAtNanos)
    }

    @Test
    fun `runtime generation revoke fences old tickets while newer generation can issue`() {
        val registry = EngineComponentProcessLaunchCapabilityRegistry(
            nanoTime = { 100L },
            clientSessionIdFactory = { "session" },
            capabilityFactory = { "capability-${"1".repeat(40)}" },
            ttlNanos = 50L
        )
        val first = requireNotNull(registry.issue(assignment()).identity)

        assertEquals(1, registry.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID))
        val revoked = registry.consume(first.attachCapability)
        val stale = registry.issue(assignment())
        val next = registry.issue(
            assignment(runtimeEpoch = RUNTIME_EPOCH + 1L, engineSessionId = "engine-session-next")
        )

        assertFalse(revoked.accepted)
        assertEquals("component_process_launch_capability_not_found", revoked.reason)
        assertFalse(stale.accepted)
        assertEquals("component_process_launch_generation_revoked", stale.reason)
        assertTrue(next.accepted)
        assertEquals(RUNTIME_EPOCH + 1L, next.identity?.runtimeEpoch)
    }

    private fun assignment(
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID
    ) = EngineComponentProcessSlotAssignment(
        key = EngineComponentProcessSlotKey(
            instanceId = INSTANCE_ID,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            guestProcessName = GUEST_PROCESS_NAME
        ),
        processSlot = PROCESS_SLOT
    )

    private companion object {
        const val INSTANCE_ID = "instance-component"
        const val RUNTIME_EPOCH = 7L
        const val ENGINE_SESSION_ID = "engine-session"
        const val GUEST_PROCESS_NAME = "com.test:remote"
        const val PROCESS_SLOT = "com.multiapp.app:v1"
    }
}

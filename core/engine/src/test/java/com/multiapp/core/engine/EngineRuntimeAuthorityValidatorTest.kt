package com.multiapp.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRuntimeAuthorityValidatorTest {
    @Test
    fun `unavailable authority fails closed`() {
        val decision = EngineRuntimeAuthorityValidator.validate(null)

        assertFalse(decision.allowed)
        assertFalse(decision.authorityAvailable)
        assertEquals("ipc_unavailable", decision.reason)
    }

    @Test
    fun `connected authority fails closed for missing runtime`() {
        val decision = EngineRuntimeAuthorityValidator.validate(
            snapshot(found = false, reason = "runtime_not_found")
        )

        assertFalse(decision.allowed)
        assertTrue(decision.authorityAvailable)
    }

    @Test
    fun `authority rejects process slot mismatch and dead runtime`() {
        val mismatch = EngineRuntimeAuthorityValidator.validate(
            snapshot = snapshot(processSlot = "com.multiapp.app:v1"),
            expectedProcessSlot = "com.multiapp.app:v2"
        )
        val dead = EngineRuntimeAuthorityValidator.validate(
            snapshot(runtimeState = "DEAD")
        )

        assertFalse(mismatch.allowed)
        assertTrue(mismatch.reason.contains("runtime_process_slot_mismatch"))
        assertFalse(dead.allowed)
        assertTrue(dead.reason.contains("runtime_state_dead"))
    }

    @Test
    fun `authority accepts matching live runtime`() {
        val decision = EngineRuntimeAuthorityValidator.validate(
            snapshot = snapshot(processSlot = "com.multiapp.app:v2"),
            expectedProcessSlot = "com.multiapp.app:v2"
        )

        assertTrue(decision.allowed)
        assertTrue(decision.authorityAvailable)
    }

    @Test
    fun `bootstrap may inspect generation before attach but runtime operations require live authority`() {
        val snapshot = snapshot(
            runtimeState = "CREATED",
            reason = "runtime_process_not_bound",
            liveAuthority = false
        )

        val runtimeOperation = EngineRuntimeAuthorityValidator.validate(snapshot)
        val bootstrapInspection = EngineRuntimeAuthorityValidator.validate(
            snapshot = snapshot,
            requireLiveAuthority = false
        )

        assertFalse(runtimeOperation.allowed)
        assertEquals("runtime_process_not_bound", runtimeOperation.reason)
        assertTrue(bootstrapInspection.allowed)
    }

    private fun snapshot(
        found: Boolean = true,
        processSlot: String? = "com.multiapp.app:v0",
        runtimeState: String? = "RUNNING",
        reason: String? = null,
        liveAuthority: Boolean = true
    ) = EngineRuntimeIpcSnapshot(
        found = found,
        instanceId = "instance-1",
        processSlot = processSlot,
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        runtimeEpoch = 10L,
        engineSessionId = "engine-1",
        evidenceSessionId = "evidence-1",
        runtimeState = runtimeState,
        processId = 1234,
        processName = processSlot,
        reason = reason,
        liveAuthority = liveAuthority
    )
}

package com.multiapp.core.engine

import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineProviderProcessEndpointControlPlaneTest {
    @Test
    fun `registration requires Binder caller PID and assigned component process slot`() {
        val authority = recordingAuthority(identity())
        val controlPlane = EngineProviderProcessEndpointControlPlane(authority)
        val token = liveBinder()

        val wrongPid = controlPlane.register(identity(), token, PROCESS_ID + 1, PROCESS_SLOT)
        val wrongSlot = controlPlane.register(identity(), token, PROCESS_ID, "$HOST_PACKAGE:v7")

        assertFalse(wrongPid.accepted)
        assertEquals("endpoint_calling_pid_mismatch", wrongPid.reason)
        assertFalse(wrongSlot.accepted)
        assertEquals("endpoint_calling_process_slot_mismatch", wrongSlot.reason)
        verify(exactly = 0) { token.linkToDeath(any(), any()) }
        assertEquals(0, authority.calls)
    }

    @Test
    fun `runtime authority must return the exact Provider component generation identity`() {
        val candidate = identity()
        val deniedAuthority = EngineProviderProcessEndpointRuntimeAuthority {
            EngineProviderProcessEndpointAuthorityDecision(
                allowed = false,
                expectedIdentity = null,
                reason = "provider_component_process_slot_unassigned"
            )
        }
        val mismatchedAuthority = recordingAuthority(
            candidate.copy(providerClassName = "$ORIGIN_PACKAGE.ForgedProvider")
        )
        val deniedToken = liveBinder()
        val mismatchedToken = liveBinder()

        val denied = EngineProviderProcessEndpointControlPlane(deniedAuthority).register(
            candidate,
            deniedToken,
            PROCESS_ID,
            PROCESS_SLOT
        )
        val mismatched = EngineProviderProcessEndpointControlPlane(mismatchedAuthority).register(
            candidate,
            mismatchedToken,
            PROCESS_ID,
            PROCESS_SLOT
        )

        assertFalse(denied.accepted)
        assertEquals("provider_component_process_slot_unassigned", denied.reason)
        assertFalse(mismatched.accepted)
        assertEquals("endpoint_runtime_authority_identity_mismatch", mismatched.reason)
        verify(exactly = 0) { deniedToken.linkToDeath(any(), any()) }
        verify(exactly = 0) { mismatchedToken.linkToDeath(any(), any()) }
    }

    @Test
    fun `authoritative query rejects endpoint immediately after engine generation advances`() {
        val expected = AtomicReference(identity())
        val authority = EngineProviderProcessEndpointRuntimeAuthority { candidate ->
            val current = expected.get()
            EngineProviderProcessEndpointAuthorityDecision(
                allowed = candidate.runtimeEpoch == current.runtimeEpoch,
                expectedIdentity = current.takeIf { candidate.runtimeEpoch == current.runtimeEpoch },
                reason = if (candidate.runtimeEpoch == current.runtimeEpoch) {
                    "provider_endpoint_expected"
                } else {
                    "provider_endpoint_runtime_generation_mismatch"
                }
            )
        }
        val controlPlane = EngineProviderProcessEndpointControlPlane(authority)
        val oldIdentity = identity()
        val oldToken = liveBinder()
        assertTrue(controlPlane.register(oldIdentity, oldToken, PROCESS_ID, PROCESS_SLOT).accepted)
        assertTrue(controlPlane.queryAuthoritative(oldIdentity).found)

        val successor = identity(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-next",
            processId = PROCESS_ID + 1
        )
        expected.set(successor)

        val staleQuery = controlPlane.queryAuthoritative(oldIdentity)
        val successorToken = liveBinder()
        val replacement = controlPlane.register(
            successor,
            successorToken,
            PROCESS_ID + 1,
            PROCESS_SLOT
        )

        assertFalse(staleQuery.found)
        assertEquals("provider_endpoint_runtime_generation_mismatch", staleQuery.reason)
        assertTrue(replacement.accepted)
        assertTrue(replacement.replacedGeneration)
        assertTrue(controlPlane.queryAuthoritative(successor).found)
    }

    @Test
    fun `unregister requires the endpoint process caller and exact Binder`() {
        val identity = identity()
        val controlPlane = EngineProviderProcessEndpointControlPlane(recordingAuthority(identity))
        val token = liveBinder()
        val forgedToken = liveBinder()
        assertTrue(controlPlane.register(identity, token, PROCESS_ID, PROCESS_SLOT).accepted)

        val wrongPid = controlPlane.unregister(identity, token, PROCESS_ID + 1, PROCESS_SLOT)
        val wrongBinder = controlPlane.unregister(identity, forgedToken, PROCESS_ID, PROCESS_SLOT)
        val removed = controlPlane.unregister(identity, token, PROCESS_ID, PROCESS_SLOT)

        assertFalse(wrongPid.removed)
        assertEquals("endpoint_calling_pid_mismatch", wrongPid.reason)
        assertFalse(wrongBinder.removed)
        assertEquals("endpoint_registration_not_found", wrongBinder.reason)
        assertTrue(removed.removed)
        assertFalse(controlPlane.queryAuthoritative(identity).found)
    }

    @Test
    fun `control plane exposes generation instance and Binder death cleanup`() {
        val identity = identity()
        var deaths = 0
        val controlPlane = EngineProviderProcessEndpointControlPlane(
            runtimeAuthority = recordingAuthority(identity),
            onEndpointDeath = { deaths++ }
        )
        val token = liveBinder()
        assertTrue(controlPlane.register(identity, token, PROCESS_ID, PROCESS_SLOT).accepted)

        assertEquals(1, controlPlane.handleBinderDeath(token))
        assertEquals(1, deaths)
        assertEquals(0, controlPlane.activeEndpointCount())
        assertEquals(0, controlPlane.revokeGeneration(INSTANCE_ID, RUNTIME_EPOCH, ENGINE_SESSION_ID))
        assertEquals(0, controlPlane.revokeInstance(INSTANCE_ID))
    }

    @Test
    fun `runtime authority failure is fail closed and component slot is mandatory`() {
        val token = liveBinder()
        val controlPlane = EngineProviderProcessEndpointControlPlane(
            EngineProviderProcessEndpointRuntimeAuthority { error("engine unavailable") }
        )

        val result = controlPlane.register(identity(), token, PROCESS_ID, PROCESS_SLOT)

        assertFalse(result.accepted)
        assertEquals("endpoint_runtime_authority_unavailable", result.reason)
        verify(exactly = 0) { token.linkToDeath(any(), any()) }
        assertFailsWith<IllegalArgumentException> {
            identity().copy(processSlot = "")
        }
    }

    private fun recordingAuthority(
        expected: EngineProviderProcessEndpointIdentity
    ): RecordingAuthority = RecordingAuthority(expected)

    private class RecordingAuthority(
        private val expected: EngineProviderProcessEndpointIdentity
    ) : EngineProviderProcessEndpointRuntimeAuthority {
        var calls: Int = 0
            private set

        override fun authorize(
            candidate: EngineProviderProcessEndpointIdentity
        ): EngineProviderProcessEndpointAuthorityDecision {
            calls++
            return EngineProviderProcessEndpointAuthorityDecision(
                allowed = true,
                expectedIdentity = expected,
                reason = "provider_endpoint_expected"
            )
        }
    }

    private fun liveBinder(): IBinder = mockk(relaxed = true) {
        every { isBinderAlive } returns true
    }

    private fun identity(
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID,
        processId: Int = PROCESS_ID
    ) = EngineProviderProcessEndpointIdentity(
        instanceId = INSTANCE_ID,
        guestAuthority = AUTHORITY,
        providerClassName = PROVIDER_CLASS,
        declaredProcessName = DECLARED_PROCESS,
        effectiveProcessName = EFFECTIVE_PROCESS,
        processSlot = PROCESS_SLOT,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = processId
    )

    private companion object {
        const val INSTANCE_ID = "instance-provider-control"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val AUTHORITY = "$ORIGIN_PACKAGE.data"
        const val PROVIDER_CLASS = "$ORIGIN_PACKAGE.RemoteProvider"
        const val DECLARED_PROCESS = ":provider"
        const val EFFECTIVE_PROCESS = "$ORIGIN_PACKAGE$DECLARED_PROCESS"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v4"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val PROCESS_ID = 4242
    }
}

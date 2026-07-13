package com.multiapp.app.container

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.multiapp.core.engine.EngineActivityLaunchAuthorization
import com.multiapp.core.engine.EngineRuntimeForegroundAck
import com.multiapp.core.loader.VirtualActivityLaunchAuthority
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineGuestForegroundAcknowledgerTest {
    @Test
    fun `authorized guest resume completion sends one capability bound ACK`() {
        val guestApplication = mockk<Application>(relaxed = true)
        val activity = guestActivity(guestApplication, IDENTITY)
        val classLoader = requireNotNull(activity.javaClass.classLoader)
        var authorizationCount = 0
        var ackCount = 0
        val acknowledger = EngineGuestForegroundAcknowledger(
            ackTransport = EngineGuestForegroundAckTransport { request ->
                ackCount += 1
                assertEquals(
                    REQUEST.copy(capabilityToken = IDENTITY.capabilityToken),
                    request
                )
                EngineRuntimeForegroundAck(true, false, "RUNNING", "guest_activity_resumed")
            },
            launchTransport = EngineGuestActivityLaunchTransport {
                authorizationCount += 1
                EngineActivityLaunchAuthorization(true, false, "launch_capability_authorized")
            },
            dispatchAck = { task -> task() }
        )

        assertTrue(acknowledger.register(guestApplication, classLoader, REQUEST))
        assertTrue(VirtualActivityLaunchAuthority.authorize(IDENTITY).accepted)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)

        assertEquals(1, authorizationCount)
        assertEquals(1, ackCount)
        assertEquals(0, acknowledger.registrationCount())
    }

    @Test
    fun `nonterminal rejected ACK keeps registration and retries capability`() {
        val guestApplication = mockk<Application>(relaxed = true)
        val activity = guestActivity(guestApplication, IDENTITY)
        val classLoader = requireNotNull(activity.javaClass.classLoader)
        var ackCount = 0
        val acknowledger = acknowledger(
            ackTransport = EngineGuestForegroundAckTransport { request ->
                ackCount += 1
                assertEquals(IDENTITY.capabilityToken, request.capabilityToken)
                if (ackCount == 1) {
                    EngineRuntimeForegroundAck(
                        false,
                        false,
                        "PREWARMED",
                        "launch_capability_not_found"
                    )
                } else {
                    EngineRuntimeForegroundAck(true, false, "RUNNING", "guest_activity_resumed")
                }
            }
        )

        assertTrue(acknowledger.register(guestApplication, classLoader, REQUEST))
        assertTrue(VirtualActivityLaunchAuthority.authorize(IDENTITY).accepted)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)

        assertEquals(1, ackCount)
        assertEquals(1, acknowledger.registrationCount())

        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)

        assertEquals(2, ackCount)
        assertEquals(0, acknowledger.registrationCount())
    }

    @Test
    fun `terminal rejected ACK clears registration`() {
        val guestApplication = mockk<Application>(relaxed = true)
        val activity = guestActivity(guestApplication, IDENTITY)
        val classLoader = requireNotNull(activity.javaClass.classLoader)
        val acknowledger = acknowledger(
            ackTransport = EngineGuestForegroundAckTransport {
                EngineRuntimeForegroundAck(false, false, "DEAD", "invalid_state:DEAD")
            }
        )

        assertTrue(acknowledger.register(guestApplication, classLoader, REQUEST))
        assertTrue(VirtualActivityLaunchAuthority.authorize(IDENTITY).accepted)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)

        assertEquals(0, acknowledger.registrationCount())
    }

    @Test
    fun `same capability cannot enqueue duplicate ACK while first is in flight`() {
        val guestApplication = mockk<Application>(relaxed = true)
        val activity = guestActivity(guestApplication, IDENTITY)
        val classLoader = requireNotNull(activity.javaClass.classLoader)
        val queued = mutableListOf<() -> Unit>()
        var ackCount = 0
        val acknowledger = EngineGuestForegroundAcknowledger(
            ackTransport = EngineGuestForegroundAckTransport {
                ackCount += 1
                EngineRuntimeForegroundAck(true, false, "RUNNING", "guest_activity_resumed")
            },
            launchTransport = acceptingLaunchTransport(),
            dispatchAck = { task -> queued += task }
        )

        assertTrue(acknowledger.register(guestApplication, classLoader, REQUEST))
        assertTrue(VirtualActivityLaunchAuthority.authorize(IDENTITY).accepted)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)

        assertEquals(1, queued.size)
        assertEquals(0, ackCount)
        assertEquals(1, acknowledger.registrationCount())

        queued.single().invoke()

        assertEquals(1, ackCount)
        assertEquals(0, acknowledger.registrationCount())
    }

    @Test
    fun `old generation cannot replace or authorize after newer registration`() {
        val guestApplication = mockk<Application>(relaxed = true)
        val classLoader = requireNotNull(VirtualActivityLaunchAuthority::class.java.classLoader)
        val acknowledger = acknowledger(
            ackTransport = EngineGuestForegroundAckTransport { null }
        )
        val newer = REQUEST.copy(runtimeEpoch = 43L, engineSessionId = "engine-session-43")

        assertTrue(acknowledger.register(guestApplication, classLoader, newer))
        assertFalse(acknowledger.register(guestApplication, classLoader, REQUEST))
        assertFalse(
            acknowledger.register(
                guestApplication,
                classLoader,
                newer.copy(engineSessionId = "foreign-session")
            )
        )

        assertFalse(VirtualActivityLaunchAuthority.authorize(IDENTITY).accepted)
        assertTrue(
            VirtualActivityLaunchAuthority.authorize(
                IDENTITY.copy(runtimeEpoch = 43L, engineSessionId = "engine-session-43")
            ).accepted
        )
        assertEquals(1, acknowledger.registrationCount())
    }

    @Test
    fun `host Application cannot acknowledge guest runtime`() {
        val guestApplication = mockk<Application>(relaxed = true)
        val hostApplication = mockk<Application>(relaxed = true)
        val activity = guestActivity(hostApplication, IDENTITY)
        val classLoader = requireNotNull(activity.javaClass.classLoader)
        var ackCount = 0
        val acknowledger = acknowledger(
            ackTransport = EngineGuestForegroundAckTransport {
                ackCount += 1
                null
            }
        )

        assertTrue(acknowledger.register(guestApplication, classLoader, REQUEST))
        assertTrue(VirtualActivityLaunchAuthority.authorize(IDENTITY).accepted)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)

        assertEquals(0, ackCount)
        assertEquals(1, acknowledger.registrationCount())
    }

    private fun acknowledger(
        ackTransport: EngineGuestForegroundAckTransport
    ) = EngineGuestForegroundAcknowledger(
        ackTransport = ackTransport,
        launchTransport = acceptingLaunchTransport(),
        dispatchAck = { task -> task() }
    )

    private fun acceptingLaunchTransport() = EngineGuestActivityLaunchTransport {
        EngineActivityLaunchAuthorization(true, false, "launch_capability_authorized")
    }

    private fun guestActivity(application: Application, identity: VirtualActivityLaunchIdentity): Activity {
        val intent = mockk<Intent>(relaxed = true) {
            every { getStringExtra("multiapp.instanceId") } returns identity.instanceId
            every { getStringExtra("multiapp.guestActivityClassName") } returns identity.guestActivityClassName
            every { getStringExtra("multiapp.engine.sessionId") } returns identity.engineSessionId
            every { getStringExtra("multiapp.engine.processSlot") } returns identity.processSlot
            every { getStringExtra("multiapp.engine.proxyActivityClassName") } returns
                identity.proxyActivityClassName
            every { getStringExtra("multiapp.engine.launchCapability") } returns identity.capabilityToken
            every { getLongExtra("multiapp.engine.runtimeEpoch", any()) } returns identity.runtimeEpoch
        }
        return mockk(relaxed = true) {
            every { this@mockk.application } returns application
            every { this@mockk.intent } returns intent
        }
    }

    private companion object {
        val REQUEST = EngineGuestForegroundAckRequest(
            instanceId = "instance-1",
            runtimeEpoch = 42L,
            engineSessionId = "engine-session-42",
            processSlot = "com.multiapp.app:v3",
            processId = 2468
        )
        val IDENTITY = VirtualActivityLaunchIdentity(
            capabilityToken = "capability-42",
            instanceId = REQUEST.instanceId,
            runtimeEpoch = REQUEST.runtimeEpoch,
            engineSessionId = REQUEST.engineSessionId,
            processSlot = REQUEST.processSlot,
            proxyActivityClassName = "com.multiapp.app.container.ProxyActivity3",
            guestActivityClassName = "com.test.MainActivity"
        )
    }
}

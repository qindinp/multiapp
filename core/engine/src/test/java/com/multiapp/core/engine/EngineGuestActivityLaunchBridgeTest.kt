package com.multiapp.core.engine

import android.content.Intent
import com.multiapp.core.loader.VirtualActivityManager
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineGuestActivityLaunchBridgeTest {
    @Test
    fun `proxy intent authorization preserves complete launch identity`() {
        var observedIdentity: EngineActivityLaunchIdentity? = null
        EngineGuestActivityLaunchBridge.install(
            validator = EngineGuestActivityLaunchValidator { identity ->
                observedIdentity = identity
                EngineActivityLaunchAuthorization(true, false, "accepted")
            },
            resumeObserver = EngineGuestActivityResumeObserver { _, _ -> }
        )

        val result = EngineGuestActivityLaunchBridge.authorizeProxyIntent(
            proxyIntent = proxyIntent(capabilityToken = "capability-1"),
            proxyActivityClassName = PROXY_ACTIVITY
        )

        assertTrue(result.accepted)
        assertEquals("capability-1", observedIdentity?.capabilityToken)
        assertEquals(INSTANCE_ID, observedIdentity?.instanceId)
        assertEquals(RUNTIME_EPOCH, observedIdentity?.runtimeEpoch)
        assertEquals(ENGINE_SESSION_ID, observedIdentity?.engineSessionId)
        assertEquals(PROCESS_SLOT, observedIdentity?.processSlot)
        assertEquals(PROXY_ACTIVITY, observedIdentity?.proxyActivityClassName)
        assertEquals(GUEST_ACTIVITY, observedIdentity?.guestActivityClassName)
    }

    @Test
    fun `proxy intent without capability fails closed before validator`() {
        var validatorCalls = 0
        EngineGuestActivityLaunchBridge.install(
            validator = EngineGuestActivityLaunchValidator {
                validatorCalls += 1
                EngineActivityLaunchAuthorization(true, false, "unexpected")
            },
            resumeObserver = EngineGuestActivityResumeObserver { _, _ -> }
        )

        val result = EngineGuestActivityLaunchBridge.authorizeProxyIntent(
            proxyIntent = proxyIntent(capabilityToken = null),
            proxyActivityClassName = PROXY_ACTIVITY
        )

        assertFalse(result.accepted)
        assertEquals("invalid_activity_launch_identity", result.reason)
        assertEquals(0, validatorCalls)
    }

    private fun proxyIntent(capabilityToken: String?): Intent = mockk(relaxed = true) {
        every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY) } returns capabilityToken
        every { getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID) } returns INSTANCE_ID
        every { getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, any()) } returns RUNTIME_EPOCH
        every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID) } returns ENGINE_SESSION_ID
        every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT) } returns PROCESS_SLOT
        every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME) } returns GUEST_ACTIVITY
    }

    private companion object {
        const val INSTANCE_ID = "instance-1"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val PROCESS_SLOT = "com.multiapp.app:v3"
        const val PROXY_ACTIVITY = "com.multiapp.app.container.ProxyActivity3"
        const val GUEST_ACTIVITY = "com.example.MainActivity"
    }
}

package com.multiapp.core.engine

import android.content.Intent
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.model.virtual.VirtualActivityRecord
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EngineProxyActivityLaunchCommitRequestsTest {
    @Test
    fun `proxy intent creates an engine commit request with its guest action`() {
        val proxyIntent = mockk<Intent>(relaxed = true) {
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY) } returns "capability-42"
            every { getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, 0L) } returns 42L
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID) } returns "session-42"
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT) } returns "com.multiapp.app:v5"
            every { getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN) } returns "launch-token"
        }
        val guestIntent = mockk<Intent>(relaxed = true) {
            every { flags } returns 16
            every { action } returns "com.test.minimal.NEW_INTENT_PROBE"
            every { dataString } returns "content://example/path"
            every { categories } returns setOf("com.test.CATEGORY")
            every { extras } returns null
        }
        val record = record()

        val request = EngineProxyActivityLaunchCommitRequests.fromProxyIntent(
            proxyIntent = proxyIntent,
            record = record,
            originalGuestIntent = { guestIntent }
        )

        assertNotNull(request)
        assertEquals("capability-42", request.identity.capabilityToken)
        assertEquals(record, request.record)
        assertEquals(16, request.intentFlags)
        assertEquals("com.test.minimal.NEW_INTENT_PROBE", request.dataIntent?.action)
        assertEquals("content://example/path", request.dataIntent?.dataUri)
        assertEquals(setOf("com.test.CATEGORY"), request.dataIntent?.categories)
    }

    @Test
    fun `proxy intent without a capability cannot create a commit request`() {
        val proxyIntent = mockk<Intent>(relaxed = true) {
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY) } returns null
        }

        val request = EngineProxyActivityLaunchCommitRequests.fromProxyIntent(
            proxyIntent = proxyIntent,
            record = record()
        )

        assertNull(request)
    }

    private fun record() = VirtualActivityRecord(
        token = "launch-token",
        instanceId = "instance-1",
        originPackageName = "com.test.minimal",
        guestActivityClassName = "com.test.minimal.MainActivity",
        proxyActivityClassName = "com.multiapp.app.container.ProxyActivitySingleTop5",
        launchMode = "singleTop",
        taskAffinity = "com.test.minimal:instance-1"
    )
}

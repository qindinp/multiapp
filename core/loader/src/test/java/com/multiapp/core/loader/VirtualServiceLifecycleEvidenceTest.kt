package com.multiapp.core.loader

import android.app.Service
import android.content.Intent
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualServiceLifecycleEvidenceTest {
    @Test
    fun `from start result records created and cached events`() {
        val request = startRequest()

        val created = VirtualServiceLifecycleEvidence.from(
            VirtualServiceRuntimeResult.CreatedAndStarted(
                startRequest = request,
                service = mockk<Service>(relaxed = true),
                startCommandResult = Service.START_STICKY,
                activeStartCount = 1,
                activeBindCount = 0,
                foreground = true,
                foregroundNotificationId = 77,
                foregroundServiceType = 8
            )
        )
        val cached = VirtualServiceLifecycleEvidence.from(
            VirtualServiceRuntimeResult.StartedCached(
                startRequest = request,
                service = mockk<Service>(relaxed = true),
                startCommandResult = Service.START_NOT_STICKY,
                activeStartCount = 1,
                activeBindCount = 1
            )
        )

        assertEquals(VirtualServiceLifecycleEvidence.Event.CREATED_AND_STARTED, created.event)
        assertTrue(created.success)
        assertFalse(created.cached)
        assertEquals(Service.START_STICKY, created.startCommandResult)
        assertEquals(1, created.activeStartCount)
        assertEquals(0, created.activeBindCount)
        assertTrue(created.foreground)
        assertEquals(77, created.foregroundNotificationId)
        assertEquals(8, created.foregroundServiceType)
        assertEquals(VirtualServiceLifecycleEvidence.Event.STARTED_CACHED, cached.event)
        assertTrue(cached.success)
        assertTrue(cached.cached)
        assertEquals(Service.START_NOT_STICKY, cached.startCommandResult)
        assertEquals(1, cached.activeStartCount)
        assertEquals(1, cached.activeBindCount)
    }

    @Test
    fun `from failure result records error details`() {
        val error = IllegalStateException("start failed")

        val evidence = VirtualServiceLifecycleEvidence.from(
            VirtualServiceRuntimeResult.OnStartCommandFailed(
                startRequest = startRequest(),
                service = mockk<Service>(relaxed = true),
                cached = true,
                error = error
            )
        )

        assertEquals(VirtualServiceLifecycleEvidence.Event.ON_START_COMMAND_FAILED, evidence.event)
        assertFalse(evidence.success)
        assertTrue(evidence.cached)
        assertEquals(error.javaClass.name, evidence.errorClassName)
        assertEquals("start failed", evidence.errorMessage)
    }

    @Test
    fun `from stop result records stopped not found and destroy failure`() {
        val stopped = VirtualServiceLifecycleEvidence.from(
            VirtualServiceRuntimeStopResult.Stopped(
                stopRequest = stopRequest(),
                service = mockk(relaxed = true),
                idleStopResult = HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = "stopServiceDestroyed",
                    hostStopServiceReturnValue = true,
                    detail = "hostStopServiceRequested"
                )
            )
        )
        val notFound = VirtualServiceLifecycleEvidence.from(
            VirtualServiceRuntimeStopResult.NotFound(stopRequest())
        )
        val failed = VirtualServiceLifecycleEvidence.from(
            VirtualServiceRuntimeStopResult.OnDestroyFailed(
                stopRequest = stopRequest(),
                service = mockk(relaxed = true),
                error = IllegalArgumentException("destroy failed")
            )
        )

        assertEquals(VirtualServiceLifecycleEvidence.Event.STOPPED, stopped.event)
        assertTrue(stopped.success)
        assertTrue(stopped.idleStopRequested)
        assertEquals("stopServiceDestroyed", stopped.idleStopReason)
        assertEquals(true, stopped.hostStopServiceReturnValue)
        assertEquals("hostStopServiceRequested", stopped.idleStopDetail)
        assertEquals(VirtualServiceLifecycleEvidence.Event.STOP_NOT_FOUND, notFound.event)
        assertFalse(notFound.success)
        assertEquals(VirtualServiceLifecycleEvidence.Event.ON_DESTROY_FAILED, failed.event)
        assertFalse(failed.success)
        assertEquals("destroy failed", failed.errorMessage)
    }

    private fun startRequest() = VirtualServiceStartRequest(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        sourceIntent = mockk<Intent>(relaxed = true),
        reason = "explicit"
    )

    private fun stopRequest() = VirtualServiceStopRequest(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        sourceIntent = mockk<Intent>(relaxed = true),
        reason = "explicitStop"
    )
}

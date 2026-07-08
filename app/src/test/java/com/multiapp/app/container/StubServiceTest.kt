package com.multiapp.app.container

import android.app.Service
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StubServiceTest {

    @Test
    fun `service runtime bind is async only for cache miss with instance id`() {
        assertTrue(StubService.shouldBindRuntimeAsync("inst-001", hasReusableRuntime = false))
        assertFalse(StubService.shouldBindRuntimeAsync("inst-001", hasReusableRuntime = true))
        assertFalse(StubService.shouldBindRuntimeAsync("", hasReusableRuntime = false))
    }

    @Test
    fun `synchronous service start returns guest onStartCommand result`() {
        assertEquals(
            Service.START_STICKY,
            StubService.hostStartCommandResult(
                guestStartCommandResult = Service.START_STICKY,
                asyncDispatch = false
            )
        )
        assertEquals(
            Service.START_REDELIVER_INTENT,
            StubService.hostStartCommandResult(
                guestStartCommandResult = Service.START_REDELIVER_INTENT,
                asyncDispatch = false
            )
        )
    }

    @Test
    fun `async service bootstrap returns default because host already returned`() {
        assertEquals(
            Service.START_NOT_STICKY,
            StubService.hostStartCommandResult(
                guestStartCommandResult = Service.START_STICKY,
                asyncDispatch = true
            )
        )
    }

    @Test
    fun `missing guest service result returns not sticky`() {
        assertEquals(
            Service.START_NOT_STICKY,
            StubService.hostStartCommandResult(
                guestStartCommandResult = null,
                asyncDispatch = false
            )
        )
    }
}

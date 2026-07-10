package com.multiapp.core.loader

import android.content.Intent
import android.os.IBinder
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivityThreadCompatTest {

    @Test
    fun `sendActivityResult invokes ActivityThread hidden bridge when available`() {
        val activityThread = FakeActivityThread()
        val token = mockk<IBinder>(relaxed = true)
        val data = mockk<Intent>(relaxed = true)

        val result = ActivityThreadCompat.sendActivityResult(
            activityToken = token,
            resultWho = null,
            requestCode = 42,
            resultCode = -1,
            data = data,
            activityThread = activityThread
        )

        assertEquals("PARTIAL", result.verdict)
        assertTrue(result.attempted)
        assertTrue(result.invoked)
        assertEquals("CALL_SCHEDULED_DELIVERY_PENDING_DEVICE_PROOF", result.reason)
        assertSame(token, activityThread.token)
        assertEquals(42, activityThread.requestCode)
        assertEquals(-1, activityThread.resultCode)
        assertSame(data, activityThread.data)
    }

    @Test
    fun `sendActivityResult skips when framework activity token is missing`() {
        val result = ActivityThreadCompat.sendActivityResult(
            activityToken = null,
            resultWho = null,
            requestCode = 42,
            resultCode = -1,
            data = null,
            activityThread = FakeActivityThread()
        )

        assertEquals("SKIPPED", result.verdict)
        assertEquals("ACTIVITY_THREAD_TOKEN_MISSING", result.reason)
        assertTrue(!result.attempted)
        assertTrue(!result.invoked)
    }

    @Test
    fun `sendActivityResult fails closed when hidden bridge is unavailable`() {
        val result = ActivityThreadCompat.sendActivityResult(
            activityToken = mockk(relaxed = true),
            resultWho = null,
            requestCode = 42,
            resultCode = -1,
            data = null,
            activityThread = Any()
        )

        assertEquals("FAIL", result.verdict)
        assertEquals("SEND_ACTIVITY_RESULT_METHOD_MISSING", result.reason)
        assertTrue(result.attempted)
        assertTrue(!result.invoked)
    }

    @Suppress("unused")
    private class FakeActivityThread {
        var token: IBinder? = null
        var resultWho: String? = null
        var requestCode: Int = 0
        var resultCode: Int = 0
        var data: Intent? = null

        fun sendActivityResult(
            token: IBinder,
            resultWho: String?,
            requestCode: Int,
            resultCode: Int,
            data: Intent?
        ) {
            this.token = token
            this.resultWho = resultWho
            this.requestCode = requestCode
            this.resultCode = resultCode
            this.data = data
        }
    }
}

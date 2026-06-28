package com.multiapp.core.loader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class VirtualReceiverRuntimeTest {

    @Test
    fun `dispatch returns receiver class not found when class loader cannot load receiver`() {
        val recorder = InMemoryVirtualBroadcastRecorder()
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, className -> throw ClassNotFoundException(className) },
            recorder = recorder
        )
        val request = runtimeRequest("com.test.minimal.MissingReceiver")

        val result = runtime.dispatch(request)

        val failed = assertIs<VirtualBroadcastResult.ReceiverClassNotFound>(result)
        assertEquals("com.test.minimal.MissingReceiver", failed.request.receiverClassName)
        assertIs<ClassNotFoundException>(failed.error)
        assertEquals("inst-001", failed.record.instanceId)
        assertEquals("com.test.minimal.MissingReceiver", failed.record.receiverClassName)
        assertEquals("com.test.ACTION_SYNC", failed.record.action)
        assertEquals(VirtualBroadcastResultCode.ReceiverClassNotFound, failed.record.result)
        assertEquals(listOf(failed.record), recorder.records())
    }

    @Test
    fun `dispatch returns onReceive failure when receiver throws`() {
        val receiver = FailingReceiver()
        val recorder = InMemoryVirtualBroadcastRecorder()
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, _ -> receiver },
            recorder = recorder
        )
        val request = runtimeRequest("com.test.minimal.ThrowingReceiver")

        val result = runtime.dispatch(request)

        val failed = assertIs<VirtualBroadcastResult.OnReceiveFailed>(result)
        assertSame(receiver, failed.receiver)
        assertEquals("receive failed", failed.error.message)
        assertEquals("inst-001", failed.record.instanceId)
        assertEquals("com.test.minimal.ThrowingReceiver", failed.record.receiverClassName)
        assertEquals("com.test.ACTION_SYNC", failed.record.action)
        assertEquals(VirtualBroadcastResultCode.OnReceiveFailed, failed.record.result)
        assertEquals(listOf(failed.record), recorder.records())
    }

    private fun runtimeRequest(receiverClassName: String): VirtualReceiverRuntimeRequest {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns "com.test.ACTION_SYNC"
        return VirtualReceiverRuntimeRequest(
            dispatchRequest = VirtualBroadcastDispatchRequest(
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                receiverClassName = receiverClassName,
                sourceIntent = intent,
                action = "com.test.ACTION_SYNC",
                reason = "explicit"
            ),
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )
    }

    private class FailingReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            error("receive failed")
        }
    }
}

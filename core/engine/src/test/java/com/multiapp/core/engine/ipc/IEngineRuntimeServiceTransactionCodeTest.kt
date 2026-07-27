package com.multiapp.core.engine.ipc

import android.os.IBinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IEngineRuntimeServiceTransactionCodeTest {
    @Test
    fun `new engine methods are appended without shifting legacy transaction codes`() {
        val createInstance = transactionCode("engineCreateInstance")
        val stopRuntime = transactionCode("stopRuntime")
        val queryCapabilities = transactionCode("engineQueryCapabilities")
        val clearInstanceData = transactionCode("engineClearInstanceData")
        val refreshPackage = transactionCode("engineRefreshPackage")

        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 2, createInstance)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 66, stopRuntime)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 67, queryCapabilities)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 68, clearInstanceData)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 69, refreshPackage)
        assertTrue(refreshPackage > stopRuntime)
    }

    private fun transactionCode(methodName: String): Int =
        IEngineRuntimeService.Stub::class.java
            .getDeclaredField("TRANSACTION_$methodName")
            .apply { isAccessible = true }
            .getInt(null)
}

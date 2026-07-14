package com.multiapp.core.loader

import android.app.Service
import android.os.IBinder
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualServiceRecordManagerTest {

    @Test
    fun `proxy stub is active for started bound or foreground records in the same slot`() {
        val records = VirtualServiceRecordManager()

        records.put(record(className = "StartedService", started = true))
        assertTrue(records.hasActiveRecordsForProxyStub(PROCESS_SLOT))
        records.remove(INSTANCE_ID, "StartedService")

        records.put(record(className = "BoundService", activeBindCount = 1))
        assertTrue(records.hasActiveRecordsForProxyStub(PROCESS_SLOT))
        records.remove(INSTANCE_ID, "BoundService")

        records.put(record(className = "ForegroundService", foreground = true))
        assertTrue(records.hasActiveRecordsForProxyStub(PROCESS_SLOT))
        assertFalse(records.hasActiveRecordsForProxyStub("com.multiapp.app:v4"))
        records.remove(INSTANCE_ID, "ForegroundService")

        records.put(record(className = "InactiveService"))
        assertFalse(records.hasActiveRecordsForProxyStub(PROCESS_SLOT))
    }

    @Test
    fun `repeated unbind update is idempotent`() {
        val records = VirtualServiceRecordManager()
        val binder = mockk<IBinder>(relaxed = true)
        records.put(
            record(
                className = "BoundService",
                activeBindCount = 1,
                bindings = mapOf(
                    BIND_KEY to VirtualServiceBindingRecord(
                        bindKey = BIND_KEY,
                        binder = binder,
                        activeConnectionCount = 1
                    )
                )
            )
        )

        val first = records.updateUnbind(INSTANCE_ID, "BoundService", BIND_KEY, false)!!
        val repeated = records.updateUnbind(INSTANCE_ID, "BoundService", BIND_KEY, false)!!

        assertEquals(0, first.activeBindCount)
        assertEquals(0, repeated.activeBindCount)
        assertFalse(BIND_KEY in repeated.bindings)
    }

    private fun record(
        className: String,
        started: Boolean = false,
        activeBindCount: Int = 0,
        bindings: Map<String, VirtualServiceBindingRecord> = emptyMap(),
        foreground: Boolean = false
    ) = VirtualServiceRecord(
        instanceId = INSTANCE_ID,
        originPackageName = "com.test.minimal",
        guestServiceClassName = className,
        service = mockk<Service>(relaxed = true),
        createdAtMs = 1L,
        processSlot = PROCESS_SLOT,
        started = started,
        activeBindCount = activeBindCount,
        bindings = bindings,
        foreground = foreground
    )

    private companion object {
        const val INSTANCE_ID = "inst-001"
        const val PROCESS_SLOT = "com.multiapp.app:v3"
        const val BIND_KEY = "bind-key"
    }
}

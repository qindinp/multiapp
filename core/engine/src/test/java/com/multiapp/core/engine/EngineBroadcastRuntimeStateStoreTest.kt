package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineBroadcastRuntimeStateStoreTest {
    @Test
    fun `file backed store restores Broadcast records and isolates clear by instance`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineBroadcastRuntimeStateFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEngineBroadcastRuntimeStateStore(file)
        writer.upsert(record("instance-1", "test.ACTION", EngineBroadcastDeliveryState.DELIVERED))
        writer.upsert(record("instance-2", "test.OTHER", EngineBroadcastDeliveryState.BLOCKED))

        val reader = FileBackedEngineBroadcastRuntimeStateStore(file)
        val restored = reader.list("instance-1").single()

        assertEquals(EngineBroadcastDeliveryState.DELIVERED, restored.state)
        assertEquals(EngineResultStatus.PASS, restored.lastVerdict)
        assertEquals(4L, restored.dispatchCount)
        assertEquals(3L, restored.deliveredCount)
        assertEquals(42L, restored.runtimeEpoch)

        reader.clear("instance-1")

        assertTrue(reader.list("instance-1").isEmpty())
        assertEquals("test.OTHER", reader.list("instance-2").single().action)
    }

    @Test
    fun `file backed update merges counters under one store lock`(@TempDir tempDir: File) {
        val store = FileBackedEngineBroadcastRuntimeStateStore(
            File(tempDir, EngineBroadcastRuntimeStateFiles.DEFAULT_FILE_NAME)
        )
        store.upsert(record("instance-1", "test.ACTION", EngineBroadcastDeliveryState.DELIVERED))

        store.update("instance-1", "com.test.EventReceiver", "test.ACTION") { existing ->
            requireNotNull(existing).copy(
                state = EngineBroadcastDeliveryState.FAILED,
                lastVerdict = EngineResultStatus.FAIL,
                lastReason = "on_receive_failed",
                dispatchCount = existing.dispatchCount + 1L,
                failureCount = existing.failureCount + 1L
            )
        }

        val updated = store.list("instance-1").single()
        assertEquals(5L, updated.dispatchCount)
        assertEquals(1L, updated.failureCount)
        assertEquals(EngineBroadcastDeliveryState.FAILED, updated.state)
    }

    @Test
    fun `atomic update rejects a record for another Broadcast key`() {
        val store = InMemoryEngineBroadcastRuntimeStateStore()

        assertFailsWith<IllegalArgumentException> {
            store.update("instance-1", "com.test.EventReceiver", "test.ACTION") {
                record("instance-2", "test.OTHER", EngineBroadcastDeliveryState.DELIVERED)
            }
        }

        assertTrue(store.list("instance-1").isEmpty())
        assertTrue(store.list("instance-2").isEmpty())
    }

    private fun record(
        instanceId: String,
        action: String,
        state: EngineBroadcastDeliveryState
    ) = EngineBroadcastRuntimeRecord(
        instanceId = instanceId,
        receiverClassName = "com.test.EventReceiver",
        action = action,
        processSlot = "com.multiapp.app:v0",
        runtimeEpoch = 42L,
        state = state,
        lastVerdict = if (state == EngineBroadcastDeliveryState.DELIVERED) {
            EngineResultStatus.PASS
        } else {
            EngineResultStatus.UNSUPPORTED
        },
        lastReason = "test_reason",
        dispatchCount = 4L,
        deliveredCount = if (state == EngineBroadcastDeliveryState.DELIVERED) 3L else 0L,
        blockedCount = if (state == EngineBroadcastDeliveryState.BLOCKED) 1L else 0L,
        updatedAtMs = 100L
    )
}

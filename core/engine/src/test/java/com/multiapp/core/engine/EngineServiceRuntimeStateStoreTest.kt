package com.multiapp.core.engine

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineServiceRuntimeStateStoreTest {
    @Test
    fun `file backed store restores Service records and isolates clear by instance`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineServiceRuntimeStateFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEngineServiceRuntimeStateStore(file)
        writer.upsert(record(instanceId = "instance-1", serviceClassName = "com.test.SyncService"))
        writer.upsert(record(instanceId = "instance-2", serviceClassName = "com.test.PushService"))

        val reader = FileBackedEngineServiceRuntimeStateStore(file)
        val restored = reader.list("instance-1").single()

        assertEquals(EngineServiceLifecycleState.STARTED, restored.state)
        assertEquals(2, restored.activeStartCount)
        assertEquals(1, restored.activeBindCount)
        assertEquals(3, restored.startCommandResult)

        reader.clear("instance-1")

        assertTrue(reader.list("instance-1").isEmpty())
        assertEquals("com.test.PushService", reader.list("instance-2").single().serviceClassName)
    }

    @Test
    fun `file backed store restores bound only Service lifecycle`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineServiceRuntimeStateFiles.DEFAULT_FILE_NAME)
        FileBackedEngineServiceRuntimeStateStore(file).upsert(
            record(
                instanceId = "instance-1",
                serviceClassName = "com.test.BoundService",
                state = EngineServiceLifecycleState.BOUND,
                activeStartCount = 0,
                activeBindCount = 2
            )
        )

        val restored = FileBackedEngineServiceRuntimeStateStore(file)
            .list("instance-1")
            .single()

        assertEquals(EngineServiceLifecycleState.BOUND, restored.state)
        assertEquals(0, restored.activeStartCount)
        assertEquals(2, restored.activeBindCount)
    }

    private fun record(
        instanceId: String,
        serviceClassName: String,
        state: EngineServiceLifecycleState = EngineServiceLifecycleState.STARTED,
        activeStartCount: Int = 2,
        activeBindCount: Int = 1
    ) = EngineServiceRuntimeRecord(
        instanceId = instanceId,
        serviceClassName = serviceClassName,
        processSlot = "com.multiapp.app:v1",
        runtimeEpoch = 1L,
        state = state,
        activeStartCount = activeStartCount,
        activeBindCount = activeBindCount,
        cached = true,
        startCommandResult = 3,
        updatedAtMs = 100L
    )
}

package com.multiapp.core.engine

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineProviderRuntimeStateStoreTest {
    @Test
    fun `file backed store restores Provider records and isolates clear by instance`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineProviderRuntimeStateFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEngineProviderRuntimeStateStore(file)
        writer.upsert(record("instance-1", "com.test.data", EngineProviderOperation.QUERY))
        writer.upsert(record("instance-2", "com.test.files", EngineProviderOperation.OPEN_FILE))

        val reader = FileBackedEngineProviderRuntimeStateStore(file)
        val restored = reader.list("instance-1").single()

        assertEquals(EngineProviderLifecycleState.READY, restored.state)
        assertEquals(EngineProviderOperation.QUERY, restored.lastOperation)
        assertEquals(3L, restored.operationCount)
        assertEquals(42L, restored.runtimeEpoch)

        reader.clear("instance-1")

        assertTrue(reader.list("instance-1").isEmpty())
        assertEquals("com.test.files", reader.list("instance-2").single().guestAuthority)
    }

    private fun record(
        instanceId: String,
        authority: String,
        operation: EngineProviderOperation
    ) = EngineProviderRuntimeRecord(
        instanceId = instanceId,
        guestAuthority = authority,
        providerClassName = "com.test.DataProvider",
        processSlot = "com.multiapp.app:v0",
        runtimeEpoch = 42L,
        cached = true,
        lastOperation = operation,
        operationCount = 3L,
        updatedAtMs = 100L
    )
}

package com.multiapp.core.engine

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnginePermissionGrantStoreTest {
    @Test
    fun `file store persists independent grants per instance`(@TempDir tempDir: File) {
        val file = File(tempDir, EnginePermissionGrantFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEnginePermissionGrantStore(file)
        writer.set(record("instance-1", granted = true))
        writer.set(record("instance-2", granted = false))

        val reader = FileBackedEnginePermissionGrantStore(file)

        assertEquals(true, reader.get("instance-1", PERMISSION)?.granted)
        assertEquals(false, reader.get("instance-2", PERMISSION)?.granted)
        assertEquals(1, reader.clear("instance-1", PERMISSION))
        assertNull(reader.get("instance-1", PERMISSION))
        assertEquals(false, reader.get("instance-2", PERMISSION)?.granted)
    }

    private fun record(instanceId: String, granted: Boolean) = EnginePermissionGrantRecord(
        instanceId = instanceId,
        permissionName = PERMISSION,
        granted = granted,
        source = EnginePermissionGrantSource.USER_DECISION,
        updatedAtMs = 100L
    )

    private companion object {
        const val PERMISSION = "com.example.permission.READ_PRIVATE"
    }
}

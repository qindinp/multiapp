package com.multiapp.core.engine

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineAppOpsStateStoreTest {
    @Test
    fun `file store restores instance modes and resets only requested operation`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineAppOpsStateFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEngineAppOpsStateStore(file)
        writer.set(EngineAppOpModeRecord("instance-1", 26, EngineAppOpModes.IGNORED, 100L))
        writer.set(EngineAppOpModeRecord("instance-1", 27, EngineAppOpModes.ALLOWED, 101L))
        writer.set(EngineAppOpModeRecord("instance-2", 26, EngineAppOpModes.ERRORED, 102L))

        val reader = FileBackedEngineAppOpsStateStore(file)

        assertEquals(EngineAppOpModes.IGNORED, reader.get("instance-1", 26)?.mode)
        assertEquals(listOf(26, 27), reader.list("instance-1").map { it.opCode })
        assertEquals(1, reader.reset("instance-1", 26))
        assertNull(reader.get("instance-1", 26))
        assertEquals(EngineAppOpModes.ALLOWED, reader.get("instance-1", 27)?.mode)
        assertEquals(EngineAppOpModes.ERRORED, reader.get("instance-2", 26)?.mode)
    }
}

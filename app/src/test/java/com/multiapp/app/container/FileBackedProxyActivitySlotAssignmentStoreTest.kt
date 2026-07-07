package com.multiapp.app.container

import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileBackedProxyActivitySlotAssignmentStoreTest {

    @Test
    fun `store persists proxy slot assignment across instances`(@TempDir tempDir: File) {
        val file = File(tempDir, "proxy_activity_slots.properties")
        val key = ProxyActivitySlotKey(
            instanceId = "inst-001",
            launchMode = null,
            taskKey = "com.test.minimal:inst-001"
        )

        FileBackedProxyActivitySlotAssignmentStore(file).save(
            key,
            "com.multiapp.app.container.ProxyActivity0"
        )

        val reloaded = FileBackedProxyActivitySlotAssignmentStore(file)

        assertEquals("com.multiapp.app.container.ProxyActivity0", reloaded.find(key))
    }

    @Test
    fun `store returns null for unknown slot assignment`(@TempDir tempDir: File) {
        val store = FileBackedProxyActivitySlotAssignmentStore(File(tempDir, "proxy_activity_slots.properties"))

        assertNull(
            store.find(
                ProxyActivitySlotKey(
                    instanceId = "inst-001",
                    launchMode = "singleTop",
                    taskKey = "com.test.minimal:inst-001"
                )
            )
        )
    }
}

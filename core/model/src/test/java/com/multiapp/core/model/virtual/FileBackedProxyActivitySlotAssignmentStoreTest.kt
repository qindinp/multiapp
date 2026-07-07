package com.multiapp.core.model.virtual

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals(key, reloaded.ownerOf("com.multiapp.app.container.ProxyActivity0"))
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
        assertNull(store.ownerOf("com.multiapp.app.container.ProxyActivity0"))
    }

    @Test
    fun `store prunes deleted instances and inactive proxy tasks`(@TempDir tempDir: File) {
        val store = FileBackedProxyActivitySlotAssignmentStore(File(tempDir, "proxy_activity_slots.properties"))
        val activeKey = ProxyActivitySlotKey(
            instanceId = "inst-active",
            launchMode = null,
            taskKey = "com.test:inst-active"
        )
        val inactiveTaskKey = ProxyActivitySlotKey(
            instanceId = "inst-inactive-task",
            launchMode = null,
            taskKey = "com.test:inst-inactive-task"
        )
        val deletedKey = ProxyActivitySlotKey(
            instanceId = "inst-deleted",
            launchMode = null,
            taskKey = "com.test:inst-deleted"
        )

        store.save(activeKey, "com.multiapp.app.container.ProxyActivity0")
        store.save(inactiveTaskKey, "com.multiapp.app.container.ProxyActivity1")
        store.save(deletedKey, "com.multiapp.app.container.ProxyActivity2")

        val removed = store.pruneStaleAssignments(
            validInstanceIds = setOf("inst-active", "inst-inactive-task"),
            liveProxyActivityClassNames = setOf("com.multiapp.app.container.ProxyActivity0"),
            knownProxyActivityClassNames = setOf(
                "com.multiapp.app.container.ProxyActivity0",
                "com.multiapp.app.container.ProxyActivity1",
                "com.multiapp.app.container.ProxyActivity2"
            )
        )

        assertEquals(2, removed)
        assertEquals("com.multiapp.app.container.ProxyActivity0", store.find(activeKey))
        assertNull(store.find(inactiveTaskKey))
        assertNull(store.find(deletedKey))
        assertNull(store.ownerOf("com.multiapp.app.container.ProxyActivity1"))
        assertNull(store.ownerOf("com.multiapp.app.container.ProxyActivity2"))
    }

    @Test
    fun `store prunes assignments for removed proxy classes`(@TempDir tempDir: File) {
        val store = FileBackedProxyActivitySlotAssignmentStore(File(tempDir, "proxy_activity_slots.properties"))
        val key = ProxyActivitySlotKey(
            instanceId = "inst-001",
            launchMode = null,
            taskKey = "com.test:inst-001"
        )
        store.save(key, "com.multiapp.app.container.OldProxyActivity")

        val removed = store.pruneStaleAssignments(
            validInstanceIds = setOf("inst-001"),
            liveProxyActivityClassNames = setOf("com.multiapp.app.container.OldProxyActivity"),
            knownProxyActivityClassNames = setOf("com.multiapp.app.container.ProxyActivity0")
        )

        assertEquals(1, removed)
        assertNull(store.find(key))
    }
}

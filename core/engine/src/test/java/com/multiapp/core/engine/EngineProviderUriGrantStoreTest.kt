package com.multiapp.core.engine

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineProviderUriGrantStoreTest {
    @Test
    fun `file store merges modes and restores exact and prefix grants`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineProviderUriGrantFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEngineProviderUriGrantStore(file)
        writer.grant(record(path = "/books/7", modes = READ))
        writer.grant(record(path = "/books/7", modes = WRITE))
        writer.grant(record(path = "/covers", modes = READ, prefix = true))

        val reader = FileBackedEngineProviderUriGrantStore(file)

        assertEquals(READ or WRITE, reader.listForInstance("target-1").first().modeFlags)
        assertNotNull(reader.findGrant("owner-1", "target-1", AUTHORITY, "/books/7", WRITE))
        assertNull(reader.findGrant("owner-1", "target-1", AUTHORITY, "/books/8", READ))
        assertNotNull(reader.findGrant("owner-1", "target-1", AUTHORITY, "/covers/large/7", READ))
    }

    @Test
    fun `revoke removes requested modes for uri and descendants without broad prefix leak`() {
        val store = InMemoryEngineProviderUriGrantStore()
        store.grant(record(path = "/books", modes = READ, prefix = true))
        store.grant(record(path = "/books/7", modes = READ or WRITE))
        store.grant(record(path = "/other", modes = READ))

        val changed = store.revoke(
            ownerInstanceId = "owner-1",
            targetInstanceId = null,
            guestAuthority = AUTHORITY,
            encodedPath = "/books/7",
            modeFlags = READ
        )

        assertEquals(1, changed)
        assertNotNull(store.findGrant("owner-1", "target-1", AUTHORITY, "/books/7", READ))
        assertNotNull(store.findGrant("owner-1", "target-1", AUTHORITY, "/books/7", WRITE))
        assertNotNull(store.findGrant("owner-1", "target-1", AUTHORITY, "/other", READ))

        store.revoke("owner-1", null, AUTHORITY, "/books", READ)

        assertNull(store.findGrant("owner-1", "target-1", AUTHORITY, "/books/8", READ))
        assertNotNull(store.findGrant("owner-1", "target-1", AUTHORITY, "/books/7", WRITE))
        assertTrue(store.listForInstance("target-1").any { it.encodedPath == "/other" })
    }

    @Test
    fun `persisted modes survive store recreation and release keeps transient access`(@TempDir tempDir: File) {
        val file = File(tempDir, EngineProviderUriGrantFiles.DEFAULT_FILE_NAME)
        val writer = FileBackedEngineProviderUriGrantStore(file)
        val offered = writer.grant(
            record(path = "/documents/7", modes = READ or WRITE, persistable = true)
        )

        val taken = writer.takePersistable(offered, READ, persistedAtMs = 200L)
        val restored = FileBackedEngineProviderUriGrantStore(file)

        assertEquals(READ, taken?.persistedModeFlags)
        assertEquals(200L, restored.listPersistedForTarget("target-1").single().persistedAtMs)
        assertNotNull(restored.findGrant("owner-1", "target-1", AUTHORITY, "/documents/7", READ))

        val released = restored.releasePersistable(
            restored.listPersistedForTarget("target-1").single(),
            READ,
            updatedAtMs = 300L
        )

        assertEquals(0, released?.persistedModeFlags)
        assertTrue(restored.listPersistedForTarget("target-1").isEmpty())
        assertNotNull(restored.findGrant("owner-1", "target-1", AUTHORITY, "/documents/7", READ))
    }

    @Test
    fun `non persistable offer cannot be taken and owner revoke removes persisted access`() {
        val store = InMemoryEngineProviderUriGrantStore()
        val transient = store.grant(record(path = "/documents/7", modes = READ))
        assertNull(store.takePersistable(transient, READ, persistedAtMs = 200L))

        val offered = store.grant(
            record(path = "/documents/8", modes = READ, persistable = true)
        )
        assertNotNull(store.takePersistable(offered, READ, persistedAtMs = 200L))

        store.revoke("owner-1", "target-1", AUTHORITY, "/documents/8", READ)

        assertNull(store.findGrant("owner-1", "target-1", AUTHORITY, "/documents/8", READ))
        assertTrue(store.listPersistedForTarget("target-1").isEmpty())
    }

    private fun record(
        path: String,
        modes: Int,
        prefix: Boolean = false,
        persistable: Boolean = false
    ) = EngineProviderUriGrantRecord(
        ownerInstanceId = "owner-1",
        targetInstanceId = "target-1",
        targetPackageName = "com.example.target",
        guestAuthority = AUTHORITY,
        encodedPath = path,
        modeFlags = modes,
        prefix = prefix,
        persistable = persistable,
        createdAtMs = 100L,
        updatedAtMs = 100L
    )

    private companion object {
        const val AUTHORITY = "com.example.provider"
        const val READ = 1
        const val WRITE = 2
    }
}

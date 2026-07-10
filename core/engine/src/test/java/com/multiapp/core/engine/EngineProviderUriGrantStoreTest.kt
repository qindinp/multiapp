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

    private fun record(
        path: String,
        modes: Int,
        prefix: Boolean = false
    ) = EngineProviderUriGrantRecord(
        ownerInstanceId = "owner-1",
        targetInstanceId = "target-1",
        targetPackageName = "com.example.target",
        guestAuthority = AUTHORITY,
        encodedPath = path,
        modeFlags = modes,
        prefix = prefix,
        persistable = false,
        createdAtMs = 100L,
        updatedAtMs = 100L
    )

    private companion object {
        const val AUTHORITY = "com.example.provider"
        const val READ = 1
        const val WRITE = 2
    }
}

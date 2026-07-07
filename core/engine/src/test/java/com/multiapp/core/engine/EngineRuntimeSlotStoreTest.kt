package com.multiapp.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class EngineRuntimeSlotStoreTest {

    @Test
    fun `file store persists assignment across reload`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_slots.properties")
        val store = FileBackedEngineRuntimeSlotStore(file)

        val assigned = store.assign(
            instanceId = "inst_001",
            originPackageName = "com.example.app",
            processCandidates = listOf("com.multiapp.app:v0"),
            proxyCandidates = listOf("com.multiapp.app.container.ProxyActivity0"),
            nowMs = 10L
        )

        val reloaded = FileBackedEngineRuntimeSlotStore(file)

        assertEquals(assigned.copy(updatedAtMs = 10L), reloaded.get("inst_001"))
    }

    @Test
    fun `same origin instances receive different process slots`() {
        val store = InMemoryEngineRuntimeSlotStore()
        val processCandidates = listOf("host:v0", "host:v1")
        val proxyCandidates = listOf("Proxy0", "Proxy1")

        val first = store.assign("inst_001", "com.example.app", processCandidates, proxyCandidates)
        val second = store.assign("inst_002", "com.example.app", processCandidates, proxyCandidates)

        assertNotEquals(first.processSlot, second.processSlot)
        assertNotEquals(first.proxySlot, second.proxySlot)
    }

    @Test
    fun `reassigning same instance keeps stable slots`() {
        val store = InMemoryEngineRuntimeSlotStore()
        val first = store.assign(
            instanceId = "inst_001",
            originPackageName = "com.example.app",
            processCandidates = listOf("host:v0", "host:v1"),
            proxyCandidates = listOf("Proxy0", "Proxy1"),
            nowMs = 10L
        )

        val second = store.assign(
            instanceId = "inst_001",
            originPackageName = "com.example.app",
            processCandidates = listOf("host:v0", "host:v1"),
            proxyCandidates = listOf("Proxy0", "Proxy1"),
            nowMs = 20L
        )

        assertEquals(first.processSlot, second.processSlot)
        assertEquals(first.proxySlot, second.proxySlot)
        assertEquals(20L, second.updatedAtMs)
    }

    @Test
    fun `proxy slot exhaustion is explicit`() {
        val store = InMemoryEngineRuntimeSlotStore()
        store.assign(
            instanceId = "inst_001",
            originPackageName = "com.example.one",
            processCandidates = listOf("host:v0", "host:v1"),
            proxyCandidates = listOf("Proxy0")
        )

        val error = assertFailsWith<EngineRuntimeSlotExhaustedException> {
            store.assign(
                instanceId = "inst_002",
                originPackageName = "com.example.two",
                processCandidates = listOf("host:v0", "host:v1"),
                proxyCandidates = listOf("Proxy0")
            )
        }

        assertEquals("proxy", error.slotType)
    }

    @Test
    fun `prune removes stale instance assignments`() {
        val store = InMemoryEngineRuntimeSlotStore()
        store.assign("inst_001", "com.example.one", listOf("host:v0"), listOf("Proxy0"))
        store.assign("inst_002", "com.example.two", listOf("host:v1"), listOf("Proxy1"))

        assertEquals(1, store.prune(setOf("inst_002")))

        assertNull(store.get("inst_001"))
        assertEquals("Proxy1", store.get("inst_002")?.proxySlot)
    }
}

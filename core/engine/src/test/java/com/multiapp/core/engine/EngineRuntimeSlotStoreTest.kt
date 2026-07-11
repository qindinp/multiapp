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
    fun `different origin instances cannot share a process slot`() {
        val store = InMemoryEngineRuntimeSlotStore()
        val processCandidates = listOf("host:v0", "host:v1")

        val first = store.assign(
            "inst_001",
            "com.example.one",
            processCandidates,
            listOf("ProxyStandard0", "ProxyStandard1")
        )
        val second = store.assign(
            "inst_002",
            "com.example.two",
            processCandidates,
            listOf("ProxySingleTask0", "ProxySingleTask1")
        )

        assertNotEquals(first.processSlot, second.processSlot)
    }

    @Test
    fun `file store repairs a persisted cross-package process slot collision`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_slots.properties")
        file.writeText(
            """
            inst_001.originPackageName=com.example.one
            inst_001.processSlot=host:v0
            inst_001.proxySlot=ProxyStandard0
            inst_001.updatedAtMs=1
            inst_002.originPackageName=com.example.two
            inst_002.processSlot=host:v0
            inst_002.proxySlot=ProxySingleTask0
            inst_002.updatedAtMs=1
            """.trimIndent()
        )
        val store = FileBackedEngineRuntimeSlotStore(file)

        val repaired = store.assign(
            "inst_001",
            "com.example.one",
            listOf("host:v0", "host:v1"),
            listOf("ProxyStandard0", "ProxyStandard1")
        )

        assertEquals("host:v1", repaired.processSlot)
        assertEquals("ProxyStandard1", repaired.proxySlot)
        assertEquals("host:v0", store.get("inst_002")?.processSlot)
    }

    @Test
    fun `paired candidates keep process slot aligned with proxy slot`() {
        val store = InMemoryEngineRuntimeSlotStore()
        val processCandidates = listOf("host:v0", "host:v1", "host:v2")
        val proxyCandidates = listOf("Proxy0", "Proxy1", "Proxy2")

        val assigned = store.assign(
            instanceId = "inst_001",
            originPackageName = "com.example.app",
            processCandidates = processCandidates,
            proxyCandidates = proxyCandidates
        )
        val proxyIndex = proxyCandidates.indexOf(assigned.proxySlot)

        assertEquals(processCandidates[proxyIndex], assigned.processSlot)
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

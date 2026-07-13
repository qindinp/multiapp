package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProxyActivitySlotAssignmentStoreProviderTest {

    @BeforeTest
    fun setUp() {
        ProxyActivitySlotAssignmentStoreProvider.clearForTests()
    }

    @AfterTest
    fun tearDown() {
        ProxyActivitySlotAssignmentStoreProvider.clearForTests()
    }

    @Test
    fun `uninstalled provider blocks allocation instead of selecting first candidate`() {
        val registry = DefaultVirtualAmsComponentDispatcher.defaultProxyActivityRegistry(
            hostPackageName = "com.multiapp.app"
        )

        val error = assertFailsWith<ProxyActivitySlotAssignmentStoreProviderNotInstalledException> {
            registry.allocate(
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                guestActivityClassName = "com.test.minimal.MainActivity",
                taskKey = "com.test.minimal:inst-001"
            )
        }

        assertTrue(error.message.orEmpty().contains("production proxy Activity slot authority"))
    }

    @Test
    fun `installed provider delegates filtered candidates only to authoritative store`() {
        val store = RecordingStore()
        ProxyActivitySlotAssignmentStoreProvider.install(store)
        assertSame(store, ProxyActivitySlotAssignmentStoreProvider.requireStore())
        val registry = DefaultVirtualAmsComponentDispatcher.defaultProxyActivityRegistry(
            hostPackageName = "com.multiapp.app"
        )

        val record = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            launchMode = "singleTop",
            taskKey = "com.test.minimal:inst-001"
        )

        val reserve = store.reserveCalls.single()
        assertEquals(reserve.candidates.last(), record.proxyActivityClassName)
        assertTrue(reserve.candidates.all { it.contains("ProxyActivitySingleTop") })
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `batch rollback restores remembered assignment through find and compareAndSet`() {
        val store = RecordingStore()
        val key = ProxyActivitySlotKey("inst-001", null, "com.test.minimal:inst-001")
        store.values[key] = "com.multiapp.app.container.ProxyActivity0"
        val rollback = ProxyActivitySlotAssignmentRollback(store)
        rollback.remember(key)
        store.values[key] = "com.multiapp.app.container.ProxyActivity1"

        rollback.restore()

        assertEquals("com.multiapp.app.container.ProxyActivity0", store.values[key])
        assertEquals(2, store.findCalls.count { it == key })
        assertEquals(
            listOf(
                CompareAndSetCall(
                    key = key,
                    expected = "com.multiapp.app.container.ProxyActivity1",
                    updated = "com.multiapp.app.container.ProxyActivity0"
                )
            ),
            store.compareAndSetCalls
        )
    }

    private class RecordingStore : ProxyActivitySlotAssignmentStore {
        val values = linkedMapOf<ProxyActivitySlotKey, String>()
        val findCalls = mutableListOf<ProxyActivitySlotKey>()
        val reserveCalls = mutableListOf<ReserveCall>()
        val compareAndSetCalls = mutableListOf<CompareAndSetCall>()
        var saveCalls: Int = 0

        override fun find(key: ProxyActivitySlotKey): String? {
            findCalls += key
            return values[key]
        }

        override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
            saveCalls += 1
            values[key] = proxyActivityClassName
        }

        override fun reserve(
            key: ProxyActivitySlotKey,
            candidateProxyActivityClassNames: List<String>
        ): String? {
            reserveCalls += ReserveCall(key, candidateProxyActivityClassNames)
            return candidateProxyActivityClassNames.lastOrNull()?.also { values[key] = it }
        }

        override fun compareAndSet(
            key: ProxyActivitySlotKey,
            expectedProxyActivityClassName: String?,
            newProxyActivityClassName: String?
        ): Boolean {
            compareAndSetCalls += CompareAndSetCall(
                key,
                expectedProxyActivityClassName,
                newProxyActivityClassName
            )
            if (values[key] != expectedProxyActivityClassName) return false
            if (newProxyActivityClassName == null) {
                values.remove(key)
            } else {
                values[key] = newProxyActivityClassName
            }
            return true
        }
    }

    private data class ReserveCall(
        val key: ProxyActivitySlotKey,
        val candidates: List<String>
    )

    private data class CompareAndSetCall(
        val key: ProxyActivitySlotKey,
        val expected: String?,
        val updated: String?
    )
}

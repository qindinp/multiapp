package com.multiapp.core.model.virtual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProxyActivityRegistryTest {

    @Test
    fun `allocate creates resolvable virtual activity record`() {
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))

        val record = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            nowMs = 1000L
        )

        assertEquals("inst-001", record.instanceId)
        assertEquals("com.test.minimal", record.originPackageName)
        assertEquals("com.test.minimal.MainActivity", record.guestActivityClassName)
        assertEquals("com.multiapp.app.container.ProxyActivity0", record.proxyActivityClassName)
        assertEquals(1000L, record.createdAtMs)
        assertNotNull(registry.resolve(record.token))
    }

    @Test
    fun `consume removes virtual activity record`() {
        val registry = ProxyActivityRegistry(listOf("ProxyActivity0"))
        val record = registry.allocate("inst-001", "com.test.minimal", "com.test.minimal.MainActivity")

        assertEquals(record, registry.consume(record.token))
        assertNull(registry.resolve(record.token))
    }

    @Test
    fun `registry prefers unused proxy slots before reusing`() {
        val registry = ProxyActivityRegistry(listOf("ProxyActivity0", "ProxyActivity1"))

        val instanceIdsBySlot = (0..20)
            .map { "inst-$it" }
            .groupBy { ProxyActivityRegistry.stableSlotIndex(it, 2) }
        val firstInstanceId = instanceIdsBySlot.getValue(0).first()
        val secondInstanceId = instanceIdsBySlot.getValue(1).first()

        val first = registry.allocate(firstInstanceId, "com.test.one", "com.test.one.MainActivity")
        val second = registry.allocate(secondInstanceId, "com.test.two", "com.test.two.MainActivity")

        assertNotEquals(first.proxyActivityClassName, second.proxyActivityClassName)
    }

    @Test
    fun `registry uses stable instance slot when records are empty`() {
        val firstRegistry = ProxyActivityRegistry(listOf("ProxyActivity0", "ProxyActivity1", "ProxyActivity2"))
        val secondRegistry = ProxyActivityRegistry(listOf("ProxyActivity0", "ProxyActivity1", "ProxyActivity2"))

        val first = firstRegistry.allocate("inst-stable", "com.test.one", "com.test.one.MainActivity")
        val second = secondRegistry.allocate("inst-stable", "com.test.one", "com.test.one.MainActivity")

        assertEquals(first.proxyActivityClassName, second.proxyActivityClassName)
    }

    @Test
    fun `registry reuses active assigned proxy slot for same instance task`() {
        val store = InMemorySlotStore()
        val registry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxyActivity0", "ProxyActivity1"),
            slotAssignmentStore = store
        )
        val root = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.RootActivity",
            taskKey = "com.test:inst-001",
            taskAffinity = "com.test:inst-001"
        )

        val detail = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.DetailActivity",
            taskKey = "com.test:inst-001",
            taskAffinity = "com.test:inst-001"
        )

        assertEquals(root.proxyActivityClassName, detail.proxyActivityClassName)
    }

    @Test
    fun `registry fails explicitly when all matching slots are owned by other tasks`() {
        val registry = ProxyActivityRegistry(listOf("ProxyActivity0", "ProxyActivity1"))
        registry.registerExisting(
            listOf(
                activeRecord(
                    token = "token-001",
                    instanceId = "inst-001",
                    proxyActivityClassName = "ProxyActivity0",
                    taskAffinity = "com.test:inst-001"
                ),
                activeRecord(
                    token = "token-002",
                    instanceId = "inst-002",
                    proxyActivityClassName = "ProxyActivity1",
                    taskAffinity = "com.test:inst-002"
                )
            )
        )

        val error = assertFailsWith<ProxyActivitySlotExhaustedException> {
            registry.allocate(
                instanceId = "inst-003",
                originPackageName = "com.test",
                guestActivityClassName = "com.test.MainActivity",
                taskKey = "com.test:inst-003",
                taskAffinity = "com.test:inst-003"
            )
        }

        assertEquals("inst-003", error.instanceId)
        assertEquals("com.test:inst-003", error.taskKey)
        assertEquals(2, error.candidateCount)
    }

    @Test
    fun `registry selects proxy slot matching launch mode`() {
        val registry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxyStandard0", "ProxySingleTop0", "ProxySingleTask0"),
            launchModeByClassName = mapOf(
                "ProxyStandard0" to null,
                "ProxySingleTop0" to "singleTop",
                "ProxySingleTask0" to "singleTask"
            )
        )

        val standard = registry.allocate("inst-001", "com.test", "com.test.StandardActivity")
        val singleTop = registry.allocate("inst-001", "com.test", "com.test.TopActivity", launchMode = "singleTop")
        val singleTask = registry.allocate("inst-001", "com.test", "com.test.TaskActivity", launchMode = "singleTask")

        assertEquals("ProxyStandard0", standard.proxyActivityClassName)
        assertEquals("ProxySingleTop0", singleTop.proxyActivityClassName)
        assertEquals("singleTop", singleTop.launchMode)
        assertEquals("ProxySingleTask0", singleTask.proxyActivityClassName)
        assertEquals("singleTask", singleTask.launchMode)
    }

    @Test
    fun `registry rejects unsupported and unknown launch modes`() {
        val registry = ProxyActivityRegistry(listOf("ProxyActivity0"))

        listOf("singleInstance", "singleInstancePerTask", "futureLaunchMode").forEach { launchMode ->
            assertEquals(launchMode, ProxyActivityRegistry.normalizeLaunchMode(launchMode))
            assertFalse(ProxyActivityRegistry.isSupportedLaunchMode(launchMode))
            val error = assertFailsWith<UnsupportedVirtualActivityLaunchModeException> {
                registry.allocate(
                    instanceId = "inst-001",
                    originPackageName = "com.test",
                    guestActivityClassName = "com.test.MainActivity",
                    launchMode = launchMode
                )
            }
            assertEquals(launchMode, error.launchMode)
        }

        assertEquals("futureLaunchMode", ProxyActivityRegistry.normalizeLaunchMode(" futureLaunchMode "))
        assertEquals(emptyList(), registry.listRecords())
    }

    @Test
    fun `registerExisting reserves active proxy slots`() {
        val registry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxySingleTop0", "ProxySingleTop1"),
            launchModeByClassName = mapOf(
                "ProxySingleTop0" to "singleTop",
                "ProxySingleTop1" to "singleTop"
            )
        )
        registry.registerExisting(
            listOf(
                VirtualActivityRecord(
                    token = "existing-token",
                    instanceId = "inst-001",
                    originPackageName = "com.test",
                    guestActivityClassName = "com.test.FirstActivity",
                    proxyActivityClassName = "ProxySingleTop0",
                    launchMode = "singleTop",
                    state = VirtualActivityState.RESUMED
                )
            )
        )

        val allocated = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.SecondActivity",
            launchMode = "singleTop"
        )

        assertEquals("ProxySingleTop1", allocated.proxyActivityClassName)
    }

    @Test
    fun `registry reuses persisted slot assignment for same instance task`() {
        val store = InMemorySlotStore()
        val firstRegistry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxyActivity0", "ProxyActivity1"),
            slotAssignmentStore = store
        )
        val first = firstRegistry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.MainActivity",
            taskKey = "com.test:inst-001"
        )
        val secondRegistry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxyActivity0", "ProxyActivity1"),
            slotAssignmentStore = store
        )

        val second = secondRegistry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.MainActivity",
            taskKey = "com.test:inst-001"
        )

        assertEquals(first.proxyActivityClassName, second.proxyActivityClassName)
    }

    @Test
    fun `registry ignores persisted slot when another active instance owns it`() {
        val store = InMemorySlotStore().apply {
            save(ProxyActivitySlotKey("inst-002", null, "com.test:inst-002"), "ProxyActivity0")
        }
        val registry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxyActivity0", "ProxyActivity1"),
            slotAssignmentStore = store
        )
        registry.registerExisting(
            listOf(
                VirtualActivityRecord(
                    token = "existing-token",
                    instanceId = "inst-001",
                    originPackageName = "com.test",
                    guestActivityClassName = "com.test.MainActivity",
                    proxyActivityClassName = "ProxyActivity0",
                    state = VirtualActivityState.RESUMED
                )
            )
        )

        val allocated = registry.allocate(
            instanceId = "inst-002",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.MainActivity",
            taskKey = "com.test:inst-002"
        )

        assertEquals("ProxyActivity1", allocated.proxyActivityClassName)
    }

    @Test
    fun `registry skips persisted slot owned by another task after process recreation`() {
        val occupiedKey = ProxyActivitySlotKey("inst-001", null, "com.test:inst-001")
        val store = InMemorySlotStore().apply {
            save(occupiedKey, "ProxyActivity0")
        }
        val taskKeyPreferringFirstSlot = (0..100)
            .map { "com.test:inst-new-$it" }
            .first { ProxyActivityRegistry.stableSlotIndex(it, 2) == 0 }
        val registry = ProxyActivityRegistry(
            proxyActivityClassNames = listOf("ProxyActivity0", "ProxyActivity1"),
            slotAssignmentStore = store
        )

        val allocated = registry.allocate(
            instanceId = "inst-002",
            originPackageName = "com.test",
            guestActivityClassName = "com.test.MainActivity",
            taskKey = taskKeyPreferringFirstSlot
        )

        assertEquals("ProxyActivity1", allocated.proxyActivityClassName)
    }

    private class InMemorySlotStore : ProxyActivitySlotAssignmentStore {
        private val values = linkedMapOf<ProxyActivitySlotKey, String>()

        override fun find(key: ProxyActivitySlotKey): String? = values[key]

        override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
            values[key] = proxyActivityClassName
        }

        override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? =
            values.entries.firstOrNull { it.value == proxyActivityClassName }?.key
    }

    private fun activeRecord(
        token: String,
        instanceId: String,
        proxyActivityClassName: String,
        taskAffinity: String
    ): VirtualActivityRecord = VirtualActivityRecord(
        token = token,
        instanceId = instanceId,
        originPackageName = "com.test",
        guestActivityClassName = "com.test.MainActivity",
        proxyActivityClassName = proxyActivityClassName,
        taskAffinity = taskAffinity,
        state = VirtualActivityState.RESUMED
    )
}

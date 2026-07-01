package com.multiapp.core.model.virtual

import kotlin.test.Test
import kotlin.test.assertEquals
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

        val first = registry.allocate("inst-001", "com.test.one", "com.test.one.MainActivity")
        val second = registry.allocate("inst-002", "com.test.two", "com.test.two.MainActivity")

        assertNotEquals(first.proxyActivityClassName, second.proxyActivityClassName)
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
        val singleTask = registry.allocate("inst-001", "com.test", "com.test.TaskActivity", launchMode = "singleInstance")

        assertEquals("ProxyStandard0", standard.proxyActivityClassName)
        assertEquals("ProxySingleTop0", singleTop.proxyActivityClassName)
        assertEquals("singleTop", singleTop.launchMode)
        assertEquals("ProxySingleTask0", singleTask.proxyActivityClassName)
        assertEquals("singleTask", singleTask.launchMode)
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
}

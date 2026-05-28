package com.multiapp.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubMappingTest {

    private fun createMapping(
        standard: List<String> = listOf("S1", "S2", "S3", "S4", "S5"),
        singleTop: List<String> = listOf("T1", "T2"),
        singleTask: List<String> = listOf("K1", "K2"),
        singleInstance: List<String> = listOf("I1"),
        services: List<String> = listOf("Svc1", "Svc2", "Svc3", "Svc4", "Svc5"),
        providers: List<String> = listOf("Prv1", "Prv2", "Prv3"),
        receivers: List<String> = listOf("Rcv1", "Rcv2")
    ) = StubMapping(
        processSlot = 0,
        stubActivityStandard = standard,
        stubActivitySingleTop = singleTop,
        stubActivitySingleTask = singleTask,
        stubActivitySingleInstance = singleInstance,
        stubServices = services,
        stubProviders = providers,
        stubReceivers = receivers
    )

    @Test
    fun `allActivities returns all four launch modes combined`() {
        val mapping = createMapping()
        val activities = mapping.allActivities()

        assertEquals(10, activities.size) // 5 + 2 + 2 + 1
        assertTrue(activities.containsAll(listOf("S1", "S2", "S3", "S4", "S5")))
        assertTrue(activities.containsAll(listOf("T1", "T2")))
        assertTrue(activities.containsAll(listOf("K1", "K2")))
        assertTrue(activities.contains("I1"))
    }

    @Test
    fun `allActivities preserves order - standard first`() {
        val mapping = createMapping()
        val activities = mapping.allActivities()

        assertEquals("S1", activities[0])
        assertEquals("T1", activities[5])
        assertEquals("K1", activities[7])
        assertEquals("I1", activities[9])
    }

    @Test
    fun `allComponents returns all activities plus services providers receivers`() {
        val mapping = createMapping()
        val components = mapping.allComponents()

        assertEquals(20, components.size) // 10 activities + 5 services + 3 providers + 2 receivers
    }

    @Test
    fun `allComponents includes services`() {
        val mapping = createMapping()
        val components = mapping.allComponents()

        assertTrue(components.containsAll(listOf("Svc1", "Svc2", "Svc3", "Svc4", "Svc5")))
    }

    @Test
    fun `allComponents includes providers`() {
        val mapping = createMapping()
        val components = mapping.allComponents()

        assertTrue(components.containsAll(listOf("Prv1", "Prv2", "Prv3")))
    }

    @Test
    fun `allComponents includes receivers`() {
        val mapping = createMapping()
        val components = mapping.allComponents()

        assertTrue(components.containsAll(listOf("Rcv1", "Rcv2")))
    }

    @Test
    fun `allActivities with empty lists returns empty`() {
        val mapping = createMapping(
            standard = emptyList(),
            singleTop = emptyList(),
            singleTask = emptyList(),
            singleInstance = emptyList()
        )

        assertTrue(mapping.allActivities().isEmpty())
    }

    @Test
    fun `allComponents with all empty lists returns empty`() {
        val mapping = StubMapping(
            processSlot = 0,
            stubActivityStandard = emptyList(),
            stubActivitySingleTop = emptyList(),
            stubActivitySingleTask = emptyList(),
            stubActivitySingleInstance = emptyList(),
            stubServices = emptyList(),
            stubProviders = emptyList(),
            stubReceivers = emptyList()
        )

        assertTrue(mapping.allComponents().isEmpty())
    }

    @Test
    fun `allActivities with partial empty lists`() {
        val mapping = createMapping(
            standard = listOf("S1"),
            singleTop = emptyList(),
            singleTask = listOf("K1"),
            singleInstance = emptyList()
        )

        val activities = mapping.allActivities()
        assertEquals(2, activities.size)
        assertEquals("S1", activities[0])
        assertEquals("K1", activities[1])
    }

    @Test
    fun `processSlot value is preserved`() {
        val mapping = StubMapping(
            processSlot = 7,
            stubActivityStandard = emptyList(),
            stubActivitySingleTop = emptyList(),
            stubActivitySingleTask = emptyList(),
            stubActivitySingleInstance = emptyList(),
            stubServices = emptyList(),
            stubProviders = emptyList(),
            stubReceivers = emptyList()
        )

        assertEquals(7, mapping.processSlot)
    }
}

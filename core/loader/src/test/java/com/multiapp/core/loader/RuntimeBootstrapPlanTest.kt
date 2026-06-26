package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeBootstrapPlanTest {

    @Test
    fun `loaderFactoryCompatible stages are in correct order`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        val expectedOrder = listOf(
            RuntimeStage.CONFIG,
            RuntimeStage.GUEST_CONTEXT,
            RuntimeStage.PACKAGE_METADATA,
            RuntimeStage.ORIGIN_APK,
            RuntimeStage.NATIVE_LIBS,
            RuntimeStage.RESOURCES,
            RuntimeStage.CLASS_LOADER,
            RuntimeStage.APPLICATION
        )
        assertEquals(expectedOrder, plan.stages)
    }

    @Test
    fun `loaderFactoryCompatible required and optional do not overlap`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        val overlap = plan.requiredStages.intersect(plan.optionalStages.toSet())
        assertTrue(overlap.isEmpty(), "required and optional stages must not overlap, found: $overlap")
    }

    @Test
    fun `loaderFactoryCompatible has no duplicate stages`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        val duplicates = plan.stages.groupBy { it }.filter { it.value.size > 1 }
        assertTrue(duplicates.isEmpty(), "stages must not contain duplicates, found: $duplicates")
    }

    @Test
    fun `loaderFactoryCompatible stages cover all RuntimeStage values`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        val allStages = RuntimeStage.values().toSet()
        val planStages = plan.stages.toSet()
        assertEquals(allStages, planStages, "plan must cover all RuntimeStage values")
    }

    @Test
    fun `loaderFactoryCompatible sets createdAtMs`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 42L)
        assertEquals(42L, plan.createdAtMs)
    }

    @Test
    fun `loaderFactoryCompatible required stages are CONFIG through NATIVE_LIBS`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        val expectedRequired = listOf(
            RuntimeStage.CONFIG,
            RuntimeStage.GUEST_CONTEXT,
            RuntimeStage.PACKAGE_METADATA,
            RuntimeStage.ORIGIN_APK,
            RuntimeStage.NATIVE_LIBS
        )
        assertEquals(expectedRequired, plan.requiredStages)
    }

    @Test
    fun `loaderFactoryCompatible optional stages are RESOURCES through APPLICATION`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        val expectedOptional = listOf(
            RuntimeStage.RESOURCES,
            RuntimeStage.CLASS_LOADER,
            RuntimeStage.APPLICATION
        )
        assertEquals(expectedOptional, plan.optionalStages)
    }

    @Test
    fun `loaderFactoryCompatible all required stages are in stages list`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        assertTrue(
            plan.stages.containsAll(plan.requiredStages),
            "all required stages must be present in stages list"
        )
    }

    @Test
    fun `loaderFactoryCompatible all optional stages are in stages list`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 1000L)

        assertTrue(
            plan.stages.containsAll(plan.optionalStages),
            "all optional stages must be present in stages list"
        )
    }

    @Test
    fun `constructor rejects duplicate stages`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeBootstrapPlan(
                stages = listOf(RuntimeStage.CONFIG, RuntimeStage.CONFIG),
                requiredStages = listOf(RuntimeStage.CONFIG),
                optionalStages = emptyList()
            )
        }
    }

    @Test
    fun `constructor rejects required and optional overlap`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeBootstrapPlan(
                stages = listOf(RuntimeStage.CONFIG, RuntimeStage.RESOURCES),
                requiredStages = listOf(RuntimeStage.CONFIG, RuntimeStage.RESOURCES),
                optionalStages = listOf(RuntimeStage.RESOURCES)
            )
        }
    }

    @Test
    fun `constructor rejects stages not covering required and optional`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeBootstrapPlan(
                stages = listOf(RuntimeStage.CONFIG),
                requiredStages = listOf(RuntimeStage.CONFIG, RuntimeStage.NATIVE_LIBS),
                optionalStages = emptyList()
            )
        }
    }

    @Test
    fun `plans have no duplicate stages and no required optional overlap`() {
        listOf(
            RuntimeBootstrapPlan.loaderFactoryCompatible()
        ).forEach { plan ->
            assertEquals(plan.stages.toSet().size, plan.stages.size)
            assertTrue(plan.requiredStages.intersect(plan.optionalStages.toSet()).isEmpty())
            assertTrue(plan.stages.containsAll(plan.requiredStages))
            assertTrue(plan.stages.containsAll(plan.optionalStages))
        }
    }
}

package com.multiapp.core.model.instance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VirtualInstanceRecordTest {

    @Test
    fun `data class equality works for identical records`() {
        val a = makeRecord(instanceId = "abc-123")
        val b = makeRecord(instanceId = "abc-123")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality for different instanceId`() {
        val a = makeRecord(instanceId = "aaa")
        val b = makeRecord(instanceId = "bbb")

        assertNotEquals(a, b)
    }

    @Test
    fun `default values are applied correctly`() {
        val record = makeRecord()

        assertEquals(1, record.schemaVersion)
        assertEquals(IconPolicy.DEFAULT, record.iconPolicy)
        assertEquals("strict", record.protectedBaselinePolicy)
        assertEquals(null, record.lastLaunchAtMs)
        assertEquals(0, record.launchCount)
        assertEquals(InstanceState.READY, record.state)
    }

    @Test
    fun `copy with launch state is immutable`() {
        val original = makeRecord(
            launchCount = 0,
            lastLaunchAtMs = null,
            state = InstanceState.READY
        )
        val launched = original.copy(
            launchCount = original.launchCount + 1,
            lastLaunchAtMs = 5000L,
            updatedAtMs = 5000L,
            state = InstanceState.RUNNING
        )

        // Original unchanged
        assertEquals(0, original.launchCount)
        assertEquals(null, original.lastLaunchAtMs)
        assertEquals(InstanceState.READY, original.state)

        // Copy has new values
        assertEquals(1, launched.launchCount)
        assertEquals(5000L, launched.lastLaunchAtMs)
        assertEquals(InstanceState.RUNNING, launched.state)
    }

    @Test
    fun `InstanceState enum has all expected values`() {
        val values = InstanceState.entries
        assertEquals(5, values.size)
        assertEquals(InstanceState.CREATING, InstanceState.valueOf("CREATING"))
        assertEquals(InstanceState.READY, InstanceState.valueOf("READY"))
        assertEquals(InstanceState.RUNNING, InstanceState.valueOf("RUNNING"))
        assertEquals(InstanceState.STOPPED, InstanceState.valueOf("STOPPED"))
        assertEquals(InstanceState.ERROR, InstanceState.valueOf("ERROR"))
    }

    @Test
    fun `IconPolicy enum has expected values`() {
        val values = IconPolicy.entries
        assertEquals(2, values.size)
        assertEquals(IconPolicy.DEFAULT, IconPolicy.valueOf("DEFAULT"))
        assertEquals(IconPolicy.CUSTOM, IconPolicy.valueOf("CUSTOM"))
    }

    @Test
    fun `CompatibilityMode enum has expected values`() {
        val values = CompatibilityMode.entries
        assertEquals(2, values.size)
        assertEquals(CompatibilityMode.STANDARD, CompatibilityMode.valueOf("STANDARD"))
        assertEquals(CompatibilityMode.LEGACY, CompatibilityMode.valueOf("LEGACY"))
        assertEquals(CompatibilityMode.STANDARD, CompatibilityMode.DEFAULT)
    }

    private fun makeRecord(
        instanceId: String = "id-1",
        originPackageName: String = "com.example",
        virtualPackageName: String = "com.multiapp.instance.id1",
        displayName: String = "Example",
        dataRoot: String = "/data/user/0/com.multiapp.instance.id1",
        compatibilityMode: CompatibilityMode = CompatibilityMode.STANDARD,
        createdAtMs: Long = 1000L,
        updatedAtMs: Long = 1000L,
        launchCount: Int = 0,
        lastLaunchAtMs: Long? = null,
        state: InstanceState = InstanceState.READY
    ) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        displayName = displayName,
        dataRoot = dataRoot,
        compatibilityMode = compatibilityMode,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        launchCount = launchCount,
        lastLaunchAtMs = lastLaunchAtMs,
        state = state
    )
}

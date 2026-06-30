package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigStageTest {

    private class FakeInstanceManager(
        private val records: Map<String, VirtualInstanceRecord> = emptyMap()
    ) : InstanceManager {
        override fun createInstance(
            originPackageName: String,
            displayName: String,
            compatibilityMode: CompatibilityMode
        ): Result<VirtualInstanceRecord> = Result.failure(UnsupportedOperationException())

        override fun getInstance(instanceId: String): VirtualInstanceRecord? = records[instanceId]

        override fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord> =
            records.values.filter { it.originPackageName == originPackageName }

        override fun listInstances(): List<VirtualInstanceRecord> = records.values.toList()

        override fun deleteInstance(instanceId: String): Boolean = false

        override fun updateLaunchState(instanceId: String): VirtualInstanceRecord? = null

        override fun getDataRoot(instanceId: String) = null
    }

    @Test
    fun `execute loads instance into context when instance exists`() {
        val instance = instanceRecord()
        val stage = ConfigStage(
            instanceManager = FakeInstanceManager(mapOf(instance.instanceId to instance)),
            clock = fixedClock(100L, 107L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = instance.instanceId))

        assertEquals(RuntimeStage.CONFIG, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(7L, output.result.durationMs)
        assertEquals(instance.instanceId, output.context.instanceId)
        assertSame(instance, output.context.instance)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(instance.instanceId, evidence["instanceId"])
        assertEquals(instance.originPackageName, evidence["originPackageName"])
    }

    @Test
    fun `execute fails terminally when instance is missing`() {
        val stage = ConfigStage(
            instanceManager = FakeInstanceManager(),
            clock = fixedClock(200L, 203L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "missing"))

        assertEquals(RuntimeStage.CONFIG, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Instance not found: missing", output.result.message)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.instance)
        assertTrue(output.isTerminalFailure)
    }

    private fun instanceRecord(
        instanceId: String = "inst-001",
        originPackageName: String = "com.example.app"
    ) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = "/data/instances/$instanceId",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

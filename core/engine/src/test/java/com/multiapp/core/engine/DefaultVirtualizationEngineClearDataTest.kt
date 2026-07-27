package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.VirtualInstallService
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultVirtualizationEngineClearDataTest {

    @Test
    fun `clear data preserves instance and install while recreating root and releasing slot`(@TempDir tempDir: File) {
        val manager = manager(tempDir)
        val instance = manager.createInstance(ORIGIN_PACKAGE, "Clear Test").getOrThrow()
        val marker = File(instance.dataRoot, "files/private.txt").apply {
            parentFile?.mkdirs()
            writeText("private")
        }
        val slots = InMemoryEngineRuntimeSlotStore().apply {
            assign(instance.instanceId, ORIGIN_PACKAGE, listOf("host:v0"), listOf("host.Proxy0"), 1L)
        }
        val installs = mockk<VirtualInstallService>(relaxed = true)
        val engine = engine(
            manager = manager,
            installs = installs,
            slots = slots,
            processTerminator = EngineProcessTerminator { _, _, processId ->
                EngineProcessTerminationResult(true, "TERMINATED", processId, "confirmed")
            }
        )

        val result = engine.clearInstanceData(instance.instanceId)

        assertEquals(EngineResultStatus.PASS, result.status)
        assertFalse(marker.exists())
        assertNotNull(manager.getInstance(instance.instanceId))
        assertTrue(manager.getDataRoot(instance.instanceId)?.filesDir?.isDirectory == true)
        assertNull(slots.get(instance.instanceId))
        verify(exactly = 0) { installs.deleteInstallRecord(any()) }
    }

    @Test
    fun `clear data fails closed without touching disk when termination is unconfirmed`(@TempDir tempDir: File) {
        val manager = manager(tempDir)
        val instance = manager.createInstance(ORIGIN_PACKAGE, "Clear Test").getOrThrow()
        val marker = File(instance.dataRoot, "files/keep.txt").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        val slots = InMemoryEngineRuntimeSlotStore().apply {
            assign(instance.instanceId, ORIGIN_PACKAGE, listOf("host:v0"), listOf("host.Proxy0"), 1L)
        }
        val engine = engine(
            manager = manager,
            installs = mockk(relaxed = true),
            slots = slots,
            processTerminator = EngineProcessTerminator { _, _, processId ->
                EngineProcessTerminationResult(false, "TIMEOUT", processId, "still_alive")
            }
        )

        val result = engine.clearInstanceData(instance.instanceId)

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertTrue(result.message.orEmpty().contains("clear_data_stop_failed"))
        assertTrue(marker.isFile)
        assertNotNull(manager.getInstance(instance.instanceId))
        assertNotNull(slots.get(instance.instanceId))
    }

    private fun manager(tempDir: File) = DefaultInstanceManager(
        store = JsonInstanceRecordStore(File(tempDir, "records")),
        dataRootBase = File(tempDir, "instances")
    )

    private fun engine(
        manager: DefaultInstanceManager,
        installs: VirtualInstallService,
        slots: EngineRuntimeSlotStore,
        processTerminator: EngineProcessTerminator
    ) = DefaultVirtualizationEngineCore(
        hostPackageName = "com.multiapp.app",
        instanceManager = manager,
        virtualInstallService = installs,
        activityLauncher = EngineActivityLauncher { },
        processTerminator = processTerminator,
        slotStore = slots
    )

    private companion object {
        const val ORIGIN_PACKAGE = "com.example.clear"
    }
}

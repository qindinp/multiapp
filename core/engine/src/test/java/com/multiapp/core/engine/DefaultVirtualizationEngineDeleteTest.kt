package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultVirtualizationEngineDeleteTest {
    @Test
    fun `delete clears subsystem state before data and releases slots only after record deletion`(@TempDir tempDir: File) {
        val instance = instance()
        val instanceManager = mockk<InstanceManager>()
        val registry = EngineRuntimeRegistry()
        val permissionStore = InMemoryEnginePermissionGrantStore()
        val proxySlotStore = FileBackedProxyActivitySlotAssignmentStore(File(tempDir, "proxy-slots.properties"))
        val proxySlotKey = ProxyActivitySlotKey(INSTANCE_ID, null, "task")
        proxySlotStore.save(proxySlotKey, PROXY_SLOT)
        val server = DefaultVirtualSystemServer(
            registry = registry,
            permissionGrantStore = permissionStore,
            proxyActivitySlotAssignmentStore = proxySlotStore
        )
        val slotStore = InMemoryEngineRuntimeSlotStore().apply {
            assign(INSTANCE_ID, ORIGIN_PACKAGE, listOf(PROCESS_SLOT), listOf(PROXY_SLOT))
        }
        registry.register(runtime(VirtualRuntimeState.CREATED, processId = null))
        permissionStore.set(
            EnginePermissionGrantRecord(
                instanceId = INSTANCE_ID,
                permissionName = "android.permission.CAMERA",
                granted = true,
                source = EnginePermissionGrantSource.USER_DECISION,
                updatedAtMs = 100L
            )
        )
        every { instanceManager.getInstance(INSTANCE_ID) } returns instance
        every { instanceManager.deleteInstance(INSTANCE_ID) } answers {
            assertNull(registry.get(INSTANCE_ID))
            assertEquals(PROCESS_SLOT, slotStore.get(INSTANCE_ID)?.processSlot)
            assertEquals(PROXY_SLOT, proxySlotStore.find(proxySlotKey))
            assertTrue(permissionStore.list(INSTANCE_ID).isEmpty())
            true
        }
        val engine = engine(instanceManager, registry, server, slotStore)

        val result = engine.deleteInstance(INSTANCE_ID)

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals("deleteInstance", result.operation)
        assertNull(slotStore.get(INSTANCE_ID))
        assertNull(proxySlotStore.find(proxySlotKey))
        verify(exactly = 1) { instanceManager.deleteInstance(INSTANCE_ID) }
    }

    @Test
    fun `delete terminates a persisted process slot when runtime state is missing`() {
        val instanceManager = mockk<InstanceManager>()
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val slotStore = InMemoryEngineRuntimeSlotStore().apply {
            assign(INSTANCE_ID, ORIGIN_PACKAGE, listOf(PROCESS_SLOT), listOf(PROXY_SLOT))
        }
        every { instanceManager.getInstance(INSTANCE_ID) } returns instance()
        every { instanceManager.deleteInstance(INSTANCE_ID) } returns true
        var terminationRequest: Triple<String, String, Int?>? = null
        val engine = engine(
            instanceManager,
            registry,
            server,
            slotStore,
            processTerminator = EngineProcessTerminator { instanceId, processSlot, processId ->
                terminationRequest = Triple(instanceId, processSlot, processId)
                EngineProcessTerminationResult(true, "TERMINATED", processId, "confirmed")
            }
        )

        val result = engine.deleteInstance(INSTANCE_ID)

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(Triple(INSTANCE_ID, PROCESS_SLOT, null), terminationRequest)
        assertNull(slotStore.get(INSTANCE_ID))
    }

    @Test
    fun `delete retains runtime and proxy slot assignments when instance record deletion fails`(@TempDir tempDir: File) {
        val instanceManager = mockk<InstanceManager>()
        val registry = EngineRuntimeRegistry()
        val proxySlotStore = FileBackedProxyActivitySlotAssignmentStore(File(tempDir, "proxy-slots.properties"))
        val proxySlotKey = ProxyActivitySlotKey(INSTANCE_ID, null, "task")
        proxySlotStore.save(proxySlotKey, PROXY_SLOT)
        val server = DefaultVirtualSystemServer(
            registry = registry,
            proxyActivitySlotAssignmentStore = proxySlotStore
        )
        val slotStore = InMemoryEngineRuntimeSlotStore().apply {
            assign(INSTANCE_ID, ORIGIN_PACKAGE, listOf(PROCESS_SLOT), listOf(PROXY_SLOT))
        }
        every { instanceManager.getInstance(INSTANCE_ID) } returns instance()
        every { instanceManager.deleteInstance(INSTANCE_ID) } returns false
        val engine = engine(instanceManager, registry, server, slotStore)

        val result = engine.deleteInstance(INSTANCE_ID)

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("instance_record_delete_failed", result.message)
        assertEquals(PROCESS_SLOT, slotStore.get(INSTANCE_ID)?.processSlot)
        assertEquals(PROXY_SLOT, proxySlotStore.find(proxySlotKey))
    }

    @Test
    fun `delete fails closed while a guest process is still authoritative`() {
        val instance = instance()
        val instanceManager = mockk<InstanceManager>()
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val slotStore = InMemoryEngineRuntimeSlotStore().apply {
            assign(INSTANCE_ID, ORIGIN_PACKAGE, listOf(PROCESS_SLOT), listOf(PROXY_SLOT))
        }
        registry.register(runtime(VirtualRuntimeState.RUNNING, processId = 4_321))
        every { instanceManager.getInstance(INSTANCE_ID) } returns instance
        val engine = engine(
            instanceManager,
            registry,
            server,
            slotStore,
            processTerminator = EngineProcessTerminator { _, _, processId ->
                EngineProcessTerminationResult(
                    confirmed = false,
                    status = "TEST_REJECTED",
                    processId = processId,
                    message = "termination not confirmed"
                )
            }
        )

        val result = engine.deleteInstance(INSTANCE_ID)

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals(
            "process_termination_unconfirmed:TEST_REJECTED:termination not confirmed",
            result.message
        )
        assertEquals(VirtualRuntimeState.RUNNING, registry.get(INSTANCE_ID)?.state)
        assertEquals(PROCESS_SLOT, slotStore.get(INSTANCE_ID)?.processSlot)
        verify(exactly = 0) { instanceManager.deleteInstance(any()) }
    }

    private fun engine(
        instanceManager: InstanceManager,
        registry: EngineRuntimeRegistry,
        server: VirtualSystemServer,
        slotStore: EngineRuntimeSlotStore,
        processTerminator: EngineProcessTerminator = EngineProcessTerminator.TEST_NO_OP
    ) = DefaultVirtualizationEngineCore(
        hostPackageName = HOST_PACKAGE,
        instanceManager = instanceManager,
        virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
        activityLauncher = EngineActivityLauncher { },
        processTerminator = processTerminator,
        slotStore = slotStore,
        runtimeRegistry = registry,
        systemServerFactory = { server }
    )

    private fun instance() = VirtualInstanceRecord(
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        displayName = "Test",
        dataRoot = "/data/user/0/$HOST_PACKAGE/files/instances/$INSTANCE_ID",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 100L,
        updatedAtMs = 100L,
        state = InstanceState.READY
    )

    private fun runtime(state: VirtualRuntimeState, processId: Int?) = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        dataRoot = "/data/user/0/$HOST_PACKAGE/files/instances/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "/data/app/test/base.apk",
            dataDir = "/data/user/0/$HOST_PACKAGE/files/instances/$INSTANCE_ID"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PROCESS_SLOT,
        proxySlot = PROXY_SLOT,
        evidenceSessionId = "evidence-session",
        runtimeEpoch = 1L,
        engineSessionId = "engine-session",
        processId = processId,
        processName = processId?.let { PROCESS_SLOT },
        state = state
    )

    private companion object {
        const val INSTANCE_ID = "instance-delete"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.instance.delete"
        const val PROCESS_SLOT = "com.multiapp.app:v2"
        const val PROXY_SLOT = "com.multiapp.app.container.ProxyActivity2"
    }
}

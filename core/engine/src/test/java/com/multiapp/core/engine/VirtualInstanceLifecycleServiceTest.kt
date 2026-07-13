package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualTaskRecord
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualInstanceLifecycleServiceTest {
    @Test
    fun `cleanup removes only the selected instance subsystem state`(@TempDir tempDir: File) {
        val taskStore = InMemoryEngineActivityTaskStateStore()
        val activityRecords = VirtualActivityRecordManager()
        val serviceStore = InMemoryEngineServiceRuntimeStateStore()
        val providerStore = InMemoryEngineProviderRuntimeStateStore()
        val uriGrantStore = InMemoryEngineProviderUriGrantStore()
        val permissionStore = InMemoryEnginePermissionGrantStore()
        val appOpsStore = InMemoryEngineAppOpsStateStore()
        val broadcastStore = InMemoryEngineBroadcastRuntimeStateStore()
        val proxySlotStore = FileBackedProxyActivitySlotAssignmentStore(File(tempDir, "proxy-slots.properties"))
        val targetProxySlot = ProxyActivitySlotKey(INSTANCE_ID, null, "target-task")
        val siblingProxySlot = ProxyActivitySlotKey(OTHER_INSTANCE_ID, null, "other-task")
        proxySlotStore.save(targetProxySlot, "com.multiapp.app.container.ProxyActivity0")
        proxySlotStore.save(siblingProxySlot, "com.multiapp.app.container.ProxyActivity1")
        val targetActivity = activity(INSTANCE_ID, "target-token", 1)
        val otherActivity = activity(OTHER_INSTANCE_ID, "other-token", 2)
        val tasks = listOf(
            VirtualTaskRecord(1, "target-task", listOf(targetActivity), 100L),
            VirtualTaskRecord(2, "other-task", listOf(otherActivity), 100L)
        )
        taskStore.save(EngineActivityTaskStateSnapshot(tasks))
        activityRecords.restoreTasks(tasks)
        serviceStore.upsert(
            EngineServiceRuntimeRecord(
                instanceId = INSTANCE_ID,
                serviceClassName = "com.test.SyncService",
                processSlot = PROCESS_SLOT,
                runtimeEpoch = 1L,
                state = EngineServiceLifecycleState.STARTED,
                updatedAtMs = 100L
            )
        )
        providerStore.upsert(
            EngineProviderRuntimeRecord(
                instanceId = INSTANCE_ID,
                guestAuthority = AUTHORITY,
                providerClassName = "com.test.DataProvider",
                processSlot = PROCESS_SLOT,
                runtimeEpoch = 1L,
                lastOperation = EngineProviderOperation.QUERY,
                updatedAtMs = 100L
            )
        )
        uriGrantStore.grant(
            EngineProviderUriGrantRecord(
                ownerInstanceId = INSTANCE_ID,
                targetInstanceId = OTHER_INSTANCE_ID,
                targetPackageName = "com.test.other",
                guestAuthority = AUTHORITY,
                encodedPath = "/items/1",
                modeFlags = 1,
                prefix = false,
                persistable = false,
                createdAtMs = 100L,
                updatedAtMs = 100L
            )
        )
        permissionStore.set(
            EnginePermissionGrantRecord(
                instanceId = INSTANCE_ID,
                permissionName = "android.permission.CAMERA",
                granted = true,
                source = EnginePermissionGrantSource.USER_DECISION,
                updatedAtMs = 100L
            )
        )
        appOpsStore.set(
            EngineAppOpModeRecord(
                instanceId = INSTANCE_ID,
                opCode = 26,
                mode = EngineAppOpModes.ALLOWED,
                updatedAtMs = 100L
            )
        )
        broadcastStore.upsert(
            EngineBroadcastRuntimeRecord(
                instanceId = INSTANCE_ID,
                receiverClassName = "com.test.EventReceiver",
                action = "com.test.ACTION",
                processSlot = PROCESS_SLOT,
                runtimeEpoch = 1L,
                state = EngineBroadcastDeliveryState.DELIVERED,
                lastVerdict = EngineResultStatus.PASS,
                lastReason = "delivered",
                deliveredCount = 1L,
                updatedAtMs = 100L
            )
        )
        val lifecycle = RegistryBackedVirtualInstanceLifecycleService(
            taskStore,
            activityRecords,
            serviceStore,
            providerStore,
            uriGrantStore,
            permissionStore,
            appOpsStore,
            broadcastStore,
            proxySlotStore::removeInstance
        )

        val result = lifecycle.clearInstanceState(INSTANCE_ID)

        assertEquals(8, result.totalRemoved)
        assertEquals(1, result.activityRecordCount)
        assertEquals(1, result.activityTaskRecordCount)
        assertTrue(serviceStore.list(INSTANCE_ID).isEmpty())
        assertTrue(providerStore.list(INSTANCE_ID).isEmpty())
        assertTrue(uriGrantStore.listForInstance(INSTANCE_ID).isEmpty())
        assertTrue(permissionStore.list(INSTANCE_ID).isEmpty())
        assertTrue(appOpsStore.list(INSTANCE_ID).isEmpty())
        assertTrue(broadcastStore.list(INSTANCE_ID).isEmpty())
        assertEquals(listOf(OTHER_INSTANCE_ID), activityRecords.list().map { it.instanceId })
        assertEquals(listOf(OTHER_INSTANCE_ID), taskStore.load().tasks.flatMap { it.activities }.map { it.instanceId })
        assertEquals("com.multiapp.app.container.ProxyActivity0", proxySlotStore.find(targetProxySlot))

        assertEquals(1, lifecycle.releaseInstanceSlots(INSTANCE_ID))
        assertEquals(null, proxySlotStore.find(targetProxySlot))
        assertEquals("com.multiapp.app.container.ProxyActivity1", proxySlotStore.find(siblingProxySlot))
    }

    private fun activity(instanceId: String, token: String, taskId: Int) = VirtualActivityRecord(
        token = token,
        instanceId = instanceId,
        originPackageName = "com.test.app",
        guestActivityClassName = "com.test.MainActivity",
        proxyActivityClassName = "com.multiapp.app.container.ProxyActivity$taskId",
        taskId = taskId,
        taskAffinity = "$instanceId.task"
    )

    private companion object {
        const val INSTANCE_ID = "instance-target"
        const val OTHER_INSTANCE_ID = "instance-other"
        const val PROCESS_SLOT = "com.multiapp.app:v1"
        const val AUTHORITY = "com.test.provider"
    }
}

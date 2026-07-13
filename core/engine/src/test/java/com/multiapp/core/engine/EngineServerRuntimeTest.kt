package com.multiapp.core.engine

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.virtual.InMemoryProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class EngineServerRuntimeTest {

    @Test
    fun `wrapper and owner share one engine runtime graph`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val systemServer = DefaultVirtualSystemServer(runtimeRegistry)
        val activityLaunchCapabilities = EngineActivityLaunchCapabilityRegistry()
        val processDeathRegistry = EngineProcessDeathRegistry()
        var processControlPlaneCreationCount = 0
        var controlPlaneRegistry: EngineRuntimeRegistry? = null
        var controlPlaneDeathRegistry: EngineProcessDeathRegistry? = null
        var controlPlaneCapabilities: EngineActivityLaunchCapabilityRegistry? = null
        var createdProcessControlPlane: EngineProcessControlPlane? = null

        val owner = EngineServerRuntime.createForTest(
            hostPackageName = "com.multiapp.app",
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = systemServer,
            activityLaunchCapabilities = activityLaunchCapabilities,
            processDeathRegistry = processDeathRegistry,
            processControlPlaneFactory = EngineProcessControlPlaneFactory { registry, deathRegistry, capabilities ->
                processControlPlaneCreationCount += 1
                controlPlaneRegistry = registry
                controlPlaneDeathRegistry = deathRegistry
                controlPlaneCapabilities = capabilities
                EngineProcessControlPlane(registry, deathRegistry, capabilities).also {
                    createdProcessControlPlane = it
                }
            }
        )
        val wrapper = DefaultVirtualizationEngine(owner)
        val engineCore = assertIs<DefaultVirtualizationEngineCore>(owner.virtualizationEngine)

        assertSame(owner.virtualizationEngine, wrapper.delegatedEngine)
        assertSame(runtimeRegistry, owner.runtimeRegistry)
        assertSame(systemServer, owner.systemServer)
        assertSame(activityLaunchCapabilities, owner.activityLaunchCapabilities)
        assertSame(processDeathRegistry, owner.processDeathRegistry)
        assertSame(runtimeRegistry, engineCore.runtimeRegistry)
        assertSame(systemServer, engineCore.systemServer)
        assertSame(activityLaunchCapabilities, engineCore.activityLaunchCapabilities)
        assertSame(processDeathRegistry, engineCore.processDeathRegistry)
        assertSame(runtimeRegistry, controlPlaneRegistry)
        assertSame(processDeathRegistry, controlPlaneDeathRegistry)
        assertSame(activityLaunchCapabilities, controlPlaneCapabilities)
        assertSame(createdProcessControlPlane, owner.processControlPlane)
        assertEquals(1, processControlPlaneCreationCount)
    }

    @Test
    fun `startup reconcile removes invalid instance and unknown proxy class but retains valid assignment`() {
        val hostPackageName = "com.multiapp.app"
        val validInstanceId = "instance-valid"
        val removedInstanceId = "instance-removed"
        val instanceManager = mockk<InstanceManager>(relaxed = true)
        every { instanceManager.listInstances() } returns listOf(instanceRecord(validInstanceId))
        val store = InMemoryProxyActivitySlotAssignmentStore()
        val validKey = ProxyActivitySlotKey(validInstanceId, null, "task-recents")
        val removedKey = ProxyActivitySlotKey(removedInstanceId, null, "task-removed")
        val unknownClassKey = ProxyActivitySlotKey(validInstanceId, null, "task-unknown-class")
        val validClass = "$hostPackageName.container.ProxyActivity0"
        store.save(validKey, validClass)
        store.save(removedKey, "$hostPackageName.container.ProxyActivity1")
        store.save(unknownClassKey, "$hostPackageName.container.UnknownProxyActivity")
        val runtimeRegistry = EngineRuntimeRegistry()
        val systemServer = DefaultVirtualSystemServer(
            registry = runtimeRegistry,
            proxyActivitySlotAssignmentStore = store
        )

        EngineServerRuntime.createForTest(
            hostPackageName = hostPackageName,
            instanceManager = instanceManager,
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = systemServer
        )

        assertEquals(validClass, store.find(validKey))
        assertEquals(null, store.find(removedKey))
        assertEquals(null, store.find(unknownClassKey))
    }

    private fun instanceRecord(instanceId: String) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        displayName = "Test",
        dataRoot = "build/tmp/$instanceId",
        compatibilityMode = CompatibilityMode.STANDARD,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        state = InstanceState.READY
    )
}

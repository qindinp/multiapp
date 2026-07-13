package com.multiapp.core.engine

import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.VirtualInstallService
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
}

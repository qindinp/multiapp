package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.VirtualInstallService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineServerRuntime private constructor(
    graph: EngineServerRuntimeGraph
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        instanceManager: InstanceManager,
        virtualInstallService: VirtualInstallService,
        activityLauncher: EngineActivityLauncher,
        processBootstrapper: EngineProcessBootstrapper,
        slotStore: EngineRuntimeSlotStore,
        hookRuntime: DefaultEngineHookRuntime
    ) : this(
        createEngineServerRuntimeGraph(
            hostPackageName = context.packageName,
            instanceManager = instanceManager,
            virtualInstallService = virtualInstallService,
            activityLauncher = activityLauncher,
            processBootstrapper = processBootstrapper,
            slotStore = slotStore,
            hookRuntime = hookRuntime,
            permissionGrantSeeder = SourcePackagePermissionGrantSeeder(context.packageManager),
            systemServerHandle = EngineRuntimeInstallers.fileBackedSystemServer(context),
            activityLaunchCapabilities = EngineActivityLaunchCapabilityRegistry.global,
            processDeathRegistry = EngineProcessDeathRegistry()
        )
    )

    val runtimeRegistry: EngineRuntimeRegistry = graph.runtimeRegistry
    val systemServer: VirtualSystemServer = graph.systemServer
    val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
        graph.activityLaunchCapabilities
    val processDeathRegistry: EngineProcessDeathRegistry = graph.processDeathRegistry
    val processControlPlane: EngineProcessControlPlane = graph.processControlPlane
    val virtualizationEngine: VirtualizationEngine = graph.virtualizationEngine

    companion object {
        internal fun createForTest(
            hostPackageName: String,
            instanceManager: InstanceManager,
            virtualInstallService: VirtualInstallService,
            activityLauncher: EngineActivityLauncher,
            processBootstrapper: EngineProcessBootstrapper = EngineProcessBootstrapper.IMMEDIATE,
            slotStore: EngineRuntimeSlotStore = InMemoryEngineRuntimeSlotStore(),
            hookRuntime: EngineHookRuntime = EngineHookRuntime.NO_OP,
            permissionGrantSeeder: EnginePermissionGrantSeeder = EnginePermissionGrantSeeder.NO_OP,
            runtimeRegistry: EngineRuntimeRegistry = EngineRuntimeRegistry(),
            systemServer: VirtualSystemServer = DefaultVirtualSystemServer(runtimeRegistry),
            activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
                EngineActivityLaunchCapabilityRegistry(),
            processDeathRegistry: EngineProcessDeathRegistry = EngineProcessDeathRegistry(),
            processControlPlaneFactory: EngineProcessControlPlaneFactory =
                EngineProcessControlPlaneFactory { registry, deathRegistry, capabilities ->
                    EngineProcessControlPlane(registry, deathRegistry, capabilities)
                }
        ): EngineServerRuntime = EngineServerRuntime(
            createEngineServerRuntimeGraph(
                hostPackageName = hostPackageName,
                instanceManager = instanceManager,
                virtualInstallService = virtualInstallService,
                activityLauncher = activityLauncher,
                processBootstrapper = processBootstrapper,
                slotStore = slotStore,
                hookRuntime = hookRuntime,
                permissionGrantSeeder = permissionGrantSeeder,
                systemServerHandle = EngineSystemServerHandle(runtimeRegistry, systemServer),
                activityLaunchCapabilities = activityLaunchCapabilities,
                processDeathRegistry = processDeathRegistry,
                processControlPlaneFactory = processControlPlaneFactory
            )
        )
    }
}

internal fun interface EngineProcessControlPlaneFactory {
    fun create(
        runtimeRegistry: EngineRuntimeRegistry,
        processDeathRegistry: EngineProcessDeathRegistry,
        activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry
    ): EngineProcessControlPlane
}

private class EngineServerRuntimeGraph(
    val runtimeRegistry: EngineRuntimeRegistry,
    val systemServer: VirtualSystemServer,
    val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry,
    val processDeathRegistry: EngineProcessDeathRegistry,
    val processControlPlane: EngineProcessControlPlane,
    val virtualizationEngine: DefaultVirtualizationEngineCore
)

private fun createEngineServerRuntimeGraph(
    hostPackageName: String,
    instanceManager: InstanceManager,
    virtualInstallService: VirtualInstallService,
    activityLauncher: EngineActivityLauncher,
    processBootstrapper: EngineProcessBootstrapper,
    slotStore: EngineRuntimeSlotStore,
    hookRuntime: EngineHookRuntime,
    permissionGrantSeeder: EnginePermissionGrantSeeder,
    systemServerHandle: EngineSystemServerHandle,
    activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry,
    processDeathRegistry: EngineProcessDeathRegistry,
    processControlPlaneFactory: EngineProcessControlPlaneFactory =
        EngineProcessControlPlaneFactory { registry, deathRegistry, capabilities ->
            EngineProcessControlPlane(registry, deathRegistry, capabilities)
        }
): EngineServerRuntimeGraph {
    val runtimeRegistry = systemServerHandle.registry
    val systemServer = systemServerHandle.server
    val processControlPlane = processControlPlaneFactory.create(
        runtimeRegistry,
        processDeathRegistry,
        activityLaunchCapabilities
    )
    val virtualizationEngine = DefaultVirtualizationEngineCore(
        hostPackageName = hostPackageName,
        instanceManager = instanceManager,
        virtualInstallService = virtualInstallService,
        activityLauncher = activityLauncher,
        processBootstrapper = processBootstrapper,
        slotStore = slotStore,
        runtimeRegistry = runtimeRegistry,
        activityLaunchCapabilities = activityLaunchCapabilities,
        processDeathRegistry = processDeathRegistry,
        profilePolicy = CompatibilityProfilePolicy(),
        hookRuntime = hookRuntime,
        permissionGrantSeeder = permissionGrantSeeder,
        evidenceSessionFactory = { UUID.randomUUID().toString() },
        systemServerFactory = { requestedRegistry ->
            check(requestedRegistry === runtimeRegistry) {
                "VirtualSystemServer must use the owner EngineRuntimeRegistry"
            }
            systemServer
        }
    )
    return EngineServerRuntimeGraph(
        runtimeRegistry = runtimeRegistry,
        systemServer = systemServer,
        activityLaunchCapabilities = activityLaunchCapabilities,
        processDeathRegistry = processDeathRegistry,
        processControlPlane = processControlPlane,
        virtualizationEngine = virtualizationEngine
    )
}

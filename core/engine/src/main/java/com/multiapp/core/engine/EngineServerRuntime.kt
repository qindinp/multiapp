package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.model.engine.VirtualRuntimeState
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
    val serverGenerationId: String = UUID.randomUUID().toString()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        instanceManager: InstanceManager,
        virtualInstallService: VirtualInstallService,
        activityLauncher: EngineActivityLauncher,
        processBootstrapper: EngineProcessBootstrapper,
        processTerminator: AndroidEngineProcessTerminator,
        slotStore: EngineRuntimeSlotStore,
        hookRuntime: DefaultEngineHookRuntime
    ) : this(
        createEngineServerRuntimeGraph(
            hostPackageName = context.packageName,
            instanceManager = instanceManager,
            virtualInstallService = virtualInstallService,
            activityLauncher = activityLauncher,
            processBootstrapper = processBootstrapper,
            processTerminator = processTerminator,
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
    val serviceOperationLeases: EngineServiceOperationLeaseCoordinator = graph.serviceOperationLeases
    val activityOperationTransactions: EngineActivityOperationTransactionCoordinator =
        graph.activityOperationTransactions
    val providerProcessEndpoints: EngineProviderProcessEndpointControlPlane =
        graph.providerProcessEndpoints
    val processControlPlane: EngineProcessControlPlane = graph.processControlPlane
    val virtualizationEngine: VirtualizationEngine = graph.virtualizationEngine

    companion object {
        internal fun createForTest(
            hostPackageName: String,
            instanceManager: InstanceManager,
            virtualInstallService: VirtualInstallService,
            activityLauncher: EngineActivityLauncher,
            processBootstrapper: EngineProcessBootstrapper = EngineProcessBootstrapper.IMMEDIATE,
            processTerminator: EngineProcessTerminator = EngineProcessTerminator.TEST_NO_OP,
            slotStore: EngineRuntimeSlotStore = InMemoryEngineRuntimeSlotStore(),
            hookRuntime: EngineHookRuntime = EngineHookRuntime.NO_OP,
            permissionGrantSeeder: EnginePermissionGrantSeeder = EnginePermissionGrantSeeder.NO_OP,
            runtimeRegistry: EngineRuntimeRegistry = EngineRuntimeRegistry(),
            systemServer: VirtualSystemServer = DefaultVirtualSystemServer(runtimeRegistry),
            activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
                EngineActivityLaunchCapabilityRegistry(),
            processDeathRegistry: EngineProcessDeathRegistry = EngineProcessDeathRegistry(),
            processControlPlaneFactory: EngineProcessControlPlaneFactory =
                EngineProcessControlPlaneFactory { registry, deathRegistry, capabilities, cleanup ->
                    EngineProcessControlPlane(registry, deathRegistry, capabilities, cleanup)
                }
        ): EngineServerRuntime = EngineServerRuntime(
            createEngineServerRuntimeGraph(
                hostPackageName = hostPackageName,
                instanceManager = instanceManager,
                virtualInstallService = virtualInstallService,
                activityLauncher = activityLauncher,
                processBootstrapper = processBootstrapper,
                processTerminator = processTerminator,
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
        activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry,
        generationCleanup: (EngineProcessClientIdentity) -> Unit
    ): EngineProcessControlPlane
}

private class EngineServerRuntimeGraph(
    val runtimeRegistry: EngineRuntimeRegistry,
    val systemServer: VirtualSystemServer,
    val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry,
    val processDeathRegistry: EngineProcessDeathRegistry,
    val serviceOperationLeases: EngineServiceOperationLeaseCoordinator,
    val activityOperationTransactions: EngineActivityOperationTransactionCoordinator,
    val providerProcessEndpoints: EngineProviderProcessEndpointControlPlane,
    val processControlPlane: EngineProcessControlPlane,
    val virtualizationEngine: DefaultVirtualizationEngineCore
)

private fun createEngineServerRuntimeGraph(
    hostPackageName: String,
    instanceManager: InstanceManager,
    virtualInstallService: VirtualInstallService,
    activityLauncher: EngineActivityLauncher,
    processBootstrapper: EngineProcessBootstrapper,
    processTerminator: EngineProcessTerminator,
    slotStore: EngineRuntimeSlotStore,
    hookRuntime: EngineHookRuntime,
    permissionGrantSeeder: EnginePermissionGrantSeeder,
    systemServerHandle: EngineSystemServerHandle,
    activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry,
    processDeathRegistry: EngineProcessDeathRegistry,
    processControlPlaneFactory: EngineProcessControlPlaneFactory =
        EngineProcessControlPlaneFactory { registry, deathRegistry, capabilities, generationCleanup ->
            EngineProcessControlPlane(
                registry,
                deathRegistry,
                capabilities,
                generationCleanup
            )
        }
): EngineServerRuntimeGraph {
    val runtimeRegistry = systemServerHandle.registry
    val systemServer = systemServerHandle.server
    val serviceOperationLeases = EngineServiceOperationLeaseCoordinator(runtimeRegistry)
    val activityOperationTransactions = EngineActivityOperationTransactionCoordinator()
    val providerEndpointRegistry = EngineProviderProcessEndpointRegistry()
    val providerProcessEndpoints = EngineProviderProcessEndpointControlPlane(
        runtimeAuthority = providerEndpointRuntimeAuthority(runtimeRegistry),
        registry = providerEndpointRegistry
    )
    if (systemServer is DefaultVirtualSystemServer) {
        val validInstanceIds = instanceManager.listInstances()
            .mapTo(linkedSetOf()) { instance -> instance.instanceId }
        systemServer.reconcileProxyActivitySlots(
            validInstanceIds = validInstanceIds,
            knownProxyActivityClassNames = EngineProxyActivitySlots.classNames(hostPackageName).toSet()
        )
        systemServer.reconcilePackageEnabledStates(validInstanceIds)
    }
    val generationCleanup: (EngineProcessClientIdentity) -> Unit = { identity ->
        serviceOperationLeases.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
        providerProcessEndpoints.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
        activityOperationTransactions.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
    }
    val processControlPlane = processControlPlaneFactory.create(
        runtimeRegistry,
        processDeathRegistry,
        activityLaunchCapabilities,
        generationCleanup
    )
    val virtualizationEngine = DefaultVirtualizationEngineCore(
        hostPackageName = hostPackageName,
        instanceManager = instanceManager,
        virtualInstallService = virtualInstallService,
        activityLauncher = activityLauncher,
        processBootstrapper = processBootstrapper,
        processTerminator = processTerminator,
        slotStore = slotStore,
        runtimeRegistry = runtimeRegistry,
        activityLaunchCapabilities = activityLaunchCapabilities,
        processDeathRegistry = processDeathRegistry,
        profilePolicy = CompatibilityProfilePolicy(),
        hookRuntime = hookRuntime,
        permissionGrantSeeder = permissionGrantSeeder,
        ephemeralInstanceCleanup = { instanceId ->
            serviceOperationLeases.revokeInstance(instanceId)
            providerProcessEndpoints.revokeInstance(instanceId)
            activityOperationTransactions.revokeInstance(instanceId)
        },
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
        serviceOperationLeases = serviceOperationLeases,
        activityOperationTransactions = activityOperationTransactions,
        providerProcessEndpoints = providerProcessEndpoints,
        processControlPlane = processControlPlane,
        virtualizationEngine = virtualizationEngine
    )
}

private fun providerEndpointRuntimeAuthority(
    runtimeRegistry: EngineRuntimeRegistry
): EngineProviderProcessEndpointRuntimeAuthority = EngineProviderProcessEndpointRuntimeAuthority { candidate ->
    val runtime = runtimeRegistry.get(candidate.instanceId)
        ?: return@EngineProviderProcessEndpointRuntimeAuthority EngineProviderProcessEndpointAuthorityDecision(
            allowed = false,
            expectedIdentity = null,
            reason = "provider_endpoint_runtime_not_found"
        )
    if (runtime.state !in LIVE_PROVIDER_ENDPOINT_RUNTIME_STATES) {
        return@EngineProviderProcessEndpointRuntimeAuthority EngineProviderProcessEndpointAuthorityDecision(
            allowed = false,
            expectedIdentity = null,
            reason = "provider_endpoint_runtime_not_live"
        )
    }
    if (runtime.processName != runtime.processSlot) {
        return@EngineProviderProcessEndpointRuntimeAuthority EngineProviderProcessEndpointAuthorityDecision(
            allowed = false,
            expectedIdentity = null,
            reason = "provider_endpoint_runtime_process_slot_mismatch"
        )
    }
    val provider = runtime.packageSnapshot.providers.singleOrNull { component ->
        candidate.guestAuthority in component.authorities
    } ?: return@EngineProviderProcessEndpointRuntimeAuthority EngineProviderProcessEndpointAuthorityDecision(
        allowed = false,
        expectedIdentity = null,
        reason = "provider_endpoint_component_not_found"
    )
    val applicationProcessName = runtime.packageSnapshot.processName
        ?.toEffectiveGuestProcessName(runtime.originPackageName)
        ?: runtime.originPackageName
    val providerProcessName = provider.processName
        ?.toEffectiveGuestProcessName(runtime.originPackageName)
        ?: applicationProcessName
    if (providerProcessName != applicationProcessName) {
        return@EngineProviderProcessEndpointRuntimeAuthority EngineProviderProcessEndpointAuthorityDecision(
            allowed = false,
            expectedIdentity = null,
            reason = "provider_component_process_slot_not_allocated"
        )
    }
    val processId = runtime.processId
        ?: return@EngineProviderProcessEndpointRuntimeAuthority EngineProviderProcessEndpointAuthorityDecision(
            allowed = false,
            expectedIdentity = null,
            reason = "provider_endpoint_runtime_process_not_bound"
        )
    val expected = EngineProviderProcessEndpointIdentity(
        instanceId = runtime.instanceId,
        guestAuthority = candidate.guestAuthority,
        providerClassName = provider.name,
        declaredProcessName = provider.processName,
        effectiveProcessName = providerProcessName,
        processSlot = runtime.processSlot,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processId = processId
    )
    EngineProviderProcessEndpointAuthorityDecision(
        allowed = candidate == expected,
        expectedIdentity = expected.takeIf { candidate == expected },
        reason = if (candidate == expected) {
            "provider_endpoint_runtime_authority_confirmed"
        } else {
            "provider_endpoint_runtime_identity_mismatch"
        }
    )
}

private fun String.toEffectiveGuestProcessName(originPackageName: String): String =
    if (startsWith(':')) originPackageName + this else this

private val LIVE_PROVIDER_ENDPOINT_RUNTIME_STATES = setOf(
    VirtualRuntimeState.CREATED,
    VirtualRuntimeState.PREWARMED,
    VirtualRuntimeState.RUNNING
)

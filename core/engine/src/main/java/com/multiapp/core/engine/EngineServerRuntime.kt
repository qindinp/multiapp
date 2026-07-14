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
) : EngineComponentProcessAuthority {
    private val componentProcessAttachLock = Any()
    private val componentProcessIdentityProbe = graph.componentProcessIdentityProbe
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
    val serviceConnections: EngineServiceConnectionRegistry = graph.serviceConnections
    val activityOperationTransactions: EngineActivityOperationTransactionCoordinator =
        graph.activityOperationTransactions
    val providerProcessEndpoints: EngineProviderProcessEndpointControlPlane =
        graph.providerProcessEndpoints
    val componentProcessSlots: EngineComponentProcessSlotAllocator = graph.componentProcessSlots
    val componentProcessClients: EngineComponentProcessClientRegistry = graph.componentProcessClients
    val componentProcessLaunchCapabilities: EngineComponentProcessLaunchCapabilityRegistry =
        graph.componentProcessLaunchCapabilities
    val processControlPlane: EngineProcessControlPlane = graph.processControlPlane
    val virtualizationEngine: VirtualizationEngine = graph.virtualizationEngine

    fun allocateComponentProcessSlot(
        instanceId: String,
        guestProcessName: String
    ): EngineComponentProcessSlotAssignment? {
        val runtime = runtimeRegistry.get(instanceId) ?: return null
        if (runtime.state == VirtualRuntimeState.STOPPED || runtime.state == VirtualRuntimeState.DEAD) {
            return null
        }
        val effectiveGuestProcessName = guestProcessName
            .takeIf { it.isNotBlank() }
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: return null
        if (effectiveGuestProcessName !in runtime.declaredGuestProcessNames()) return null
        reservePrimaryComponentProcessSlots(
            hostPackageName = runtime.hostPackageName,
            runtimeRegistry = runtimeRegistry,
            allocator = componentProcessSlots
        )
        val applicationGuestProcessName = runtime.packageSnapshot.processName
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: runtime.originPackageName
        val declaredProcessSlots = declaredComponentProcessSlots(runtime.hostPackageName)
        return componentProcessSlots.allocate(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            guestProcessName = effectiveGuestProcessName,
            applicationGuestProcessName = applicationGuestProcessName,
            primaryProcessSlot = runtime.processSlot,
            declaredCandidateSlots = declaredProcessSlots.filterNot { it == runtime.processSlot }
        )
    }

    internal fun attachComponentProcessClient(
        identity: EngineComponentProcessClientIdentity,
        clientToken: android.os.IBinder,
        callingPid: Int,
        callingProcessName: String?,
        callingProcessStartTicks: Long? = identity.processStartTicks
    ): EngineComponentProcessClientAttachResult {
        validateComponentProcessClientIdentity(
            identity,
            callingPid,
            callingProcessName,
            callingProcessStartTicks
        )?.let { reason ->
            return componentProcessAttachRejected(identity, reason)
        }
        return componentProcessClients.attach(identity, clientToken) { deadIdentity ->
            serviceConnections.revokeProcess(
                instanceId = deadIdentity.instanceId,
                runtimeEpoch = deadIdentity.runtimeEpoch,
                engineSessionId = deadIdentity.engineSessionId,
                processSlot = deadIdentity.processSlot,
                processId = deadIdentity.processId
            )
        }
    }

    override fun prepare(
        instanceId: String,
        guestProcessName: String
    ): EngineComponentProcessOperationResult = synchronized(componentProcessAttachLock) {
        // Android starts the selected StubService in the target slot. That process binds the
        // guest runtime and consumes this ticket, so the caller never blocks on cold bootstrap.
        prepareComponentProcessLaunchLocked(instanceId, guestProcessName)
    }

    private fun prepareComponentProcessLaunchLocked(
        instanceId: String,
        guestProcessName: String
    ): EngineComponentProcessOperationResult {
        val runtime = runtimeRegistry.get(instanceId)
            ?: return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_runtime_not_found"
            )
        val effectiveGuestProcessName = guestProcessName
            .takeIf { it.isNotBlank() }
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_name_invalid"
            )
        val applicationGuestProcessName = runtime.packageSnapshot.processName
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: runtime.originPackageName
        if (effectiveGuestProcessName == applicationGuestProcessName) {
            return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_must_be_custom"
            )
        }
        val live = componentProcessClients.queryByKey(instanceId, effectiveGuestProcessName)
        if (
            live.found && live.identity != null && live.clientToken != null &&
            isComponentProcessIdentityAuthoritative(live.identity, live.clientToken)
        ) {
            return componentProcessRunning(
                identity = live.identity,
                idempotent = true,
                reason = "component_process_already_running"
            )
        }
        val assignment = runCatching {
            allocateComponentProcessSlot(instanceId, effectiveGuestProcessName)
        }.getOrElse { error ->
            return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                if (error is EngineComponentProcessSlotExhaustedException) {
                    "component_process_slot_exhausted"
                } else {
                    "component_process_slot_allocation_failed"
                }
            )
        } ?: return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_not_declared"
            )
        val issued = runCatching {
            componentProcessLaunchCapabilities.issue(assignment)
        }.getOrElse {
            return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_launch_capability_issue_failed"
            )
        }
        if (!issued.accepted || issued.identity == null) {
            return componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                issued.reason
            )
        }
        val ticket = issued.identity.toLaunchTicket()
        return EngineComponentProcessOperationResult(
            operation = COMPONENT_PROCESS_PREPARE_OPERATION,
            instanceId = instanceId,
            accepted = true,
            idempotent = issued.idempotent,
            alreadyRunning = false,
            launchTicket = ticket,
            processState = null,
            reason = issued.reason
        )
    }

    override fun attach(
        attachCapability: String,
        clientToken: android.os.IBinder,
        callingPid: Int,
        callingProcessName: String?,
        callingProcessStartTicks: Long?
    ): EngineComponentProcessOperationResult = synchronized(componentProcessAttachLock) {
        val launch = componentProcessLaunchCapabilities.query(attachCapability)
        val launchIdentity = launch.identity
            ?: return@synchronized componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                UNKNOWN_COMPONENT_PROCESS_INSTANCE,
                launch.reason
            )
        val startTicks = callingProcessStartTicks
            ?.takeIf { ticks -> ticks > 0L }
            ?: return@synchronized componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                launchIdentity.instanceId,
                "component_process_start_ticks_unavailable"
            )
        val clientIdentity = runCatching {
            launchIdentity.toClientIdentity(callingPid, startTicks)
        }
            .getOrElse {
                return@synchronized componentProcessRejected(
                    COMPONENT_PROCESS_ATTACH_OPERATION,
                    launchIdentity.instanceId,
                    "component_process_client_identity_invalid"
                )
            }
        if (componentProcessClients.isAuthoritative(clientIdentity, clientToken)) {
            return@synchronized componentProcessAttached(clientIdentity, idempotent = true)
        }
        validateComponentProcessClientIdentity(
            clientIdentity,
            callingPid,
            callingProcessName,
            startTicks
        )?.let { reason ->
            return@synchronized componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                launchIdentity.instanceId,
                reason
            )
        }
        val capability = componentProcessLaunchCapabilities.consume(attachCapability)
        if (!capability.accepted) {
            return@synchronized componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                launchIdentity.instanceId,
                capability.reason
            )
        }
        val attached = attachComponentProcessClient(
            clientIdentity,
            clientToken,
            callingPid,
            callingProcessName,
            startTicks
        )
        if (attached.accepted && attached.identity != null) {
            componentProcessAttached(attached.identity, attached.idempotent)
        } else {
            componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                launchIdentity.instanceId,
                attached.reason
            )
        }
    }

    override fun query(
        instanceId: String,
        guestProcessName: String
    ): EngineComponentProcessOperationResult {
        val runtime = runtimeRegistry.get(instanceId)
            ?: return componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId,
                "component_process_runtime_not_found"
            )
        val effectiveGuestProcessName = guestProcessName
            .takeIf { it.isNotBlank() }
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: return componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId,
                "component_process_name_invalid"
            )
        if (effectiveGuestProcessName !in runtime.declaredGuestProcessNames()) {
            return componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId,
                "component_process_not_declared"
            )
        }
        val queried = componentProcessClients.queryByKey(instanceId, effectiveGuestProcessName)
        return if (
            queried.found && queried.identity != null && queried.clientToken != null &&
            isComponentProcessIdentityAuthoritative(queried.identity, queried.clientToken)
        ) {
            EngineComponentProcessOperationResult(
                operation = COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId = instanceId,
                accepted = true,
                idempotent = false,
                alreadyRunning = true,
                launchTicket = null,
                processState = queried.identity.toPublicComponentProcessState(),
                reason = queried.reason
            )
        } else {
            componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId,
                queried.reason
            )
        }
    }

    private fun validateComponentProcessClientIdentity(
        identity: EngineComponentProcessClientIdentity,
        callingPid: Int,
        callingProcessName: String?,
        callingProcessStartTicks: Long?
    ): String? {
        val runtime = runtimeRegistry.get(identity.instanceId)
            ?: return "component_process_runtime_not_found"
        if (runtime.state == VirtualRuntimeState.STOPPED || runtime.state == VirtualRuntimeState.DEAD) {
            return "component_process_runtime_not_live"
        }
        if (
            runtime.runtimeEpoch != identity.runtimeEpoch ||
            runtime.engineSessionId != identity.engineSessionId
        ) {
            return "component_process_runtime_generation_mismatch"
        }
        if (identity.effectiveGuestProcessName !in runtime.declaredGuestProcessNames()) {
            return "component_process_name_not_declared"
        }
        val applicationGuestProcessName = runtime.packageSnapshot.processName
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: runtime.originPackageName
        if (identity.effectiveGuestProcessName == applicationGuestProcessName) {
            return "component_process_must_be_custom"
        }
        val assignment = componentProcessSlots.query(
            instanceId = identity.instanceId,
            runtimeEpoch = identity.runtimeEpoch,
            engineSessionId = identity.engineSessionId,
            guestProcessName = identity.effectiveGuestProcessName
        ) ?: return "component_process_slot_not_allocated"
        if (assignment.processSlot != identity.processSlot) return "component_process_slot_mismatch"
        if (identity.processId != callingPid) return "component_process_pid_mismatch"
        if (callingProcessName != identity.processSlot) return "component_process_android_name_mismatch"
        if (callingProcessStartTicks != identity.processStartTicks) {
            return "component_process_start_ticks_mismatch"
        }
        return null
    }

    override fun authorizeCaller(
        instanceId: String,
        callingPid: Int,
        callingProcessName: String?,
        callingProcessStartTicks: Long?
    ): EngineComponentProcessClientIdentity? {
        val queried = componentProcessClients.queryByPid(callingPid)
        val identity = queried.identity ?: return null
        val token = queried.clientToken ?: return null
        if (identity.instanceId != instanceId) return null
        if (
            validateComponentProcessClientIdentity(
                identity,
                callingPid,
                callingProcessName,
                callingProcessStartTicks
            ) != null
        ) {
            return null
        }
        return identity.takeIf { componentProcessClients.isAuthoritative(identity, token) }
    }

    private fun isComponentProcessIdentityAuthoritative(
        identity: EngineComponentProcessClientIdentity,
        clientToken: android.os.IBinder
    ): Boolean {
        val observed = componentProcessIdentityProbe.read(identity.processId) ?: return false
        return validateComponentProcessClientIdentity(
            identity = identity,
            callingPid = identity.processId,
            callingProcessName = observed.processName,
            callingProcessStartTicks = observed.processStartTicks
        ) == null && componentProcessClients.isAuthoritative(identity, clientToken)
    }

    companion object {
        internal fun createForTest(
            hostPackageName: String,
            instanceManager: InstanceManager,
            virtualInstallService: VirtualInstallService,
            activityLauncher: EngineActivityLauncher,
            processBootstrapper: EngineProcessBootstrapper =
                EngineProcessBootstrapper.PRIMARY_IMMEDIATE_COMPONENT_DEFERRED,
            processTerminator: EngineProcessTerminator = EngineProcessTerminator.TEST_NO_OP,
            slotStore: EngineRuntimeSlotStore = InMemoryEngineRuntimeSlotStore(),
            hookRuntime: EngineHookRuntime = EngineHookRuntime.NO_OP,
            permissionGrantSeeder: EnginePermissionGrantSeeder = EnginePermissionGrantSeeder.NO_OP,
            runtimeRegistry: EngineRuntimeRegistry = EngineRuntimeRegistry(),
            systemServer: VirtualSystemServer = DefaultVirtualSystemServer(runtimeRegistry),
            activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
                EngineActivityLaunchCapabilityRegistry(),
            processDeathRegistry: EngineProcessDeathRegistry = EngineProcessDeathRegistry(),
            componentProcessIdentityProbe: EngineComponentProcessIdentityProbe =
                EngineComponentProcessIdentityProbe { null },
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
                componentProcessIdentityProbe = componentProcessIdentityProbe,
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
    val serviceConnections: EngineServiceConnectionRegistry,
    val activityOperationTransactions: EngineActivityOperationTransactionCoordinator,
    val providerProcessEndpoints: EngineProviderProcessEndpointControlPlane,
    val componentProcessSlots: EngineComponentProcessSlotAllocator,
    val componentProcessClients: EngineComponentProcessClientRegistry,
    val componentProcessLaunchCapabilities: EngineComponentProcessLaunchCapabilityRegistry,
    val componentProcessIdentityProbe: EngineComponentProcessIdentityProbe,
    val processBootstrapper: EngineProcessBootstrapper,
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
    componentProcessIdentityProbe: EngineComponentProcessIdentityProbe =
        EngineComponentProcessIdentityProbe.PROCFS,
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
    val serviceConnections = EngineServiceConnectionRegistry()
    val activityOperationTransactions = EngineActivityOperationTransactionCoordinator()
    val componentProcessSlots = EngineComponentProcessSlotAllocator()
    val componentProcessClients = EngineComponentProcessClientRegistry(componentProcessIdentityProbe)
    val componentProcessLaunchCapabilities = EngineComponentProcessLaunchCapabilityRegistry()
    reservePrimaryComponentProcessSlots(
        hostPackageName = hostPackageName,
        runtimeRegistry = runtimeRegistry,
        allocator = componentProcessSlots
    )
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
        serviceConnections.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
        providerProcessEndpoints.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
        componentProcessSlots.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
        componentProcessClients.revokeGeneration(
            identity.instanceId,
            identity.runtimeEpoch,
            identity.engineSessionId
        )
        componentProcessLaunchCapabilities.revokeGeneration(
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
            serviceConnections.revokeInstance(instanceId)
            providerProcessEndpoints.revokeInstance(instanceId)
            componentProcessSlots.revokeInstance(instanceId)
            componentProcessClients.revokeInstance(instanceId)
            componentProcessLaunchCapabilities.revokeInstance(instanceId)
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
        serviceConnections = serviceConnections,
        activityOperationTransactions = activityOperationTransactions,
        providerProcessEndpoints = providerProcessEndpoints,
        componentProcessSlots = componentProcessSlots,
        componentProcessClients = componentProcessClients,
        componentProcessLaunchCapabilities = componentProcessLaunchCapabilities,
        componentProcessIdentityProbe = componentProcessIdentityProbe,
        processBootstrapper = processBootstrapper,
        processControlPlane = processControlPlane,
        virtualizationEngine = virtualizationEngine
    )
}

private fun componentProcessAttachRejected(
    identity: EngineComponentProcessClientIdentity,
    reason: String
) = EngineComponentProcessClientAttachResult(
    accepted = false,
    idempotent = false,
    replacedGeneration = false,
    identity = identity,
    reason = reason
)

private fun componentProcessRejected(
    operation: String,
    instanceId: String,
    reason: String
) = EngineComponentProcessOperationResult(
    operation = operation,
    instanceId = instanceId,
    accepted = false,
    idempotent = false,
    alreadyRunning = false,
    launchTicket = null,
    processState = null,
    reason = reason
)

private fun componentProcessAttached(
    identity: EngineComponentProcessClientIdentity,
    idempotent: Boolean
) = EngineComponentProcessOperationResult(
    operation = COMPONENT_PROCESS_ATTACH_OPERATION,
    instanceId = identity.instanceId,
    accepted = true,
    idempotent = idempotent,
    alreadyRunning = false,
    launchTicket = null,
    processState = identity.toPublicComponentProcessState(),
    reason = if (idempotent) {
        "component_process_client_already_attached"
    } else {
        "component_process_client_attached"
    }
)

private fun componentProcessRunning(
    identity: EngineComponentProcessClientIdentity,
    idempotent: Boolean,
    reason: String
) = EngineComponentProcessOperationResult(
    operation = COMPONENT_PROCESS_PREPARE_OPERATION,
    instanceId = identity.instanceId,
    accepted = true,
    idempotent = idempotent,
    alreadyRunning = true,
    launchTicket = null,
    processState = identity.toPublicComponentProcessState(),
    reason = reason
)

private fun EngineComponentProcessLaunchIdentity.toLaunchTicket() =
    EngineComponentProcessLaunchTicket(
        instanceId = instanceId,
        effectiveGuestProcessName = effectiveGuestProcessName,
        processSlot = processSlot,
        attachCapability = attachCapability
    )

private fun reservePrimaryComponentProcessSlots(
    hostPackageName: String,
    runtimeRegistry: EngineRuntimeRegistry,
    allocator: EngineComponentProcessSlotAllocator
) {
    val declaredProcessSlots = declaredComponentProcessSlots(hostPackageName)
    runtimeRegistry.list().forEach { runtime ->
        if (runtime.state == VirtualRuntimeState.STOPPED || runtime.state == VirtualRuntimeState.DEAD) {
            return@forEach
        }
        if (runtime.processSlot !in declaredProcessSlots) return@forEach
        val applicationGuestProcessName = runtime.packageSnapshot.processName
            ?.toEffectiveGuestProcessName(runtime.originPackageName)
            ?: runtime.originPackageName
        allocator.allocate(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            guestProcessName = applicationGuestProcessName,
            applicationGuestProcessName = applicationGuestProcessName,
            primaryProcessSlot = runtime.processSlot,
            declaredCandidateSlots = declaredProcessSlots.filterNot { it == runtime.processSlot }
        )
    }
}

private fun declaredComponentProcessSlots(hostPackageName: String): List<String> =
    EngineProxyActivitySlots.classNames(hostPackageName)
        .mapNotNull { className ->
            EngineProxyActivitySlots.processSlotForClassName(hostPackageName, className)
        }
        .distinct()

private fun com.multiapp.core.model.engine.VirtualInstanceRuntime.declaredGuestProcessNames(): Set<String> {
    val applicationGuestProcessName = packageSnapshot.processName
        ?.toEffectiveGuestProcessName(originPackageName)
        ?: originPackageName
    return buildSet {
        add(applicationGuestProcessName)
        sequenceOf(
            packageSnapshot.activities,
            packageSnapshot.services,
            packageSnapshot.providers,
            packageSnapshot.receivers
        ).flatten().forEach { component ->
            add(
                component.processName
                    ?.toEffectiveGuestProcessName(originPackageName)
                    ?: applicationGuestProcessName
            )
        }
    }
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

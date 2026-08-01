package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.virtual.InMemoryProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineServerRuntimeTest {

    @Test
    fun `owner construction allows only exact engine process of host package`() {
        EngineServerRuntime.requireEngineProcess(
            hostPackageName = "com.multiapp.app",
            processName = "com.multiapp.app:engine"
        )

        listOf(
            null,
            "",
            "com.multiapp.app",
            "com.multiapp.app:v0",
            "com.multiapp.app:v7",
            "other.package:engine",
            "com.multiapp.app:engine.extra"
        ).forEach { processName ->
            val error = assertFailsWith<IllegalStateException>(
                "expected rejection for process=$processName"
            ) {
                EngineServerRuntime.requireEngineProcess("com.multiapp.app", processName)
            }
            assertTrue(error.message.orEmpty().contains("com.multiapp.app:engine"))
        }
    }

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
            processControlPlaneFactory = EngineProcessControlPlaneFactory { registry, deathRegistry, capabilities, cleanup ->
                processControlPlaneCreationCount += 1
                controlPlaneRegistry = registry
                controlPlaneDeathRegistry = deathRegistry
                controlPlaneCapabilities = capabilities
                EngineProcessControlPlane(registry, deathRegistry, capabilities, cleanup).also {
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
        assertSame(owner.systemServiceProxies, engineCore.systemServiceProxyRegistry)
        assertSame(runtimeRegistry, controlPlaneRegistry)
        assertSame(processDeathRegistry, controlPlaneDeathRegistry)
        assertSame(activityLaunchCapabilities, controlPlaneCapabilities)
        assertSame(createdProcessControlPlane, owner.processControlPlane)
        assertEquals(1, processControlPlaneCreationCount)
    }

    @Test
    fun `system service registry is reflected by capabilities and cleared on stop`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = "com.multiapp.app",
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry)
        )
        val runtime = runtimeRegistry.register(providerRuntime())
        val bound = owner.systemServiceProxies.bind(
            EngineSystemServiceBindRequest(
                instanceId = runtime.instanceId,
                serviceId = EngineSystemServiceId.NOTIFICATION,
                runtimeEpoch = runtime.runtimeEpoch,
                engineSessionId = runtime.engineSessionId,
                processSlot = runtime.processSlot,
                apiLevel = 37,
                adapterId = "notification-adapter",
                adapterInstalled = true
            )
        )

        val report = owner.virtualizationEngine.queryCapabilities(runtime.instanceId)

        assertEquals(EngineResultStatus.PARTIAL, bound.verdict)
        assertTrue(
            report.capability("system-service:notification")
                ?.message
                .orEmpty()
                .contains("notification-adapter")
        )
        owner.virtualizationEngine.stopInstance(runtime.instanceId)
        assertTrue(owner.systemServiceProxies.snapshot(runtime.instanceId).isEmpty())
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

    @Test
    fun `production Provider endpoint authority allows only allocated process identity`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = "com.multiapp.app",
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry)
        )
        val runtime = providerRuntime()
        runtimeRegistry.register(runtime)
        val binder = mockk<IBinder>(relaxed = true)
        every { binder.isBinderAlive } returns true

        val sameProcess = owner.providerProcessEndpoints.register(
            identity = providerEndpointIdentity(
                runtime = runtime,
                authority = "com.test.same",
                className = "com.test.SameProcessProvider",
                declaredProcessName = null,
                effectiveProcessName = runtime.originPackageName,
                processSlot = runtime.processSlot,
                processId = requireNotNull(runtime.processId)
            ),
            endpointBinder = binder,
            callingPid = requireNotNull(runtime.processId),
            callingProcessName = runtime.processSlot
        )
        val customProcess = owner.providerProcessEndpoints.register(
            identity = providerEndpointIdentity(
                runtime = runtime,
                authority = "com.test.remote",
                className = "com.test.RemoteProvider",
                declaredProcessName = ":remote",
                effectiveProcessName = "${runtime.originPackageName}:remote",
                processSlot = "com.multiapp.app:v1",
                processId = requireNotNull(runtime.processId) + 1
            ),
            endpointBinder = binder,
            callingPid = requireNotNull(runtime.processId) + 1,
            callingProcessName = "com.multiapp.app:v1"
        )

        assertTrue(sameProcess.accepted)
        assertFalse(customProcess.accepted)
        assertEquals("provider_component_process_slot_not_allocated", customProcess.reason)
    }

    @Test
    fun `component process allocation reserves every runtime primary slot before custom slot`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val runtime = providerRuntime()
        runtimeRegistry.register(runtime)
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = runtime.hostPackageName,
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry)
        )

        val custom = owner.allocateComponentProcessSlot(runtime.instanceId, ":remote")
        val primaryOwner = owner.componentProcessSlots.ownerOf(runtime.processSlot)

        assertEquals(runtime.instanceId, primaryOwner?.instanceId)
        assertEquals(runtime.originPackageName, primaryOwner?.guestProcessName)
        assertEquals("${runtime.hostPackageName}:v1", custom?.processSlot)
        assertEquals("${runtime.originPackageName}:remote", custom?.guestProcessName)
        assertEquals(null, owner.allocateComponentProcessSlot(runtime.instanceId, ":undeclared"))
    }

    @Test
    fun `component process allocation ignores stopped and dead runtime primary slots`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val runtime = providerRuntime()
        val deadRuntime = runtime.copy(
            instanceId = "instance-dead",
            virtualPackageName = "com.multiapp.virtual.dead",
            dataRoot = "build/tmp/provider-dead",
            packageSnapshot = runtime.packageSnapshot.copy(
                instanceId = "instance-dead",
                virtualPackageName = "com.multiapp.virtual.dead",
                dataDir = "build/tmp/provider-dead"
            ),
            processSlot = "${runtime.hostPackageName}:v1",
            proxySlot = "${runtime.hostPackageName}.container.ProxyActivity1",
            runtimeEpoch = runtime.runtimeEpoch + 1,
            engineSessionId = "provider-dead-engine-session",
            processId = null,
            processName = null,
            state = VirtualRuntimeState.DEAD
        )
        runtimeRegistry.register(deadRuntime)
        runtimeRegistry.register(runtime)
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = runtime.hostPackageName,
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry)
        )

        val custom = owner.allocateComponentProcessSlot(runtime.instanceId, ":remote")

        assertEquals("${runtime.hostPackageName}:v1", custom?.processSlot)
        assertEquals(runtime.instanceId, owner.componentProcessSlots.ownerOf(custom!!.processSlot)?.instanceId)
    }

    @Test
    fun `component process client attach requires declared allocation and actual Android identity`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val runtime = providerRuntime()
        runtimeRegistry.register(runtime)
        val processId = requireNotNull(runtime.processId) + 1
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = runtime.hostPackageName,
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry),
            componentProcessIdentityProbe = EngineComponentProcessIdentityProbe { candidatePid ->
                if (candidatePid == processId) {
                    EngineComponentProcessHostIdentity(
                        processName = "${runtime.hostPackageName}:v1",
                        processStartTicks = processId.toLong() * 10L
                    )
                } else {
                    null
                }
            }
        )
        val unallocatedIdentity = componentProcessIdentity(runtime, "${runtime.hostPackageName}:v1", processId)
        val token = mockk<IBinder>(relaxed = true)
        every { token.isBinderAlive } returns true

        val unallocated = owner.attachComponentProcessClient(
            unallocatedIdentity,
            token,
            processId,
            unallocatedIdentity.processSlot
        )
        val assignment = requireNotNull(owner.allocateComponentProcessSlot(runtime.instanceId, ":remote"))
        val identity = componentProcessIdentity(runtime, assignment.processSlot, processId)
        val wrongPid = owner.attachComponentProcessClient(
            identity,
            token,
            processId + 1,
            identity.processSlot
        )
        val attached = owner.attachComponentProcessClient(
            identity,
            token,
            processId,
            identity.processSlot
        )

        assertFalse(unallocated.accepted)
        assertEquals("component_process_slot_not_allocated", unallocated.reason)
        assertFalse(wrongPid.accepted)
        assertEquals("component_process_pid_mismatch", wrongPid.reason)
        assertTrue(attached.accepted)
        assertTrue(owner.componentProcessClients.isAuthoritative(identity, token))
    }

    @Test
    fun `component process IPC authority requires engine ticket and actual slot process`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val runtime = providerRuntime()
        runtimeRegistry.register(runtime)
        val processId = requireNotNull(runtime.processId) + 10
        val processStartTicks = processId.toLong() * 10L
        var observedProcessStartTicks = processStartTicks
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = runtime.hostPackageName,
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry),
            componentProcessIdentityProbe = EngineComponentProcessIdentityProbe { candidatePid ->
                if (candidatePid == processId) {
                    EngineComponentProcessHostIdentity(
                        processName = "${runtime.hostPackageName}:v1",
                        processStartTicks = observedProcessStartTicks
                    )
                } else {
                    null
                }
            }
        )
        val prepared = owner.prepare(runtime.instanceId, ":remote")
        val launchTicket = requireNotNull(prepared.launchTicket)
        val token = mockk<IBinder>(relaxed = true)
        every { token.isBinderAlive } returns true

        val forgedProcess = owner.attach(
            launchTicket.attachCapability,
            token,
            processId,
            "${runtime.hostPackageName}:v7",
            processStartTicks
        )
        val attached = owner.attach(
            launchTicket.attachCapability,
            token,
            processId,
            launchTicket.processSlot,
            processStartTicks
        )
        val repeated = owner.attach(
            launchTicket.attachCapability,
            token,
            processId,
            launchTicket.processSlot,
            processStartTicks
        )
        val queried = owner.query(runtime.instanceId, ":remote")
        val authorized = owner.authorizeCaller(
            runtime.instanceId,
            processId,
            launchTicket.processSlot,
            processStartTicks
        )
        val reusedPid = owner.authorizeCaller(
            runtime.instanceId,
            processId,
            launchTicket.processSlot,
            processStartTicks + 1L
        )

        assertTrue(prepared.accepted)
        assertFalse(prepared.alreadyRunning)
        assertFalse(forgedProcess.accepted)
        assertEquals("component_process_android_name_mismatch", forgedProcess.reason)
        assertTrue(attached.accepted)
        assertFalse(attached.idempotent)
        assertFalse(repeated.accepted)
        assertEquals("component_process_launch_capability_not_found", repeated.reason)
        assertTrue(queried.accepted)
        assertTrue(queried.alreadyRunning)
        assertEquals(attached.processState, queried.processState)
        assertEquals(attached.processState?.processId, authorized?.processId)
        assertEquals(null, reusedPid)

        observedProcessStartTicks += 1L
        val deadClient = owner.query(runtime.instanceId, ":remote")
        assertFalse(deadClient.accepted)
        assertEquals(0, owner.componentProcessClients.activeCount())
    }

    @Test
    fun `component prepare issues one launch ticket without blocking on guest bootstrap`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val runtime = providerRuntime()
        runtimeRegistry.register(runtime)
        var bootstrapCalled = false
        val owner = EngineServerRuntime.createForTest(
            hostPackageName = runtime.hostPackageName,
            instanceManager = mockk<InstanceManager>(relaxed = true),
            virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = EngineProcessBootstrapper {
                bootstrapCalled = true
                error("component prepare must not synchronously bootstrap the target process")
            },
            runtimeRegistry = runtimeRegistry,
            systemServer = DefaultVirtualSystemServer(runtimeRegistry)
        )

        val first = owner.prepare(runtime.instanceId, ":remote")
        val repeated = owner.prepare(runtime.instanceId, ":remote")

        assertTrue(first.accepted, first.reason)
        assertFalse(first.alreadyRunning)
        assertEquals(null, first.processState)
        assertTrue(first.launchTicket?.attachCapability?.isNotBlank() == true)
        assertTrue(repeated.accepted, repeated.reason)
        assertTrue(repeated.idempotent)
        assertEquals(first.launchTicket, repeated.launchTicket)
        assertFalse(bootstrapCalled)
    }

    private fun providerRuntime(): VirtualInstanceRuntime = VirtualInstanceRuntime(
        instanceId = "instance-provider-endpoint",
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test",
        virtualPackageName = "com.multiapp.virtual.provider-endpoint",
        dataRoot = "build/tmp/provider-endpoint",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "instance-provider-endpoint",
            originPackageName = "com.test",
            virtualPackageName = "com.multiapp.virtual.provider-endpoint",
            applicationLabel = "Provider Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/provider.apk",
            dataDir = "build/tmp/provider-endpoint",
            providers = listOf(
                ResolvedComponent(
                    name = "com.test.SameProcessProvider",
                    authorities = listOf("com.test.same")
                ),
                ResolvedComponent(
                    name = "com.test.RemoteProvider",
                    authorities = listOf("com.test.remote"),
                    processName = ":remote"
                )
            )
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "provider-evidence",
        runtimeEpoch = 42L,
        engineSessionId = "provider-engine-session",
        processId = 4200,
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.RUNNING
    )

    private fun providerEndpointIdentity(
        runtime: VirtualInstanceRuntime,
        authority: String,
        className: String,
        declaredProcessName: String?,
        effectiveProcessName: String,
        processSlot: String,
        processId: Int
    ) = EngineProviderProcessEndpointIdentity(
        instanceId = runtime.instanceId,
        guestAuthority = authority,
        providerClassName = className,
        declaredProcessName = declaredProcessName,
        effectiveProcessName = effectiveProcessName,
        processSlot = processSlot,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processId = processId
    )

    private fun componentProcessIdentity(
        runtime: VirtualInstanceRuntime,
        processSlot: String,
        processId: Int
    ) = EngineComponentProcessClientIdentity(
        instanceId = runtime.instanceId,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processEpoch = 1L,
        clientSessionId = "component-session-1",
        effectiveGuestProcessName = "${runtime.originPackageName}:remote",
        processSlot = processSlot,
        processId = processId,
        processStartTicks = processId.toLong() * 10L
    )

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

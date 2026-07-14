package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.model.engine.EngineProfile
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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

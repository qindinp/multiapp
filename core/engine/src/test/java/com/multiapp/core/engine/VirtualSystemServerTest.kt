package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualSystemServerTest {

    @Test
    fun `default system server exposes commercial engine subsystem boundaries`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        assertEquals(EngineSubsystem.PACKAGE, server.packageService.subsystem)
        assertEquals(EngineSubsystem.ACTIVITY, server.activityService.subsystem)
        assertEquals(EngineSubsystem.PROVIDER, server.providerService.subsystem)
        assertEquals(EngineSubsystem.SERVICE, server.serviceService.subsystem)
        assertEquals(EngineSubsystem.BROADCAST, server.broadcastService.subsystem)
        assertEquals(EngineSubsystem.STORAGE, server.storageService.subsystem)
        assertEquals(EngineSubsystem.NATIVE, server.nativeService.subsystem)
        assertEquals(EngineSubsystem.EVIDENCE, server.evidenceService.subsystem)
    }

    @Test
    fun `runtime service is the single facade over runtime registry`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val runtime = runtime()

        val registered = server.runtimeService.register(runtime)
        val accepted = server.runtimeService.registerOperationEvidence(
            runtime.instanceId,
            EngineOperationEvidence(
                component = "provider",
                operation = "query",
                verdict = EngineResultStatus.PASS
            )
        )

        assertSame(runtime, registered)
        assertEquals(runtime, server.runtimeService.get(runtime.instanceId))
        assertTrue(accepted)
        assertEquals(EngineResultStatus.PASS, server.runtimeService.evidence(runtime.instanceId).status)
        assertEquals(EngineResultStatus.PASS, server.runtimeService.evidence(runtime.instanceId).subsystemVerdicts[EngineSubsystem.RUNTIME])
        assertTrue(server.runtimeService.stop(runtime.instanceId))
        assertEquals(EngineResultStatus.FAIL, server.runtimeService.evidence(runtime.instanceId).status)
    }

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = "instance-1",
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.instance-1",
        dataRoot = "build/tmp/instance-1",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "instance-1",
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.instance-1",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/instance-1"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1",
        runtimeEpoch = 42L,
        engineSessionId = "engine-evidence-1",
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.CREATED
    )
}

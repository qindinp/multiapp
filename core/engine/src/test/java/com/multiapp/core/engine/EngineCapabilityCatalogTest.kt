package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineCapabilityCatalogTest {
    @Test
    fun `static report keeps release critical gaps visible`() {
        val report = EngineCapabilityCatalog.report(
            server = DefaultVirtualSystemServer(EngineRuntimeRegistry()),
            instanceId = null,
            generatedAtMs = 123L
        )

        assertEquals(123L, report.generatedAtMs)
        assertEquals(EngineResultStatus.UNSUPPORTED, report.status)
        assertFalse(report.releaseReady)
        assertEquals(
            EngineResultStatus.PARTIAL,
            report.capability("system-service:notification")?.status
        )
        assertEquals(
            EngineResultStatus.UNSUPPORTED,
            report.capability("system-service:job_scheduler")?.status
        )
        assertFalse(report.capability("activity")?.unsupportedOperations?.contains("result-delivery") == true)
        assertTrue(report.capability("activity")?.supportedOperations?.contains("result-delivery") == true)
        assertTrue(report.capability("activity")?.supportedOperations?.contains("finish-result-delivery") == true)
    }

    @Test
    fun `instance report fails closed for an unknown runtime`() {
        val report = EngineCapabilityCatalog.report(
            server = DefaultVirtualSystemServer(EngineRuntimeRegistry()),
            instanceId = "missing-instance",
            generatedAtMs = 1L
        )

        assertEquals(EngineResultStatus.FAIL, report.status)
        assertEquals("runtime_not_found:missing-instance", report.message)
        assertFalse(report.releaseReady)
    }

    @Test
    fun `system service registry accepts only current runtime identity`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val runtime = server.runtimeService.register(runtime())
        val serviceRegistry = EngineSystemServiceProxyRegistry(server.runtimeService) { 99L }

        val accepted = serviceRegistry.bind(
            bindRequest(runtime, EngineSystemServiceId.NOTIFICATION)
        )
        val stale = serviceRegistry.bind(
            bindRequest(runtime, EngineSystemServiceId.NOTIFICATION).copy(runtimeEpoch = runtime.runtimeEpoch + 1)
        )
        val unsupported = serviceRegistry.bind(
            bindRequest(runtime, EngineSystemServiceId.JOB_SCHEDULER)
        )

        assertEquals(EngineResultStatus.PARTIAL, accepted.verdict)
        assertEquals(99L, accepted.binding?.boundAtMs)
        assertEquals(EngineResultStatus.FAIL, stale.verdict)
        assertEquals("runtime_epoch_mismatch", stale.message)
        assertEquals(EngineResultStatus.UNSUPPORTED, unsupported.verdict)
        assertNotNull(serviceRegistry.query(runtime.instanceId, EngineSystemServiceId.NOTIFICATION))
        assertNull(serviceRegistry.query(runtime.instanceId, EngineSystemServiceId.JOB_SCHEDULER))
        assertEquals(1, serviceRegistry.clearInstance(runtime.instanceId))
        assertTrue(serviceRegistry.snapshot().isEmpty())
    }

    private fun bindRequest(
        runtime: VirtualInstanceRuntime,
        serviceId: EngineSystemServiceId
    ) = EngineSystemServiceBindRequest(
        instanceId = runtime.instanceId,
        serviceId = serviceId,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processSlot = runtime.processSlot,
        apiLevel = 37,
        adapterId = "test-adapter",
        adapterInstalled = true
    )

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = "instance-1",
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.instance1",
        dataRoot = "build/tmp/instance-1",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "instance-1",
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.instance1",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 37,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/instance-1"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1",
        runtimeEpoch = 42L,
        engineSessionId = "engine-session-1",
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.CREATED
    )
}

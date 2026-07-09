package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineOperationEvidenceSinkTest {

    @Test
    fun `accepted evidence returns updated report through runtime service`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        val sink = DefaultEngineOperationEvidenceSink(
            RegistryBackedVirtualRuntimeService(registry)
        )

        val result = sink.record(
            instanceId = INSTANCE_ID,
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "query",
                verdict = EngineResultStatus.PASS,
                entries = mapOf("routeToken" to "secret-token")
            )
        )

        val evidence = assertNotNull(result.report)
            .operationEntries("provider", "query")
            .single()
        assertTrue(result.accepted)
        assertEquals("<redacted>", evidence.entries["routeToken"])
        assertEquals(EngineResultStatus.PASS, result.report?.status)
    }

    @Test
    fun `missing runtime rejects evidence and returns no report`() {
        val sink = DefaultEngineOperationEvidenceSink(
            RegistryBackedVirtualRuntimeService(EngineRuntimeRegistry())
        )

        val result = sink.record(
            instanceId = INSTANCE_ID,
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "query",
                verdict = EngineResultStatus.PASS
            )
        )

        assertFalse(result.accepted)
        assertNull(result.report)
    }

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.inst001",
        dataRoot = "build/tmp/inst-001",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.inst001",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/base.apk",
            dataDir = "build/tmp/inst-001"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1"
    )

    private companion object {
        const val INSTANCE_ID = "inst-001"
    }
}

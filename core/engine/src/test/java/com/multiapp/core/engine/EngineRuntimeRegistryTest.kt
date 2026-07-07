package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRuntimeRegistryTest {

    @Test
    fun `operation evidence is rejected when runtime is missing`() {
        val registry = EngineRuntimeRegistry()

        val accepted = registry.registerOperationEvidence(
            instanceId = "missing-instance",
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "route-token",
                verdict = EngineResultStatus.PASS,
                entries = mapOf("routeToken" to "token-1")
            )
        )
        val report = registry.evidence("missing-instance")

        assertFalse(accepted)
        assertEquals(EngineResultStatus.FAIL, report.status)
        assertEquals("runtime_not_found", report.entries["reason"])
        assertTrue(report.operationEvidence.isEmpty())
    }

    @Test
    fun `operation evidence is rejected after runtime is stopped`() {
        val registry = EngineRuntimeRegistry()
        val runtime = runtime()
        registry.register(runtime)

        val acceptedBeforeStop = registry.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = operationEvidence(verdict = EngineResultStatus.PASS)
        )

        val stopped = registry.stop(runtime.instanceId)
        val acceptedAfterStop = registry.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = operationEvidence(verdict = EngineResultStatus.PASS)
        )
        val report = registry.evidence(runtime.instanceId)

        assertTrue(acceptedBeforeStop)
        assertTrue(stopped)
        assertFalse(acceptedAfterStop)
        assertEquals(EngineResultStatus.FAIL, report.status)
        assertEquals("runtime_not_found", report.entries["reason"])
        assertTrue(report.operationEvidence.isEmpty())
    }

    @Test
    fun `operation evidence is sanitized before report aggregation`() {
        val registry = EngineRuntimeRegistry()
        val runtime = runtime()
        registry.register(runtime)

        val accepted = registry.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "provider:password=component-secret",
                operation = "query:password=method-secret",
                verdict = EngineResultStatus.PASS,
                entries = mapOf(
                    "uri" to "content://com.multiapp.app.stub/items?multiapp_routeToken=secret-token",
                    "callback" to "dispatch content://com.multiapp.app.stub/items?multiapp_routeToken=secret-token",
                    "routeToken" to "secret-token",
                    "password" to "plain-password",
                    "detail" to "credential=plain-credential"
                )
            )
        )
        val evidence = registry.evidence(runtime.instanceId).operationEntries("provider", "query").single()

        assertTrue(accepted)
        assertEquals("content://com.multiapp.app.stub/<redacted>", evidence.entries["uri"])
        assertEquals("dispatch content://com.multiapp.app.stub/<redacted>", evidence.entries["callback"])
        assertEquals("<redacted>", evidence.entries["routeToken"])
        assertEquals("<redacted>", evidence.entries["password"])
        assertEquals("<redacted>", evidence.entries["detail"])
    }

    @Test
    fun `same component operation evidence is appended in registration order`() {
        val registry = EngineRuntimeRegistry()
        val runtime = runtime()
        registry.register(runtime)

        val firstAccepted = registry.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "route-token",
                verdict = EngineResultStatus.PASS,
                entries = mapOf("attempt" to "1", "state" to "started")
            )
        )
        val secondAccepted = registry.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "route-token",
                verdict = EngineResultStatus.PASS,
                entries = mapOf("attempt" to "2", "state" to "confirmed")
            )
        )

        val report = registry.evidence(runtime.instanceId)
        val groupedAttempts = report.operationEntries("provider", "route-token")
            .map { evidence -> evidence.entries["attempt"] }
        val flattenedAttempts = report.flattenedOperationEvidence()
            .map { evidence -> evidence.entries["attempt"] }

        assertTrue(firstAccepted)
        assertTrue(secondAccepted)
        assertEquals(listOf("1", "2"), groupedAttempts)
        assertEquals(listOf("1", "2"), flattenedAttempts)
        assertEquals(report.flattenedOperationEvidence(), report.flattenedOperationEvidence())
    }

    @Test
    fun `registry evidence verdict keeps highest severity across append order`() {
        val registry = EngineRuntimeRegistry()
        val runtime = runtime()
        registry.register(runtime)

        assertTrue(
            registry.registerOperationEvidence(
                instanceId = runtime.instanceId,
                evidence = operationEvidence(verdict = EngineResultStatus.PARTIAL)
            )
        )
        assertEquals(EngineResultStatus.PARTIAL, registry.evidence(runtime.instanceId).status)

        assertTrue(
            registry.registerOperationEvidence(
                instanceId = runtime.instanceId,
                evidence = operationEvidence(verdict = EngineResultStatus.PASS)
            )
        )
        assertEquals(EngineResultStatus.PARTIAL, registry.evidence(runtime.instanceId).status)

        assertTrue(
            registry.registerOperationEvidence(
                instanceId = runtime.instanceId,
                evidence = operationEvidence(verdict = EngineResultStatus.FAIL)
            )
        )
        assertEquals(EngineResultStatus.FAIL, registry.evidence(runtime.instanceId).status)

        assertTrue(
            registry.registerOperationEvidence(
                instanceId = runtime.instanceId,
                evidence = operationEvidence(verdict = EngineResultStatus.UNSUPPORTED)
            )
        )
        assertEquals(EngineResultStatus.FAIL, registry.evidence(runtime.instanceId).status)
    }

    private fun operationEvidence(verdict: EngineResultStatus) = EngineOperationEvidence(
        component = "provider",
        operation = "route-token",
        verdict = verdict,
        entries = mapOf("routeToken" to "token-1")
    )

    private fun runtime(instanceId: String = "instance-1") = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = packageSnapshot(instanceId),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.ProxyActivity0",
        evidenceSessionId = "evidence-1"
    )

    private fun packageSnapshot(instanceId: String) = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        applicationLabel = "Test",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        sourceDir = "build/tmp/test.apk",
        dataDir = "build/tmp/$instanceId"
    )
}

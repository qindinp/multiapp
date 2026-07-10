package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `same-origin provider and native evidence stays isolated per process slot and instance`() {
        val registry = EngineRuntimeRegistry()
        val first = runtime(
            instanceId = "instance-1",
            processSlot = "com.multiapp.app:v0",
            proxySlot = "com.multiapp.app.ProxyActivity0"
        )
        val second = runtime(
            instanceId = "instance-2",
            processSlot = "com.multiapp.app:v1",
            proxySlot = "com.multiapp.app.ProxyActivity1"
        )
        registry.register(first)
        registry.register(second)

        assertTrue(
            registry.registerOperationEvidence(
                instanceId = first.instanceId,
                evidence = EngineOperationEvidence(
                    component = "provider",
                    operation = "query",
                    verdict = EngineResultStatus.PASS,
                    entries = mapOf(
                        "authority" to "com.test.app.provider",
                        "processSlot" to first.processSlot,
                        "instanceId" to first.instanceId
                    )
                )
            )
        )
        assertTrue(
            registry.registerOperationEvidence(
                instanceId = second.instanceId,
                evidence = EngineOperationEvidence(
                    component = "native",
                    operation = "path-redirect",
                    verdict = EngineResultStatus.PARTIAL,
                    entries = mapOf(
                        "requestedPath" to "/data/data/com.test.app/files/db",
                        "processSlot" to second.processSlot,
                        "instanceId" to second.instanceId
                    )
                )
            )
        )

        val firstReport = registry.evidence(first.instanceId)
        val secondReport = registry.evidence(second.instanceId)

        assertEquals(first.processSlot, firstReport.entries["processSlot"])
        assertEquals(second.processSlot, secondReport.entries["processSlot"])
        assertEquals(listOf("provider"), firstReport.flattenedOperationEvidence().map { it.component })
        assertEquals(listOf("native"), secondReport.flattenedOperationEvidence().map { it.component })
        assertEquals(first.instanceId, firstReport.operationEntries("provider", "query").single().entries["instanceId"])
        assertEquals(second.instanceId, secondReport.operationEntries("native", "path-redirect").single().entries["instanceId"])
    }

    @Test
    fun `stopping one same-origin runtime rejects later evidence without clearing sibling runtime`() {
        val registry = EngineRuntimeRegistry()
        val first = runtime(instanceId = "instance-1", processSlot = "com.multiapp.app:v0")
        val second = runtime(instanceId = "instance-2", processSlot = "com.multiapp.app:v1")
        registry.register(first)
        registry.register(second)

        assertTrue(registry.stop(first.instanceId))
        val stoppedAccepted = registry.registerOperationEvidence(
            instanceId = first.instanceId,
            evidence = operationEvidence(verdict = EngineResultStatus.PASS)
        )
        val siblingAccepted = registry.registerOperationEvidence(
            instanceId = second.instanceId,
            evidence = operationEvidence(verdict = EngineResultStatus.PASS)
        )

        assertFalse(stoppedAccepted)
        assertTrue(siblingAccepted)
        assertEquals(EngineResultStatus.FAIL, registry.evidence(first.instanceId).status)
        assertTrue(registry.evidence(first.instanceId).operationEvidence.isEmpty())
        assertEquals(EngineResultStatus.PASS, registry.evidence(second.instanceId).status)
        assertEquals(second, registry.get(second.instanceId))
    }

    @Test
    fun `runtime state restores from file backed store after registry rebuild`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val firstRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val runtime = runtime(
            instanceId = "instance-1",
            processSlot = "com.multiapp.app:v4",
            proxySlot = "com.multiapp.app.container.ProxyActivity4"
        )
        firstRegistry.register(runtime)

        val rebuiltRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val restored = rebuiltRegistry.get(runtime.instanceId)
        val restoredReport = rebuiltRegistry.evidence(runtime.instanceId)

        assertEquals(runtime.instanceId, restored?.instanceId)
        assertEquals(runtime.processSlot, restored?.processSlot)
        assertEquals(runtime.proxySlot, restored?.proxySlot)
        assertEquals(runtime.runtimeEpoch, restored?.runtimeEpoch)
        assertEquals("durable", restoredReport.entries["runtimeStateSource"])
        assertEquals(runtime.processSlot, restoredReport.entries["processSlot"])
        assertEquals(EngineResultStatus.PASS, restoredReport.status)
    }

    @Test
    fun `stop removes file backed runtime state`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val registry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val runtime = runtime()
        registry.register(runtime)

        assertTrue(registry.stop(runtime.instanceId))

        val rebuiltRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        assertEquals(null, rebuiltRegistry.get(runtime.instanceId))
        assertEquals(EngineResultStatus.FAIL, rebuiltRegistry.evidence(runtime.instanceId).status)
    }

    @Test
    fun `cached registry refreshes runtime written by another registry`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val writer = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val reader = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val created = runtime(runtimeEpoch = 10L)
        val running = created.copy(
            runtimeEpoch = 11L,
            state = com.multiapp.core.model.engine.VirtualRuntimeState.RUNNING,
            processId = 1234
        )
        writer.register(created)
        assertEquals(created, reader.get(created.instanceId))

        writer.register(running)

        assertEquals(running, reader.get(created.instanceId))
        assertEquals("durable-refresh", reader.evidence(created.instanceId).entries["runtimeStateSource"])
    }

    @Test
    fun `stale registry cannot overwrite newer runtime epoch`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val newestRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val staleRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val newest = runtime(runtimeEpoch = 20L, processSlot = "com.multiapp.app:v2")
        val stale = runtime(runtimeEpoch = 10L, processSlot = "com.multiapp.app:v1")
        newestRegistry.register(newest)

        val accepted = staleRegistry.register(stale)

        assertEquals(newest, accepted)
        assertEquals(newest, staleRegistry.get(newest.instanceId))
        assertEquals("durable-newer", staleRegistry.evidence(newest.instanceId).entries["runtimeStateSource"])
    }

    @Test
    fun `cached registry observes runtime stopped by another registry`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val owner = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val observer = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(file))
        val runtime = runtime(runtimeEpoch = 30L)
        owner.register(runtime)
        assertEquals(runtime, observer.get(runtime.instanceId))

        assertTrue(owner.stop(runtime.instanceId))

        assertNull(observer.get(runtime.instanceId))
        assertEquals(EngineResultStatus.FAIL, observer.evidence(runtime.instanceId).status)
    }

    private fun operationEvidence(verdict: EngineResultStatus) = EngineOperationEvidence(
        component = "provider",
        operation = "route-token",
        verdict = verdict,
        entries = mapOf("routeToken" to "token-1")
    )

    private fun runtime(
        instanceId: String = "instance-1",
        processSlot: String = "com.multiapp.app:v0",
        proxySlot: String = "com.multiapp.app.ProxyActivity0",
        runtimeEpoch: Long = 1L
    ) = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = packageSnapshot(instanceId),
        profile = EngineProfile.BASELINE,
        processSlot = processSlot,
        proxySlot = proxySlot,
        evidenceSessionId = "evidence-1",
        runtimeEpoch = runtimeEpoch
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

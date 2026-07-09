package com.multiapp.app.container

import com.multiapp.core.engine.EngineRuntimeRegistry
import com.multiapp.core.engine.EngineStorageDiagnosticKind
import com.multiapp.core.engine.EngineStorageDiagnosticStatus
import com.multiapp.core.engine.EngineStoragePathDiagnostic
import com.multiapp.core.engine.DefaultEngineOperationEvidenceSink
import com.multiapp.core.engine.RegistryBackedVirtualRuntimeService
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerEngineEvidenceBridgeTest {

    @Test
    fun `provider operation evidence is appended and exported without leaking sensitive values`(
        @TempDir filesDir: File
    ) {
        val registry = registryWithRuntime(filesDir)
        val sink = sinkFor(registry)

        val accepted = ContainerEngineEvidenceBridge.recordProviderOperation(
            instanceId = INSTANCE_ID,
            operationName = "query:password=method-secret",
            fields = mapOf(
                "status" to "PROVIDER_CREATED",
                "evidenceSuccess" to true,
                "uri" to "content://com.multiapp.app.multiapp.provider.stub/items?multiapp_routeToken=secret-token",
                "callback" to "dispatch content://com.multiapp.app.multiapp.provider.stub/items?multiapp_routeToken=secret-token",
                "routeToken" to "secret-token",
                "password" to "plain-password",
                "providerSecret" to "plain-secret",
                "credentialBlob" to "plain-credential",
                "detail" to "credential=plain-credential",
                "guestAuthority" to "com.test.app.provider"
            ),
            sink = sink
        )

        val evidence = registry.evidence(INSTANCE_ID).operationEntries("provider", "query").single()

        assertTrue(accepted)
        assertEquals(EngineResultStatus.PASS, evidence.verdict)
        assertEquals("content://com.multiapp.app.multiapp.provider.stub/<redacted>", evidence.entries["uri"])
        assertEquals("dispatch content://com.multiapp.app.multiapp.provider.stub/<redacted>", evidence.entries["callback"])
        assertEquals("<redacted>", evidence.entries["routeToken"])
        assertEquals("<redacted>", evidence.entries["password"])
        assertEquals("<redacted>", evidence.entries["providerSecret"])
        assertEquals("<redacted>", evidence.entries["credentialBlob"])
        assertEquals("<redacted>", evidence.entries["detail"])
        assertFalse(evidence.entries.values.any { value ->
            listOf(
                "secret-token",
                "plain-password",
                "plain-secret",
                "plain-credential",
                "method-secret"
            ).any(value::contains)
        })

        val exported = engineReportFile(filesDir).readText()
        assertTrue(exported.contains("component=engine-report"))
        assertTrue(exported.contains("instanceId=$INSTANCE_ID"))
        assertTrue(exported.contains("evidenceSessionId=evidence-1"))
        assertTrue(exported.contains("status=PASS"))
        assertTrue(exported.contains("profile=BASELINE"))
        assertTrue(exported.contains("hostPackageName=com.multiapp.app"))
        assertTrue(exported.contains("subsystemVerdicts.runtime=PASS"))
        assertTrue(exported.contains("subsystemVerdicts.evidence=PASS"))
        assertTrue(exported.contains("operationEvidence.provider.query.0.verdict=PASS"))
        assertTrue(exported.contains("operationEvidence.provider.query.0.entry.guestAuthority=com.test.app.provider"))
        listOf(
            "secret-token",
            "plain-password",
            "plain-secret",
            "plain-credential",
            "method-secret"
        ).forEach { leaked ->
            assertFalse(exported.contains(leaked), "engine report leaked $leaked in $exported")
        }
    }

    @Test
    fun `native storage diagnostic evidence updates and exports engine report status`(
        @TempDir filesDir: File
    ) {
        val dataRoot = ContainerRuntimePaths.instanceDataRoot(filesDir, INSTANCE_ID).absolutePath
        val registry = registryWithRuntime(filesDir)
        val sink = sinkFor(registry)
        val diagnostic = EngineStoragePathDiagnostic(
            kind = EngineStorageDiagnosticKind.NATIVE_IO,
            status = EngineStorageDiagnosticStatus.UNCHANGED,
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            dataRoot = dataRoot,
            probeName = null,
            operation = "openat",
            originalPath = "/data/data/$ORIGIN_PACKAGE/files/pr10-native-openat.txt",
            redirectedPath = "",
            candidateRedirectedPath = "$dataRoot/files/pr10-native-openat.txt",
            caller = "test",
            reason = "NATIVE_IO_PATH_NOT_REDIRECTED",
            withinDataRoot = false,
            candidateWithinDataRoot = true
        )

        val accepted = ContainerEngineEvidenceBridge.recordNativeStorageDiagnostic(
            diagnostic = diagnostic,
            fields = ContainerStorageDiagnosticsEvidence.fieldsForDiagnostic(diagnostic),
            sink = sink
        )
        val report = registry.evidence(INSTANCE_ID)
        val evidence = report.operationEntries("native", "openat").single()

        assertTrue(accepted)
        assertEquals(EngineResultStatus.FAIL, evidence.verdict)
        assertEquals(EngineResultStatus.FAIL, report.status)
        assertEquals("FAIL", evidence.entries["nativeIoRedirectVerdict"])
        assertEquals("NATIVE_IO_PATH_NOT_REDIRECTED", evidence.entries["nativeIoRedirectVerdictReason"])

        val exported = engineReportFile(filesDir).readText()
        assertTrue(exported.contains("status=FAIL"))
        assertTrue(exported.contains("operationEvidence.native.openat.0.verdict=FAIL"))
        assertTrue(exported.contains("operationEvidence.native.openat.0.entry.nativeIoRedirectVerdict=FAIL"))
        assertTrue(
            exported.contains(
                "operationEvidence.native.openat.0.entry.nativeIoRedirectVerdictReason=NATIVE_IO_PATH_NOT_REDIRECTED"
            )
        )
    }

    @Test
    fun `provider runtime bind evidence records successful bound and cached status as pass`() {
        val registry = registryWithRuntime()
        val sink = sinkFor(registry)

        val accepted = listOf("BOUND", "CACHED").map { status ->
            ContainerEngineEvidenceBridge.recordProviderOperation(
                instanceId = INSTANCE_ID,
                operationName = "runtime-bind:$status",
                fields = mapOf(
                    "status" to status,
                    "providerRuntimeBindStatus" to status,
                    "providerRuntimeBindDetail" to "runtimeBoundForProviderProxy"
                ),
                sink = sink
            )
        }
        val report = registry.evidence(INSTANCE_ID)
        val evidence = report.operationEntries("provider", "runtime-bind")

        assertTrue(accepted.all { it })
        assertEquals(2, evidence.size)
        assertTrue(evidence.all { it.verdict == EngineResultStatus.PASS })
        assertEquals(EngineResultStatus.PASS, report.status)
        assertEquals(listOf("BOUND", "CACHED"), evidence.map { it.entries["providerRuntimeBindStatus"] })
    }

    @Test
    fun `provider runtime not bound evidence cannot be promoted to pass by inconsistent success fields`() {
        val registry = registryWithRuntime()
        val sink = sinkFor(registry)

        val accepted = ContainerEngineEvidenceBridge.recordProviderOperation(
            instanceId = INSTANCE_ID,
            operationName = "query",
            fields = mapOf(
                "status" to "RUNTIME_NOT_BOUND",
                "evidenceSuccess" to true,
                "cached" to true
            ),
            sink = sink
        )
        val report = registry.evidence(INSTANCE_ID)
        val evidence = report.operationEntries("provider", "query").single()

        assertTrue(accepted)
        assertEquals(EngineResultStatus.PARTIAL, evidence.verdict)
        assertEquals(EngineResultStatus.PARTIAL, report.status)
    }

    @Test
    fun `operation evidence is rejected when engine runtime is missing`() {
        val registry = EngineRuntimeRegistry()
        val sink = sinkFor(registry)

        val accepted = ContainerEngineEvidenceBridge.recordProviderOperation(
            instanceId = INSTANCE_ID,
            operationName = "query",
            fields = mapOf("status" to "PROVIDER_CREATED", "evidenceSuccess" to true),
            sink = sink
        )

        assertFalse(accepted)
        assertEquals(EngineResultStatus.FAIL, registry.evidence(INSTANCE_ID).status)
        assertTrue(registry.evidence(INSTANCE_ID).operationEvidence.isEmpty())
    }

    @Test
    fun `rejected operation evidence does not export stale pass report`(
        @TempDir filesDir: File
    ) {
        val registry = registryWithRuntime(filesDir)
        val sink = sinkFor(registry)
        registry.stop(INSTANCE_ID)

        val accepted = ContainerEngineEvidenceBridge.recordProviderOperation(
            instanceId = INSTANCE_ID,
            operationName = "query",
            fields = mapOf("status" to "PROVIDER_CREATED", "evidenceSuccess" to true),
            sink = sink
        )

        assertFalse(accepted)
        assertFalse(engineReportFile(filesDir).exists())
    }

    @Test
    fun `engine report exporter sanitizes unsanitized operation labels`() {
        val fields = ContainerEngineEvidenceReportExporter.fieldsForReport(
            EngineEvidenceReport(
                instanceId = INSTANCE_ID,
                evidenceSessionId = "evidence-1",
                status = EngineResultStatus.PASS,
                profile = EngineProfile.BASELINE,
                operationEvidence = mapOf(
                    "provider:password=component-secret" to mapOf(
                        "query:password=operation-secret" to listOf(
                            EngineOperationEvidence(
                                component = "provider:password=component-secret",
                                operation = "query:password=operation-secret",
                                verdict = EngineResultStatus.PASS,
                                entries = mapOf("routeToken" to "secret-token")
                            )
                        )
                    )
                )
            )
        )
        val rendered = fields.entries.joinToString("\n") { (key, value) -> "$key=$value" }

        assertEquals("provider", fields["operationEvidence.0.component"])
        assertEquals("query", fields["operationEvidence.0.operation"])
        assertEquals("<redacted>", fields["operationEvidence.provider.query.0.entry.routeToken"])
        listOf("component-secret", "operation-secret", "secret-token").forEach { leaked ->
            assertFalse(rendered.contains(leaked), "engine report fields leaked $leaked in $rendered")
        }
    }

    private fun registryWithRuntime(filesDir: File? = null): EngineRuntimeRegistry {
        val dataRoot = filesDir
            ?.let { ContainerRuntimePaths.instanceDataRoot(it, INSTANCE_ID).absolutePath }
            ?: DATA_ROOT
        return EngineRuntimeRegistry().apply {
            register(
                VirtualInstanceRuntime(
                    instanceId = INSTANCE_ID,
                    hostPackageName = "com.multiapp.app",
                    originPackageName = ORIGIN_PACKAGE,
                    virtualPackageName = VIRTUAL_PACKAGE,
                    dataRoot = dataRoot,
                    packageSnapshot = VirtualPackageSnapshot(
                        instanceId = INSTANCE_ID,
                        originPackageName = ORIGIN_PACKAGE,
                        virtualPackageName = VIRTUAL_PACKAGE,
                        applicationLabel = "Test",
                        versionCode = 1L,
                        versionName = "1.0",
                        targetSdk = 35,
                        minSdk = 28,
                        sourceDir = "/tmp/base.apk",
                        dataDir = dataRoot
                    ),
                    profile = EngineProfile.BASELINE,
                    processSlot = "com.multiapp.app:v0",
                    proxySlot = "com.multiapp.app.ProxyActivity0",
                    evidenceSessionId = "evidence-1"
                )
            )
        }
    }

    private fun engineReportFile(filesDir: File): File {
        return File(filesDir, "hosted_launch_evidence/$INSTANCE_ID.engine-report.properties")
    }

    private fun sinkFor(registry: EngineRuntimeRegistry): DefaultEngineOperationEvidenceSink =
        DefaultEngineOperationEvidenceSink(RegistryBackedVirtualRuntimeService(registry))

    private companion object {
        const val INSTANCE_ID = "inst-001"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.inst001"
        const val DATA_ROOT = "/data/user/0/com.multiapp.app/files/instance_data/inst-001"
    }
}

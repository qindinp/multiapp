package com.multiapp.app.container

import com.multiapp.core.engine.EngineRuntimeRegistry
import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStorageDiagnosticStatus
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerEngineEvidenceBridgeTest {

    @Test
    fun `provider operation evidence is appended to engine report without leaking sensitive values`() {
        val registry = registryWithRuntime()

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
            registry = registry
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
    }

    @Test
    fun `native storage diagnostic evidence updates engine report status`() {
        val registry = registryWithRuntime()
        val diagnostic = VirtualStoragePathDiagnostic(
            kind = VirtualStorageDiagnosticKind.NATIVE_IO,
            status = VirtualStorageDiagnosticStatus.UNCHANGED,
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            dataRoot = DATA_ROOT,
            probeName = null,
            operation = "openat",
            originalPath = "/data/data/$ORIGIN_PACKAGE/files/pr10-native-openat.txt",
            redirectedPath = "",
            candidateRedirectedPath = "$DATA_ROOT/files/pr10-native-openat.txt",
            caller = "test",
            reason = "NATIVE_IO_PATH_NOT_REDIRECTED",
            withinDataRoot = false,
            candidateWithinDataRoot = true
        )

        val accepted = ContainerEngineEvidenceBridge.recordNativeStorageDiagnostic(
            diagnostic = diagnostic,
            fields = ContainerStorageDiagnosticsEvidence.fieldsForDiagnostic(diagnostic),
            registry = registry
        )
        val report = registry.evidence(INSTANCE_ID)
        val evidence = report.operationEntries("native", "openat").single()

        assertTrue(accepted)
        assertEquals(EngineResultStatus.FAIL, evidence.verdict)
        assertEquals(EngineResultStatus.FAIL, report.status)
        assertEquals("FAIL", evidence.entries["nativeIoRedirectVerdict"])
        assertEquals("NATIVE_IO_PATH_NOT_REDIRECTED", evidence.entries["nativeIoRedirectVerdictReason"])
    }

    @Test
    fun `provider runtime bind evidence records successful bound and cached status as pass`() {
        val registry = registryWithRuntime()

        val accepted = listOf("BOUND", "CACHED").map { status ->
            ContainerEngineEvidenceBridge.recordProviderOperation(
                instanceId = INSTANCE_ID,
                operationName = "runtime-bind:$status",
                fields = mapOf(
                    "status" to status,
                    "providerRuntimeBindStatus" to status,
                    "providerRuntimeBindDetail" to "runtimeBoundForProviderProxy"
                ),
                registry = registry
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

        val accepted = ContainerEngineEvidenceBridge.recordProviderOperation(
            instanceId = INSTANCE_ID,
            operationName = "query",
            fields = mapOf(
                "status" to "RUNTIME_NOT_BOUND",
                "evidenceSuccess" to true,
                "cached" to true
            ),
            registry = registry
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

        val accepted = ContainerEngineEvidenceBridge.recordProviderOperation(
            instanceId = INSTANCE_ID,
            operationName = "query",
            fields = mapOf("status" to "PROVIDER_CREATED", "evidenceSuccess" to true),
            registry = registry
        )

        assertFalse(accepted)
        assertEquals(EngineResultStatus.FAIL, registry.evidence(INSTANCE_ID).status)
        assertTrue(registry.evidence(INSTANCE_ID).operationEvidence.isEmpty())
    }

    private fun registryWithRuntime(): EngineRuntimeRegistry {
        return EngineRuntimeRegistry().apply {
            register(
                VirtualInstanceRuntime(
                    instanceId = INSTANCE_ID,
                    hostPackageName = "com.multiapp.app",
                    originPackageName = ORIGIN_PACKAGE,
                    virtualPackageName = VIRTUAL_PACKAGE,
                    dataRoot = DATA_ROOT,
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
                        dataDir = DATA_ROOT
                    ),
                    profile = EngineProfile.BASELINE,
                    processSlot = "com.multiapp.app:v0",
                    proxySlot = "com.multiapp.app.ProxyActivity0",
                    evidenceSessionId = "evidence-1"
                )
            )
        }
    }

    private companion object {
        const val INSTANCE_ID = "inst-001"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.inst001"
        const val DATA_ROOT = "/data/user/0/com.multiapp.app/files/instance_data/inst-001"
    }
}

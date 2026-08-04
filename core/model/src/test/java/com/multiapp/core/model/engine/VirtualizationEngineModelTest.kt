package com.multiapp.core.model.engine

import com.multiapp.core.model.virtual.ProxySlotContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VirtualizationEngineModelTest {
    @Test
    fun `capability report release readiness requires a passing report status`() {
        val passingCapability = EngineCapability(
            id = "activity",
            subsystem = EngineSubsystem.ACTIVITY,
            status = EngineResultStatus.PASS,
            releaseCritical = true
        )

        assertFalse(
            EngineCapabilityReport(
                status = EngineResultStatus.FAIL,
                capabilities = listOf(passingCapability),
                generatedAtMs = 1L
            ).releaseReady
        )
        assertTrue(
            EngineCapabilityReport(
                status = EngineResultStatus.PASS,
                capabilities = listOf(passingCapability),
                generatedAtMs = 1L
            ).releaseReady
        )
    }

    @Test
    fun `pass and partial results are successful`() {
        assertTrue(EngineResult.pass(operation = "launch").success)
        assertTrue(EngineResult.partial(operation = "launch", message = "evidence incomplete").success)
        assertFalse(EngineResult.fail(operation = "launch", message = "failed").success)
        assertFalse(EngineResult.unsupported(operation = "bindService", message = "not implemented").success)
    }

    @Test
    fun `launch request defaults to baseline profile`() {
        val request = LaunchInstanceRequest(instanceId = "instance-1")

        assertEquals(EngineProfile.BASELINE, request.profile)
        assertEquals("user", request.reason)
        assertEquals(EngineTaskPolicy.DEFAULT, request.taskPolicy)
        assertEquals(EnginePrewarmPolicy.DEFAULT, request.prewarmPolicy)
        assertEquals(EngineEvidenceMode.DEFAULT, request.evidenceMode)
        assertFalse(request.providerHookEnabled)
        assertEquals(0, request.launchFlags)
        assertEquals(null, request.targetComponentClassName)
    }

    @Test
    fun `create request carries immutable package generation and idempotency identity`() {
        val install = EnginePackageInstallRequest(
            originPackageName = "com.test.app",
            originApkPath = "/data/app/com.test.app/base.apk",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            packageLabel = "Test",
            splitApkPaths = listOf("/data/app/com.test.app/config.apk"),
            splitNames = listOf("config")
        )
        val request = CreateInstanceRequest(
            creationRequestId = "create-request-1",
            install = install,
            displayName = "Test Work"
        )

        assertEquals("com.test.app", request.originPackageName)
        assertEquals("create-request-1", request.creationRequestId)
        assertEquals(listOf("config"), request.install.splitNames)
    }

    @Test
    fun `create request rejects unsafe identity and inconsistent split metadata`() {
        assertFailsWith<IllegalArgumentException> {
            EnginePackageInstallRequest(
                originPackageName = "../unsafe",
                originApkPath = "/tmp/base.apk",
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 35,
                minSdk = 28,
                packageLabel = "Unsafe"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EnginePackageInstallRequest(
                originPackageName = "com.test.app",
                originApkPath = "/tmp/base.apk",
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 35,
                minSdk = 28,
                packageLabel = "Test",
                splitApkPaths = listOf("/tmp/one.apk", "/tmp/two.apk"),
                splitNames = listOf("one")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CreateInstanceRequest(
                creationRequestId = "unsafe/request",
                install = EnginePackageInstallRequest(
                    originPackageName = "com.test.app",
                    originApkPath = "/tmp/base.apk",
                    versionCode = 1L,
                    versionName = "1.0",
                    targetSdk = 35,
                    minSdk = 28,
                    packageLabel = "Test"
                ),
                displayName = "Test"
            )
        }
    }

    @Test
    fun `provider route contract keeps stable proxy parameter names`() {
        assertEquals("multiapp_instanceId", ProviderRouteContract.PROXY_INSTANCE_ID)
        assertEquals("multiapp_guestAuthority", ProviderRouteContract.PROXY_GUEST_AUTHORITY)
        assertEquals("multiapp_processSlot", ProviderRouteContract.PROXY_PROCESS_SLOT)
        assertEquals("multiapp_routeToken", ProviderRouteContract.PROXY_ROUTE_TOKEN)
    }

    @Test
    fun `proxy slot contract keeps stable slot assignment file name`() {
        assertEquals("proxy_activity_slots.properties", ProxySlotContract.SLOT_ASSIGNMENT_FILE)
    }

    @Test
    fun `evidence report groups operation evidence without replacing runtime entries`() {
        val report = EngineEvidenceReport(
            instanceId = "instance-1",
            evidenceSessionId = "evidence-1",
            status = EngineResultStatus.PASS,
            profile = EngineProfile.BASELINE,
            entries = mapOf("hostPackageName" to "com.multiapp.app")
        )

        val updated = report
            .withOperationEvidence(
                EngineOperationEvidence(
                    component = "provider",
                    operation = "route-token",
                    verdict = EngineResultStatus.PASS,
                    entries = mapOf("routeTokenStatus" to "PRESENT_REDACTED")
                )
            )
            .withOperationEvidence(
                EngineOperationEvidence(
                    component = "native",
                    operation = "path-redirect",
                    verdict = EngineResultStatus.PARTIAL,
                    entries = mapOf(
                        "requestedPath" to "/data/data/com.test.app/lib/libfoo.so",
                        "redirectedPath" to "/data/data/com.multiapp.app/instances/instance-1/lib/libfoo.so"
                    )
                )
            )

        assertEquals("com.multiapp.app", updated.entries["hostPackageName"])
        assertEquals(EngineResultStatus.PARTIAL, updated.status)
        assertEquals(
            "PRESENT_REDACTED",
            updated.operationEntries("provider", "route-token").single().entries["routeTokenStatus"]
        )
        assertEquals(
            "/data/data/com.multiapp.app/instances/instance-1/lib/libfoo.so",
            updated.operationEntries("native", "path-redirect").single().entries["redirectedPath"]
        )
    }

    @Test
    fun `operation evidence verdicts merge by aggregation severity`() {
        val report = EngineEvidenceReport(
            instanceId = "instance-1",
            evidenceSessionId = "evidence-1",
            status = EngineResultStatus.PASS,
            profile = EngineProfile.BASELINE
        )

        val partial = report
            .withOperationEvidence(evidence(verdict = EngineResultStatus.PASS))
            .withOperationEvidence(evidence(verdict = EngineResultStatus.PARTIAL))

        val unsupported = partial
            .withOperationEvidence(evidence(verdict = EngineResultStatus.PASS))
            .withOperationEvidence(evidence(verdict = EngineResultStatus.UNSUPPORTED))

        val failed = unsupported
            .withOperationEvidence(evidence(verdict = EngineResultStatus.FAIL))
            .withOperationEvidence(evidence(verdict = EngineResultStatus.UNSUPPORTED))

        assertEquals(EngineResultStatus.PARTIAL, partial.status)
        assertEquals(EngineResultStatus.UNSUPPORTED, unsupported.status)
        assertEquals(EngineResultStatus.FAIL, failed.status)
    }

    @Test
    fun `subsystem verdicts contribute to report status`() {
        val report = EngineEvidenceReport(
            instanceId = "instance-1",
            evidenceSessionId = "evidence-1",
            status = EngineResultStatus.PASS,
            profile = EngineProfile.BASELINE
        )

        val updated = report
            .withSubsystemVerdict(EngineSubsystem.RUNTIME, EngineResultStatus.PASS)
            .withSubsystemVerdict(EngineSubsystem.BROADCAST, EngineResultStatus.UNSUPPORTED)

        assertEquals(EngineResultStatus.UNSUPPORTED, updated.status)
        assertEquals(EngineResultStatus.PASS, updated.subsystemVerdicts[EngineSubsystem.RUNTIME])
        assertEquals(EngineResultStatus.UNSUPPORTED, updated.subsystemVerdicts[EngineSubsystem.BROADCAST])
    }

    @Test
    fun `flattened operation evidence is deterministic for exporters`() {
        val report = EngineEvidenceReport(
            instanceId = "instance-1",
            evidenceSessionId = "evidence-1",
            status = EngineResultStatus.PASS,
            profile = EngineProfile.BASELINE,
            entries = mapOf("hostPackageName" to "com.multiapp.app"),
            operationEvidence = hashMapOf(
                "provider" to hashMapOf(
                    "route-token" to listOf(
                        EngineOperationEvidence(
                            component = "provider",
                            operation = "route-token",
                            verdict = EngineResultStatus.PASS,
                            entries = hashMapOf("z" to "last", "a" to "first")
                        ),
                        EngineOperationEvidence(
                            component = "provider",
                            operation = "route-token",
                            verdict = EngineResultStatus.PASS,
                            entries = mapOf("attempt" to "2")
                        )
                    ),
                    "query" to listOf(
                        EngineOperationEvidence(
                            component = "provider",
                            operation = "query",
                            verdict = EngineResultStatus.PASS,
                            entries = mapOf("attempt" to "1")
                        )
                    )
                ),
                "native" to hashMapOf(
                    "path-redirect" to listOf(
                        EngineOperationEvidence(
                            component = "native",
                            operation = "path-redirect",
                            verdict = EngineResultStatus.PARTIAL,
                            entries = mapOf("attempt" to "1")
                        )
                    )
                )
            )
        )

        val firstRead = report.flattenedOperationEvidence()
        val secondRead = report.flattenedOperationEvidence()

        assertEquals(firstRead, secondRead)
        assertEquals(
            listOf(
                "native:path-redirect:1",
                "provider:query:1",
                "provider:route-token:first",
                "provider:route-token:2"
            ),
            firstRead.map { evidence ->
                "${evidence.component}:${evidence.operation}:${evidence.entries["attempt"] ?: evidence.entries["a"]}"
            }
        )
        assertEquals(listOf("a", "z"), firstRead[2].entries.keys.toList())
        assertEquals("com.multiapp.app", report.entries["hostPackageName"])
    }

    private fun evidence(verdict: EngineResultStatus) = EngineOperationEvidence(
        component = "provider",
        operation = "route-token",
        verdict = verdict
    )
}

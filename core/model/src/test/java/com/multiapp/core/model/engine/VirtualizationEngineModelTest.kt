package com.multiapp.core.model.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualizationEngineModelTest {

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

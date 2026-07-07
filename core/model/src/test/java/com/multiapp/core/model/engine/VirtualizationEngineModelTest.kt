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

    private fun evidence(verdict: EngineResultStatus) = EngineOperationEvidence(
        component = "provider",
        operation = "route-token",
        verdict = verdict
    )
}

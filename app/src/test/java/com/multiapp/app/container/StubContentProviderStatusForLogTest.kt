package com.multiapp.app.container

import com.multiapp.core.engine.EngineProviderDispatchResult
import com.multiapp.core.engine.EngineProviderEvidence
import com.multiapp.core.engine.EngineProviderOperation
import com.multiapp.core.engine.EngineProviderPolicy
import com.multiapp.core.engine.EngineProviderResolution
import com.multiapp.core.engine.statusForLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class StubContentProviderStatusForLogTest {

    @Test
    fun `provider status for log omits instance and authority identifiers`() {
        val results = listOf(
            EngineProviderDispatchResult.RuntimeNotBound(
                resolution = resolution(),
                evidence = evidence(reason = "RUNTIME_NOT_BOUND")
            ) to "RUNTIME_NOT_BOUND:com.test.minimal.ProbeProvider",
            EngineProviderDispatchResult.InstanceNotFound("inst-001") to "INSTANCE_NOT_FOUND",
            EngineProviderDispatchResult.ProviderNotFound(
                instanceId = "inst-001",
                guestAuthority = "missing.authority",
                evidence = evidence(reason = "PROVIDER_NOT_FOUND")
            ) to "PROVIDER_NOT_FOUND"
        )

        results.forEach { (result, expected) ->
            val status = result.statusForLog()

            assertEquals(expected, status)
            assertFalse(status.contains("inst-001"), "status leaked instance id: $status")
            assertFalse(status.contains("missing.authority"), "status leaked guest authority: $status")
            assertFalse(status.contains("com.test.minimal.probe"), "status leaked guest authority: $status")
        }
    }

    private fun resolution(): EngineProviderResolution = EngineProviderResolution(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.inst001",
        guestAuthority = "com.test.minimal.probe",
        proxyAuthority = "com.multiapp.app.multiapp.provider.stub",
        providerClassName = "com.test.minimal.ProbeProvider",
        policy = policy()
    )

    private fun evidence(reason: String): EngineProviderEvidence = EngineProviderEvidence(
        instanceId = "inst-001",
        guestAuthority = "com.test.minimal.probe",
        proxyAuthority = "com.multiapp.app.multiapp.provider.stub",
        providerClassName = "com.test.minimal.ProbeProvider",
        operation = EngineProviderOperation.QUERY,
        success = false,
        reason = reason,
        policy = policy()
    )

    private fun policy(): EngineProviderPolicy = EngineProviderPolicy(
        exported = false,
        permission = null,
        grantUriPermissions = false,
        status = "INTERNAL_ONLY",
        reason = "NOT_EXPORTED",
        routingScope = "INSTANCE",
        processWideProviderHook = false,
        authorityRewriteEntry = "VirtualContentResolver"
    )
}

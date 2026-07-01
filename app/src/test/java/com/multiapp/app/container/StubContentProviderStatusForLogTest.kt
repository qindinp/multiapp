package com.multiapp.app.container

import android.content.pm.ProviderInfo
import com.multiapp.core.loader.VirtualProviderDispatchResult
import com.multiapp.core.loader.VirtualProviderEvidence
import com.multiapp.core.loader.VirtualProviderResolution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class StubContentProviderStatusForLogTest {

    @Test
    fun `provider status for log omits instance and authority identifiers`() {
        val provider = StubContentProvider()
        val results = listOf(
            VirtualProviderDispatchResult.RuntimeNotBound(
                resolution = resolution(),
                evidence = evidence(reason = "RUNTIME_NOT_BOUND")
            ) to "RUNTIME_NOT_BOUND:com.test.minimal.ProbeProvider",
            VirtualProviderDispatchResult.InstanceNotFound("inst-001") to "INSTANCE_NOT_FOUND",
            VirtualProviderDispatchResult.ProviderNotFound(
                instanceId = "inst-001",
                guestAuthority = "missing.authority",
                evidence = evidence(reason = "PROVIDER_NOT_FOUND")
            ) to "PROVIDER_NOT_FOUND"
        )

        results.forEach { (result, expected) ->
            val status = provider.statusForLogByReflection(result)

            assertEquals(expected, status)
            assertFalse(status.contains("inst-001"), "status leaked instance id: $status")
            assertFalse(status.contains("missing.authority"), "status leaked guest authority: $status")
            assertFalse(status.contains("com.test.minimal.probe"), "status leaked guest authority: $status")
        }
    }

    private fun StubContentProvider.statusForLogByReflection(result: VirtualProviderDispatchResult): String {
        val method = StubContentProvider::class.java.getDeclaredMethod(
            "statusForLog",
            VirtualProviderDispatchResult::class.java
        )
        method.isAccessible = true
        return method.invoke(this, result) as String
    }

    private fun resolution(): VirtualProviderResolution = VirtualProviderResolution(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.inst001",
        guestAuthority = "com.test.minimal.probe",
        proxyAuthority = "com.multiapp.app.multiapp.provider.stub",
        providerClassName = "com.test.minimal.ProbeProvider",
        providerInfo = ProviderInfo()
    )

    private fun evidence(reason: String): VirtualProviderEvidence = VirtualProviderEvidence(
        instanceId = "inst-001",
        guestAuthority = "com.test.minimal.probe",
        proxyAuthority = "com.multiapp.app.multiapp.provider.stub",
        providerClassName = "com.test.minimal.ProbeProvider",
        operation = VirtualProviderEvidence.Operation.QUERY,
        success = false,
        reason = reason
    )
}

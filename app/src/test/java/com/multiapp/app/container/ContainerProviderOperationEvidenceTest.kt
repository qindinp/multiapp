package com.multiapp.app.container

import com.multiapp.core.engine.EngineProviderOperationCapability
import com.multiapp.core.engine.EngineProviderOperationEvidenceFacade
import com.multiapp.core.loader.BootstrapEvidence
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.BootstrapStatus
import com.multiapp.core.loader.BootstrapSummary
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContainerProviderOperationEvidenceTest {

    @Test
    fun `provider capability operations mirror bootstrap status evidence`() {
        val provider = ResolvedComponent(
            name = "com.test.minimal.ProbeProvider",
            exported = false,
            authorities = listOf("com.test.minimal.probe"),
            permission = "com.test.minimal.permission.PROBE_PROVIDER",
            grantUriPermissions = true
        )
        val result = hostedResult(provider)
        val operation = EngineProviderOperationCapability(
            operationName = "notifyChange",
            operationStatusKey = "providerOperationNotifyChangeStatus"
        )

        val fields = EngineProviderOperationEvidenceFacade.fieldsForCapabilityOperation(result, provider, operation)

        assertEquals("ROUTED_BY_CONTENT_RESOLVER_HOOK", fields["status"])
        assertEquals("PROVIDER_PROXY", fields["stage"])
        assertEquals("notifyChange", fields["operationName"])
        assertEquals("inst-001", fields["instanceId"])
        assertEquals("com.test.minimal", fields["originPackageName"])
        assertEquals("com.multiapp.instance.001", fields["virtualPackageName"])
        assertEquals("com.test.minimal.probe", fields["guestAuthority"])
        assertEquals("com.test.minimal.ProbeProvider", fields["providerClassName"])
        assertEquals("true", fields["providerRoutingEnabled"])
        assertEquals("INSTANCE", fields["providerRoutingScope"])
        assertEquals(true, fields["evidenceSuccess"])
        assertEquals("ROUTED_BY_CONTENT_RESOLVER_HOOK", fields["providerOperationNotifyChangeStatus"])
        assertEquals(false, fields["hostFallback"])
        assertEquals("ROUTED_BY_CONTENT_RESOLVER_HOOK", fields["capabilityVerdict"])

        val entry = EngineProviderOperationEvidenceFacade.capabilityEvidenceFromBootstrapResult(result)
            .entries
            .single { it.operationName == "notifyChange" }
        assertEquals("provider-notify-change", entry.component)
        assertEquals(fields, entry.fields)
    }

    private fun hostedResult(provider: ResolvedComponent): HostedBootstrapResult = HostedBootstrapResult(
        instanceId = "inst-001",
        installId = "com.test.minimal",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.001",
        applicationLabel = "MinimalTest",
        originApkPath = "/data/app/minimal.apk",
        dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        guestClassLoader = null,
        guestApplication = null,
        installRecord = null,
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            virtualPackageName = "com.multiapp.instance.001",
            applicationLabel = "MinimalTest",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 23,
            sourceDir = "/data/app/minimal.apk",
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            providers = listOf(provider)
        ),
        launcherActivityClassName = null,
        stageResults = listOf(
            BootstrapResult(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                status = BootstrapStatus.SUCCESS,
                message = "Provider routing prepared",
                evidence = listOf(
                    BootstrapEvidence("providerRoutingEnabled", "true"),
                    BootstrapEvidence("providerRoutingScope", "INSTANCE"),
                    BootstrapEvidence("providerRoutingPrimary", "ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY"),
                    BootstrapEvidence("providerRoutingFallback", "NONE"),
                    BootstrapEvidence("providerOperationNotifyChangeStatus", "ROUTED_BY_CONTENT_RESOLVER_HOOK")
                )
            )
        ),
        summary = BootstrapSummary(
            totalTimeMs = 0L,
            stageResults = emptyList(),
            overallStatus = BootstrapStatus.SUCCESS,
            failedStage = null,
            failureReason = null
        ),
        success = true
    )
}

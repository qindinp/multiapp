package com.multiapp.core.engine

import android.app.Application
import com.multiapp.core.loader.BootstrapEvidence
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.loader.toSummary
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EngineProcessBootstrapReadinessTest {
    @Test
    fun `LoadedApk fallback cannot enter READY state`() {
        val stages = listOf(
            BootstrapResult.success(
                stage = RuntimeStage.APPLICATION,
                message = "fallback Application created",
                evidence = listOf(
                    BootstrapEvidence("loadedApkApplicationCreatorStatus", "FALLBACK"),
                    BootstrapEvidence("providerPreinstallStatus", "PASS")
                )
            ),
            BootstrapResult.success(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "PMS proxy installed"
            )
        )
        val loaderResult = HostedBootstrapResult(
            instanceId = INSTANCE_ID,
            installId = ORIGIN_PACKAGE,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            processSlot = PROCESS_SLOT,
            originApkPath = "/tmp/base.apk",
            dataRoot = "/tmp/instance",
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            guestApplication = mockk<Application>(relaxed = true),
            installRecord = null,
            packageSnapshot = null,
            launcherActivityClassName = GUEST_ACTIVITY,
            stageResults = stages,
            summary = stages.toSummary(),
            success = true,
            diagnostics = null
        )
        val request = EngineProcessBootstrapRequest(
            runtime = runtime(),
            providerRoutingEnabled = true,
            legacyProviderHookEnabled = false,
            evidenceMode = EngineEvidenceMode.DEFAULT
        )

        val result = EngineProcessBootstrapReadiness.evaluate(
            request = request,
            result = EngineHostedBootstrapResult.fromLoader(loaderResult),
            processId = 4100,
            processName = PROCESS_SLOT,
            cached = false,
            durationMs = 10L
        )

        assertFalse(result.ready)
        assertEquals(EngineProcessBootstrapState.FAILED, result.state)
        assertEquals(EngineResultStatus.FAIL, result.verdict)
        assertEquals("FALLBACK", result.applicationStatus)
    }

    private fun runtime(): VirtualInstanceRuntime {
        val snapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "Example",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            sourceDir = "/tmp/base.apk",
            dataDir = "/tmp/instance",
            launcherActivityName = GUEST_ACTIVITY
        )
        return VirtualInstanceRuntime(
            instanceId = INSTANCE_ID,
            hostPackageName = "com.multiapp.app",
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            dataRoot = snapshot.dataDir,
            packageSnapshot = snapshot,
            profile = EngineProfile.BASELINE,
            processSlot = PROCESS_SLOT,
            proxySlot = "com.multiapp.app.container.ProxyActivity0",
            evidenceSessionId = "evidence-1",
            runtimeEpoch = 1L,
            engineSessionId = "engine-1"
        )
    }

    private companion object {
        const val INSTANCE_ID = "instance-1"
        const val ORIGIN_PACKAGE = "com.example.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.instance.example"
        const val PROCESS_SLOT = "com.multiapp.app:v0"
        const val GUEST_ACTIVITY = "com.example.app.MainActivity"
    }
}

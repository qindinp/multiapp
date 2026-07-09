package com.multiapp.core.engine

import com.multiapp.core.loader.BootstrapEvidence
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.loader.toSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineHostedBootstrapResultTest {

    @Test
    fun `stage results are exposed as engine DTOs`() {
        val loaderStage = BootstrapResult.success(
            stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
            message = "PMS proxy installed",
            evidence = listOf(
                BootstrapEvidence("globalPmsProxyEnabled", "true"),
                BootstrapEvidence("sPackageManagerPatched", "true")
            ),
            durationMs = 12L
        )
        val result = EngineHostedBootstrapResult.fromLoader(
            hostedResult(stageResults = listOf(loaderStage), success = true)
        )

        val stage = result.firstStageResult(EngineBootstrapStage.PACKAGE_MANAGER_PROXY)!!
        val fields = stage.toEvidenceFields()

        assertEquals(EngineBootstrapStage.PACKAGE_MANAGER_PROXY, stage.stage)
        assertEquals(EngineBootstrapStatus.SUCCESS, stage.status)
        assertTrue(stage.isSuccessful)
        assertFalse(stage.isTerminalFailure)
        assertEquals("PMS proxy installed", fields["message"])
        assertEquals("12", fields["durationMs"])
        assertEquals("true", fields["globalPmsProxyEnabled"])
        assertEquals("true", fields["sPackageManagerPatched"])
    }

    @Test
    fun `last stage lookup keeps launcher failure evidence`() {
        val earlyLauncherStage = BootstrapResult.success(
            stage = RuntimeStage.LAUNCHER_ACTIVITY,
            message = "old launcher"
        )
        val finalLauncherStage = BootstrapResult.failed(
            stage = RuntimeStage.LAUNCHER_ACTIVITY,
            message = "Launcher Activity class not loadable",
            evidence = listOf(BootstrapEvidence("resolver", "InstallRecordFallback"))
        )
        val result = EngineHostedBootstrapResult.fromLoader(
            hostedResult(
                stageResults = listOf(earlyLauncherStage, finalLauncherStage),
                success = false
            )
        )

        val stage = result.lastStageResult(EngineBootstrapStage.LAUNCHER_ACTIVITY)!!

        assertEquals(EngineBootstrapStatus.FAILED, stage.status)
        assertFalse(stage.isSuccessful)
        assertTrue(stage.isTerminalFailure)
        assertEquals("InstallRecordFallback", stage.evidence["resolver"])
    }

    private fun hostedResult(
        stageResults: List<BootstrapResult>,
        success: Boolean
    ): HostedBootstrapResult =
        HostedBootstrapResult(
            instanceId = "inst-001",
            installId = "com.example.app",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc123",
            originApkPath = "/tmp/base.apk",
            dataRoot = "/tmp/inst-001",
            guestClassLoader = if (success) ClassLoader.getSystemClassLoader() else null,
            guestApplication = null,
            installRecord = null,
            packageSnapshot = null,
            launcherActivityClassName = null,
            stageResults = stageResults,
            summary = stageResults.toSummary(),
            success = success,
            diagnostics = null
        )
}

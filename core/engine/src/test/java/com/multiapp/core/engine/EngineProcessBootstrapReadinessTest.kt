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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `component runtime can enter READY without launcher activity`() {
        val request = componentRequest()
        val loaderResult = hostedResult(
            processSlot = COMPONENT_PROCESS_SLOT,
            launcherActivityClassName = null,
            providerPreinstallStatus = "SKIPPED"
        )

        val result = EngineProcessBootstrapReadiness.evaluate(
            request = request,
            result = EngineHostedBootstrapResult.fromLoader(loaderResult),
            processId = 4201,
            processName = COMPONENT_PROCESS_SLOT,
            cached = true,
            durationMs = 4L
        )

        assertTrue(result.ready, result.message)
        assertEquals(EngineProcessBootstrapState.READY, result.state)
        assertEquals(null, result.launcherActivityClassName)
        assertEquals("COMPONENT_RUNTIME", result.evidence["bootstrapKind"])
        assertEquals("$ORIGIN_PACKAGE:remote", result.evidence["effectiveGuestProcessName"])
    }

    @Test
    fun `component runtime rejects partial Provider preinstall`() {
        val request = componentRequest()
        val loaderResult = hostedResult(
            processSlot = COMPONENT_PROCESS_SLOT,
            launcherActivityClassName = null,
            providerPreinstallStatus = "PARTIAL"
        )

        val result = EngineProcessBootstrapReadiness.evaluate(
            request = request,
            result = EngineHostedBootstrapResult.fromLoader(loaderResult),
            processId = 4201,
            processName = COMPONENT_PROCESS_SLOT,
            cached = false,
            durationMs = 4L
        )

        assertFalse(result.ready)
        assertEquals(EngineProcessBootstrapState.FAILED, result.state)
        assertEquals("PARTIAL", result.providerPreinstallStatus)
    }

    @Test
    fun `component request rejects launch ticket for another process slot`() {
        val ticket = componentTicket(processSlot = "com.multiapp.app:v7")

        assertFailsWith<IllegalArgumentException> {
            EngineProcessBootstrapRequest(
                runtime = runtime().copy(
                    processSlot = COMPONENT_PROCESS_SLOT,
                    processName = "$ORIGIN_PACKAGE:remote"
                ),
                providerRoutingEnabled = true,
                legacyProviderHookEnabled = false,
                evidenceMode = EngineEvidenceMode.DEFAULT,
                kind = EngineProcessBootstrapKind.COMPONENT_RUNTIME,
                componentLaunchTicket = ticket
            )
        }
    }

    private fun componentRequest(): EngineProcessBootstrapRequest {
        val ticket = componentTicket()
        return EngineProcessBootstrapRequest(
            runtime = runtime().copy(
                processSlot = ticket.processSlot,
                processName = ticket.effectiveGuestProcessName
            ),
            providerRoutingEnabled = true,
            legacyProviderHookEnabled = false,
            evidenceMode = EngineEvidenceMode.DEFAULT,
            kind = EngineProcessBootstrapKind.COMPONENT_RUNTIME,
            componentLaunchTicket = ticket
        )
    }

    private fun componentTicket(
        processSlot: String = COMPONENT_PROCESS_SLOT
    ) = EngineComponentProcessLaunchTicket(
        instanceId = INSTANCE_ID,
        effectiveGuestProcessName = "$ORIGIN_PACKAGE:remote",
        processSlot = processSlot,
        attachCapability = "a".repeat(64)
    )

    private fun hostedResult(
        processSlot: String,
        launcherActivityClassName: String?,
        providerPreinstallStatus: String
    ): HostedBootstrapResult {
        val stages = listOf(
            BootstrapResult.success(
                stage = RuntimeStage.APPLICATION,
                message = "LoadedApk Application created",
                evidence = listOf(
                    BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"),
                    BootstrapEvidence("providerPreinstallStatus", providerPreinstallStatus)
                )
            ),
            BootstrapResult.success(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "PMS proxy installed"
            )
        )
        return HostedBootstrapResult(
            instanceId = INSTANCE_ID,
            installId = ORIGIN_PACKAGE,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            processSlot = processSlot,
            originApkPath = "/tmp/base.apk",
            dataRoot = "/tmp/instance",
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            guestApplication = mockk<Application>(relaxed = true),
            installRecord = null,
            packageSnapshot = null,
            launcherActivityClassName = launcherActivityClassName,
            stageResults = stages,
            summary = stages.toSummary(),
            success = true,
            diagnostics = null
        )
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
        const val COMPONENT_PROCESS_SLOT = "com.multiapp.app:v1"
        const val GUEST_ACTIVITY = "com.example.app.MainActivity"
    }
}

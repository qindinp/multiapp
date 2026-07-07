package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualPackageManagerProxyStageTest {

    @Test
    fun `execute installs global proxy and records evidence`() {
        val snapshot = snapshot()
        val installResult = installResult(
            status = VirtualPackageManagerGlobalInstallStatus.INSTALLED,
            sPackageManagerRead = true,
            sPackageManagerPatched = true,
            applicationPatchResults = listOf(
                ActivityThreadPackageManagerPatchResult("hostContext.packageManager", patched = true)
            )
        )
        val stage = VirtualPackageManagerProxyStage(
            hostContext = null,
            installer = VirtualPackageManagerGlobalInstallAction { _, installedSnapshot, runtimeUid ->
                assertEquals(snapshot, installedSnapshot)
                assertEquals(RUNTIME_UID, runtimeUid)
                installResult
            },
            runtimeUidProvider = { RUNTIME_UID },
            clock = fixedClock(100L, 109L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot))

        assertEquals(RuntimeStage.PACKAGE_MANAGER_PROXY, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(9L, output.result.durationMs)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["globalPmsProxyEnabled"])
        assertEquals("true", evidence["sPackageManagerPatched"])
        assertEquals("1", evidence["applicationPackageManagerPatchedCount"])
        assertEquals("package,application,component,intent,permission,uid", evidence["virtualizedQueryFamilies"])
        assertEquals(snapshot.originPackageName, evidence["originPackageName"])
        assertEquals(snapshot.virtualPackageName, evidence["virtualPackageName"])
        assertTrue(!output.isTerminalFailure)
    }

    @Test
    fun `execute installs notification package proxy for origin and virtual package aliases`() {
        val snapshot = snapshot()
        val hostContext = mockk<Context>()
        var capturedSourcePackages = emptyList<String>()
        var capturedHostPackageName = ""
        every { hostContext.packageName } returns "com.multiapp.app"
        val stage = VirtualPackageManagerProxyStage(
            hostContext = hostContext,
            installer = VirtualPackageManagerGlobalInstallAction { _, _, _ ->
                installResult(
                    status = VirtualPackageManagerGlobalInstallStatus.INSTALLED,
                    sPackageManagerRead = true,
                    sPackageManagerPatched = true
                )
            },
            notificationPackageProxyInstaller = NotificationPackageProxyInstallAction { sourcePackages, hostPackageName ->
                capturedSourcePackages = sourcePackages.toList()
                capturedHostPackageName = hostPackageName
                true
            },
            runtimeUidProvider = { RUNTIME_UID },
            clock = fixedClock(100L, 109L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot))

        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(listOf("com.test.minimal", "com.multiapp.instance.abc"), capturedSourcePackages)
        assertEquals("com.multiapp.app", capturedHostPackageName)
        assertEquals("INSTALLED", evidence["notificationPackageProxyStatus"])
        assertEquals("com.test.minimal,com.multiapp.instance.abc", evidence["notificationPackageProxySourcePackages"])
        assertEquals("com.multiapp.app", evidence["notificationPackageProxyHostPackage"])
        assertEquals("guest-to-host-package-args", evidence["notificationPackageProxyMode"])
    }

    @Test
    fun `execute fails terminally when package snapshot is missing`() {
        val stage = VirtualPackageManagerProxyStage(
            hostContext = null,
            installer = VirtualPackageManagerGlobalInstallAction { _, _, _ -> error("installer should not be called") },
            runtimeUidProvider = { RUNTIME_UID },
            clock = fixedClock(200L, 203L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.PACKAGE_MANAGER_PROXY, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Package snapshot is required before package manager proxy install", output.result.message)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.packageSnapshot)
        assertTrue(output.isTerminalFailure)
    }

    @Test
    fun `execute reports degraded result without terminal failure when installer degrades`() {
        val snapshot = snapshot()
        val stage = VirtualPackageManagerProxyStage(
            hostContext = null,
            installer = VirtualPackageManagerGlobalInstallAction { _, _, _ ->
                installResult(
                    status = VirtualPackageManagerGlobalInstallStatus.DEGRADED,
                    sPackageManagerRead = false,
                    sPackageManagerPatched = false,
                    degradedReasons = listOf("S_PACKAGE_MANAGER_NULL")
                )
            },
            runtimeUidProvider = { RUNTIME_UID },
            clock = fixedClock(300L, 304L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot))

        assertEquals(RuntimeStage.PACKAGE_MANAGER_PROXY, output.result.stage)
        assertEquals(BootstrapStatus.DEGRADED, output.result.status)
        assertEquals(4L, output.result.durationMs)
        assertTrue(!output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("false", evidence["globalPmsProxyEnabled"])
        assertTrue(evidence.getValue("degradedReasons").contains("S_PACKAGE_MANAGER_NULL"))
    }

    private fun installResult(
        status: VirtualPackageManagerGlobalInstallStatus,
        sPackageManagerRead: Boolean,
        sPackageManagerPatched: Boolean,
        applicationPatchResults: List<ActivityThreadPackageManagerPatchResult> = emptyList(),
        degradedReasons: List<String> = emptyList()
    ) = VirtualPackageManagerGlobalInstallResult(
        status = status,
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        runtimeUid = RUNTIME_UID,
        sPackageManagerRead = sPackageManagerRead,
        sPackageManagerPatched = sPackageManagerPatched,
        ipackageManagerInterface = "fake.IPackageManager",
        originalPackageManagerClass = "fake.Pms",
        proxyClass = "fake.Proxy",
        applicationPackageManagerPatchResults = applicationPatchResults,
        degradedReasons = degradedReasons
    )

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = "/data/inst"
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return { values.getOrElse(index++) { values.last() } }
    }

    private companion object {
        const val RUNTIME_UID = 42420
    }
}

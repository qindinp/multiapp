package com.multiapp.core.loader

import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherActivityStageTest {

    @Test
    fun `execute resolves launcher from resolved package before install record fallback`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { "java.lang.Integer" },
            clock = fixedClock(100L, 106L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(launcherActivityName = "java.lang.String")
            )
        )

        assertEquals(RuntimeStage.LAUNCHER_ACTIVITY, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(6L, output.result.durationMs)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("java.lang.String", evidence["launcherActivityClass"])
        assertEquals("VirtualPackageResolver", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute resolves launcher from package resolver before install record fallback`() {
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                assertEquals("/artifact/com.example.app.apk", apkPath)
                return resolvedPackage(launcherActivityName = "java.lang.StringBuilder")
            }
        }
        val stage = LauncherActivityStage(
            packageResolver = packageResolver,
            launcherActivityResolver = { "java.lang.Integer" },
            clock = fixedClock(200L, 207L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.StringBuilder", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("VirtualPackageResolver", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
    }

    @Test
    fun `execute falls back to install record resolver`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { "java.lang.Integer" },
            clock = fixedClock(300L, 309L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.Integer", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("InstallRecord", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
    }

    @Test
    fun `execute fails non terminal when launcher class is not loadable`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { "com.example.DoesNotExist" },
            clock = fixedClock(400L, 413L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.LAUNCHER_ACTIVITY, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Launcher Activity class not loadable: com.example.DoesNotExist", output.result.message)
        assertEquals(13L, output.result.durationMs)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.example.DoesNotExist", evidence["launcherActivityClass"])
        assertEquals("false", evidence["loadable"])
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute skips non terminal when no launcher can be resolved`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(500L, 503L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.LAUNCHER_ACTIVITY, output.result.stage)
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals("No launcher Activity resolved from manifest or InstallRecord", output.result.message)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.launcherActivityClassName)
        assertFalse(output.isTerminalFailure)
    }

    private fun stageInput(
        resolvedPackage: ResolvedPackage? = null,
        installRecord: InstallRecord = installRecord()
    ) = BootstrapStageInput(
        instanceId = "inst-001",
        installRecord = installRecord,
        originApkPath = installRecord.originApkPath,
        resolvedPackage = resolvedPackage,
        guestClassLoader = ClassLoader.getSystemClassLoader()
    )

    private fun installRecord() = InstallRecord(
        packageName = "com.example.app",
        originApkPath = "/artifact/com.example.app.apk",
        originApkSha256 = "sha256",
        originCertSha256 = "cert-sha256",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        installTimeMs = 500L
    )

    private fun resolvedPackage(launcherActivityName: String?) = ResolvedPackage(
        packageName = "com.example.app",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        launcherActivityName = launcherActivityName
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

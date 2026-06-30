package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackageSnapshotStageTest {

    @Test
    fun `execute creates and registers package snapshot from explicit stage context`() {
        val registry = VirtualPackageRegistry()
        val instance = instanceRecord()
        val installRecord = installRecord()
        val resolvedPackage = resolvedPackage()
        val stage = PackageSnapshotStage(
            packageMetadataResolver = { path ->
                assertEquals(installRecord.originApkPath, path)
                resolvedPackage
            },
            packageRegistry = registry,
            clock = fixedClock(100L, 111L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath,
                nativeLibraryDir = "/data/instances/inst-001/lib"
            )
        )

        assertEquals(RuntimeStage.RESOURCES, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(11L, output.result.durationMs)
        val snapshot = assertNotNull(output.context.packageSnapshot)
        assertSame(resolvedPackage, output.context.resolvedPackage)
        assertSame(snapshot, registry.getByInstanceId(instance.instanceId))
        assertEquals(instance.originPackageName, snapshot.originPackageName)
        assertEquals(instance.virtualPackageName, snapshot.virtualPackageName)
        assertEquals("Resolved Label", snapshot.applicationLabel)
        assertEquals("/data/instances/inst-001/lib", snapshot.nativeLibraryDir)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(instance.instanceId, evidence["instanceId"])
        assertEquals(instance.originPackageName, evidence["originPackageName"])
        assertEquals(instance.virtualPackageName, evidence["virtualPackageName"])
        assertEquals(installRecord.originApkPath, evidence["sourceDir"])
        assertEquals(instance.dataRoot, evidence["dataDir"])
        assertEquals("/data/instances/inst-001/lib", evidence["nativeLibraryDir"])
        assertEquals("1", evidence["providerCount"])
        assertEquals("1", evidence["activityCount"])
    }

    @Test
    fun `execute preserves null nativeLibraryDir when no native dir was resolved`() {
        val registry = VirtualPackageRegistry()
        val instance = instanceRecord()
        val installRecord = installRecord()
        val stage = PackageSnapshotStage(
            packageMetadataResolver = { null },
            packageRegistry = registry,
            clock = fixedClock(200L, 204L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath,
                nativeLibraryDir = null
            )
        )

        val snapshot = assertNotNull(output.context.packageSnapshot)
        assertNull(output.context.resolvedPackage)
        assertNull(snapshot.nativeLibraryDir)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
    }

    @Test
    fun `execute fails terminally when install record is missing`() {
        val instance = instanceRecord()
        val stage = PackageSnapshotStage(
            packageMetadataResolver = { null },
            packageRegistry = VirtualPackageRegistry(),
            clock = fixedClock(300L, 303L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                originApkPath = "/artifact/app.apk"
            )
        )

        assertEquals(RuntimeStage.RESOURCES, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Install record is required before package snapshot", output.result.message)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.packageSnapshot)
        assertTrue(output.isTerminalFailure)
    }

    private fun instanceRecord() = VirtualInstanceRecord(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = "/data/instances/inst-001",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
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

    private fun resolvedPackage() = ResolvedPackage(
        packageName = "com.example.app",
        versionCode = 2L,
        versionName = "2.0",
        targetSdk = 36,
        minSdk = 28,
        applicationLabel = "Resolved Label",
        launcherActivityName = "com.example.app.MainActivity",
        activities = listOf(ResolvedComponent(name = "com.example.app.MainActivity", exported = true)),
        providers = listOf(
            ResolvedComponent(
                name = "com.example.app.Provider",
                exported = false,
                authorities = listOf("com.example.app.provider")
            )
        )
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedPackage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BootstrapStageContractTest {

    @Test
    fun `failed BootstrapResult makes stage output terminal`() {
        val context = BootstrapStageInput(instanceId = "inst-001")
        val output = BootstrapStageOutput(
            context = context,
            result = BootstrapResult.failed(
                stage = RuntimeStage.CONFIG,
                message = "Instance not found: inst-001"
            )
        )

        assertTrue(output.isTerminalFailure)
        assertSame(context, output.context)
        assertEquals(RuntimeStage.CONFIG, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Instance not found: inst-001", output.result.message)
    }

    @Test
    fun `successful BootstrapResult keeps stage output non terminal`() {
        val output = BootstrapStageOutput(
            context = BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk"
            ),
            result = BootstrapResult.success(
                stage = RuntimeStage.ORIGIN_APK,
                message = "Origin APK resolved",
                evidence = listOf(BootstrapEvidence("originApkPath", "/artifact/app.apk"))
            )
        )

        assertFalse(output.isTerminalFailure)
        assertEquals(RuntimeStage.ORIGIN_APK, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("/artifact/app.apk", output.context.originApkPath)
    }

    @Test
    fun `stage context carries explicit bootstrap facts between stages`() {
        val instance = instanceRecord()
        val installRecord = installRecord()
        val classLoader = ClassLoader.getSystemClassLoader()

        val context = BootstrapStageInput(
            instanceId = "inst-001",
            instance = instance,
            installRecord = installRecord,
            originApkPath = installRecord.originApkPath,
            nativeLibraryDir = "/data/instances/inst-001/lib",
            guestClassLoader = classLoader
        )

        assertEquals("inst-001", context.instanceId)
        assertSame(instance, context.instance)
        assertSame(installRecord, context.installRecord)
        assertEquals("/artifact/com.example.app.apk", context.originApkPath)
        assertEquals("/data/instances/inst-001/lib", context.nativeLibraryDir)
        assertSame(classLoader, context.guestClassLoader)
    }

    @Test
    fun `stage context carries resolved package metadata between stages`() {
        val resolvedPackage = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 2L,
            versionName = "2.0",
            targetSdk = 36,
            minSdk = 28,
            applicationLabel = "Resolved Label",
            launcherActivityName = "com.example.app.MainActivity"
        )

        val context = BootstrapStageInput(
            instanceId = "inst-001",
            resolvedPackage = resolvedPackage
        )

        assertSame(resolvedPackage, context.resolvedPackage)
        assertEquals("Resolved Label", context.resolvedPackage?.applicationLabel)
        assertEquals("com.example.app.MainActivity", context.resolvedPackage?.launcherActivityName)
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
}

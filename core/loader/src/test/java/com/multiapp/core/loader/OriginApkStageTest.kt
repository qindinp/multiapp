package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OriginApkStageTest {

    @Test
    fun `execute resolves existing origin APK path from install record`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "origin.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val instance = instanceRecord()
        val installRecord = installRecord(originApkPath = apkFile.absolutePath)
        val stage = OriginApkStage(clock = fixedClock(100L, 109L))

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                installRecord = installRecord
            )
        )

        assertEquals(RuntimeStage.ORIGIN_APK, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(9L, output.result.durationMs)
        assertSame(installRecord, output.context.installRecord)
        assertEquals(apkFile.absolutePath, output.context.originApkPath)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(apkFile.absolutePath, evidence["originApkPath"])
    }

    @Test
    fun `execute fails terminally when install record is missing`() {
        val instance = instanceRecord()
        val stage = OriginApkStage(clock = fixedClock(200L, 202L))

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.ORIGIN_APK, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Install record is required before resolving origin APK", output.result.message)
        assertEquals(2L, output.result.durationMs)
        assertNull(output.context.originApkPath)
        assertTrue(output.isTerminalFailure)
    }

    @Test
    fun `execute fails terminally when origin APK does not exist`() {
        val instance = instanceRecord()
        val installRecord = installRecord(originApkPath = "/nonexistent/path.apk")
        val stage = OriginApkStage(clock = fixedClock(300L, 307L))

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                installRecord = installRecord
            )
        )

        assertEquals(RuntimeStage.ORIGIN_APK, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Origin APK not found: /nonexistent/path.apk", output.result.message)
        assertEquals(7L, output.result.durationMs)
        assertNull(output.context.originApkPath)
        assertTrue(output.isTerminalFailure)
    }

    private fun instanceRecord(
        instanceId: String = "inst-001",
        originPackageName: String = "com.example.app"
    ) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = "/data/instances/$instanceId",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun installRecord(
        packageName: String = "com.example.app",
        originApkPath: String = "/data/apks/example.apk"
    ) = InstallRecord(
        packageName = packageName,
        originApkPath = originApkPath,
        originApkSha256 = "abc123",
        originCertSha256 = "def456",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        installTimeMs = 500L
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

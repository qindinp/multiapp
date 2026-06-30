package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallRecordStageTest {

    private class FakeInstallRecordStore(
        private val records: Map<String, InstallRecord> = emptyMap()
    ) : InstallRecordStore {
        override fun save(record: InstallRecord): Result<String> = Result.failure(UnsupportedOperationException())
        override fun load(packageName: String): InstallRecord? = records[packageName]
        override fun listAll(): List<InstallRecord> = records.values.toList()
        override fun delete(packageName: String): Boolean = false
    }

    @Test
    fun `execute loads install record using origin package name from config context`() {
        val instance = instanceRecord(originPackageName = "com.example.factsource")
        val installRecord = installRecord(packageName = "com.example.factsource")
        val stage = InstallRecordStage(
            installRecordStore = FakeInstallRecordStore(mapOf(installRecord.packageName to installRecord)),
            clock = fixedClock(100L, 112L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.PACKAGE_METADATA, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(12L, output.result.durationMs)
        assertSame(instance, output.context.instance)
        assertSame(installRecord, output.context.installRecord)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(installRecord.packageName, evidence["packageName"])
        assertEquals(installRecord.versionName, evidence["versionName"])
    }

    @Test
    fun `execute fails terminally when config context has no instance`() {
        val stage = InstallRecordStage(
            installRecordStore = FakeInstallRecordStore(),
            clock = fixedClock(200L, 201L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.PACKAGE_METADATA, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Instance is required before loading install record", output.result.message)
        assertEquals(1L, output.result.durationMs)
        assertNull(output.context.installRecord)
        assertTrue(output.isTerminalFailure)
    }

    @Test
    fun `execute fails terminally when install record is missing`() {
        val instance = instanceRecord(originPackageName = "com.example.missing")
        val stage = InstallRecordStage(
            installRecordStore = FakeInstallRecordStore(),
            clock = fixedClock(300L, 305L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.PACKAGE_METADATA, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Install record not found: com.example.missing", output.result.message)
        assertEquals(5L, output.result.durationMs)
        assertNull(output.context.installRecord)
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

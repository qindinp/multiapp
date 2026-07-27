package com.multiapp.core.engine

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ImportResult
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultVirtualizationEngineRefreshTest {

    @Test
    fun `same version content change is rejected before stopping instances`(@TempDir tempDir: File) {
        val oldApk = apk(tempDir, "old.apk", "old generation")
        val changedApk = apk(tempDir, "changed.apk", "changed generation")
        val events = mutableListOf<String>()
        val instances = listOf(instance("instance-a"))
        val installs = RecordingRefreshInstallService(record(oldApk), events)
        val engine = engine(
            instanceManager = instanceManager(instances),
            installs = installs,
            events = events
        )

        val result = engine.refreshPackage(request(changedApk, versionCode = 1L, versionName = "1.0"))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("same_version_content_changed", result.message)
        assertEquals(emptyList(), events)
        assertEquals(0, installs.refreshCount)
    }

    @Test
    fun `refresh stops every package instance before replacing generation and clears slots`(@TempDir tempDir: File) {
        val oldApk = apk(tempDir, "old.apk", "old generation")
        val updatedApk = apk(tempDir, "updated.apk", "updated generation")
        val events = mutableListOf<String>()
        val instances = listOf(instance("instance-b"), instance("instance-a"))
        val installs = RecordingRefreshInstallService(record(oldApk), events)
        val slots = InMemoryEngineRuntimeSlotStore().apply {
            assign("instance-a", ORIGIN_PACKAGE, listOf("host:v0"), listOf("host.Proxy0"), 1L)
            assign("instance-b", ORIGIN_PACKAGE, listOf("host:v1"), listOf("host.Proxy1"), 1L)
        }
        val engine = engine(
            instanceManager = instanceManager(instances),
            installs = installs,
            events = events,
            slotStore = slots,
            processTerminator = EngineProcessTerminator { instanceId, _, processId ->
                EngineProcessTerminationResult(true, "TERMINATED", processId, instanceId)
            }
        )

        val result = engine.refreshPackage(request(updatedApk, versionCode = 2L, versionName = "2.0"))

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(listOf("stop:instance-a", "stop:instance-b", "refresh"), events)
        assertEquals(1, installs.refreshCount)
        assertEquals(2L, installs.getInstallRecord(ORIGIN_PACKAGE)?.versionCode)
        assertNull(slots.get("instance-a"))
        assertNull(slots.get("instance-b"))
    }

    @Test
    fun `refresh aborts without import when instance termination is unconfirmed`(@TempDir tempDir: File) {
        val oldApk = apk(tempDir, "old.apk", "old generation")
        val updatedApk = apk(tempDir, "updated.apk", "updated generation")
        val events = mutableListOf<String>()
        val instances = listOf(instance("instance-a"))
        val installs = RecordingRefreshInstallService(record(oldApk), events)
        val slots = InMemoryEngineRuntimeSlotStore().apply {
            assign("instance-a", ORIGIN_PACKAGE, listOf("host:v0"), listOf("host.Proxy0"), 1L)
        }
        val engine = engine(
            instanceManager = instanceManager(instances),
            installs = installs,
            events = events,
            slotStore = slots,
            processTerminator = EngineProcessTerminator { _, _, processId ->
                EngineProcessTerminationResult(false, "TIMEOUT", processId, "still_alive")
            }
        )

        val result = engine.refreshPackage(request(updatedApk, versionCode = 2L, versionName = "2.0"))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals(0, installs.refreshCount)
        assertEquals(emptyList(), events)
        assertEquals(1L, installs.getInstallRecord(ORIGIN_PACKAGE)?.versionCode)
    }

    private fun engine(
        instanceManager: InstanceManager,
        installs: VirtualInstallService,
        events: MutableList<String>,
        slotStore: EngineRuntimeSlotStore = InMemoryEngineRuntimeSlotStore(),
        processTerminator: EngineProcessTerminator = EngineProcessTerminator.TEST_NO_OP
    ) = DefaultVirtualizationEngineCore(
        hostPackageName = "com.multiapp.app",
        instanceManager = instanceManager,
        virtualInstallService = installs,
        activityLauncher = EngineActivityLauncher { },
        processTerminator = processTerminator,
        slotStore = slotStore,
        ephemeralInstanceCleanup = { instanceId -> events += "stop:$instanceId" }
    )

    private fun instanceManager(instances: List<VirtualInstanceRecord>): InstanceManager =
        mockk<InstanceManager>().also { manager ->
            every { manager.getInstanceByOrigin(ORIGIN_PACKAGE) } returns instances
        }

    private fun instance(instanceId: String) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = "com.multiapp.instance.$instanceId",
        displayName = instanceId,
        dataRoot = File("build/tmp/$instanceId").absolutePath,
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        state = InstanceState.READY
    )

    private fun record(apk: File) = InstallRecord(
        packageName = ORIGIN_PACKAGE,
        originApkPath = apk.absolutePath,
        originApkSha256 = sha256(apk),
        originCertSha256 = "stable-signer",
        signerSha256Digests = listOf("stable-signer"),
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        packageLabel = "Test",
        installTimeMs = 1L
    )

    private fun request(apk: File, versionCode: Long, versionName: String) =
        EnginePackageInstallRequest(
            originPackageName = ORIGIN_PACKAGE,
            originApkPath = apk.absolutePath,
            versionCode = versionCode,
            versionName = versionName,
            targetSdk = 36,
            minSdk = 28,
            packageLabel = "Test"
        )

    private fun apk(root: File, name: String, content: String): File =
        File(root, name).apply { writeText(content) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

    private class RecordingRefreshInstallService(
        initialRecord: InstallRecord,
        private val events: MutableList<String>
    ) : VirtualInstallService {
        private var record: InstallRecord = initialRecord
        var refreshCount: Int = 0
            private set

        override suspend fun importFromInstalledPackage(packageName: String): Result<ImportResult> =
            Result.failure(UnsupportedOperationException())

        override fun importFromMetadata(
            packageName: String,
            originApkPath: String,
            versionCode: Long,
            versionName: String,
            targetSdk: Int,
            minSdk: Int,
            applicationClassName: String?,
            packageLabel: String?
        ): Result<ImportResult> = Result.failure(UnsupportedOperationException())

        override fun refreshInstallRecord(app: VirtualApp): Result<ImportResult> {
            refreshCount += 1
            events += "refresh"
            val apk = File(app.apkPath)
            record = record.copy(
                originApkPath = apk.absolutePath,
                originApkSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(apk.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte) },
                versionCode = app.versionCode,
                versionName = app.versionName,
                targetSdk = app.targetSdkVersion,
                minSdk = app.minSdkVersion,
                applicationClassName = app.applicationClassName,
                packageLabel = app.appName,
                updatedAtMs = record.updatedAtMs + 1L
            )
            return Result.success(mockk())
        }

        override fun getInstallRecord(packageName: String): InstallRecord? =
            record.takeIf { it.packageName == packageName }

        override fun listInstallRecords(): List<InstallRecord> = listOf(record)
        override fun deleteInstallRecord(packageName: String): Boolean = false
        override fun hasInstallRecord(packageName: String): Boolean = record.packageName == packageName
    }

    private companion object {
        const val ORIGIN_PACKAGE = "com.example.refresh"
    }
}

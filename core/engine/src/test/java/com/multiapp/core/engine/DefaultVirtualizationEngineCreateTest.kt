package com.multiapp.core.engine

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ImportResult
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultVirtualizationEngineCreateTest {

    @Test
    fun `legacy create entry cannot bypass metadata and idempotency contract`() {
        val instanceManager = mockk<InstanceManager>(relaxed = true)
        val installs = RecordingInstallService()

        val result = engine(instanceManager, installs).createInstance(ORIGIN_PACKAGE)

        assertEquals(EngineResultStatus.UNSUPPORTED, result.status)
        assertEquals("metadata_and_creation_request_id_required", result.message)
        verify(exactly = 0) { instanceManager.createInstance(any<InstanceManager.CreationRequest>()) }
    }

    @Test
    fun `create imports package and persists an idempotent engine request`(@TempDir tempDir: File) {
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(File(tempDir, "records")),
            dataRootBase = File(tempDir, "instances")
        )
        val installs = RecordingInstallService()
        val engine = engine(instanceManager, installs)
        val request = request(tempDir)

        val first = engine.createInstance(request)
        val second = engine.createInstance(request)

        assertEquals(EngineResultStatus.PASS, first.status)
        assertEquals(first.instanceId, second.instanceId)
        assertEquals(1, installs.ensureCount)
        val persisted = instanceManager.getInstance(first.instanceId!!)
        assertNotNull(persisted)
        assertEquals(request.creationRequestId, persisted.creationRequestId)
        assertEquals(64, persisted.creationRequestFingerprint?.length)
        assertEquals(request.displayName, persisted.displayName)
    }

    @Test
    fun `same request id with different payload fails without creating a duplicate`(@TempDir tempDir: File) {
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(File(tempDir, "records")),
            dataRootBase = File(tempDir, "instances")
        )
        val installs = RecordingInstallService()
        val engine = engine(instanceManager, installs)
        val request = request(tempDir)
        val first = engine.createInstance(request)

        val conflict = engine.createInstance(
            request.copy(install = request.install.copy(versionCode = request.install.versionCode + 1L))
        )

        assertEquals(EngineResultStatus.PASS, first.status)
        assertEquals(EngineResultStatus.FAIL, conflict.status)
        assertEquals("creation_request_id_conflict", conflict.message)
        assertEquals(1, instanceManager.listInstances().size)
    }

    @Test
    fun `successful importer must commit a matching install generation`(@TempDir tempDir: File) {
        val instanceManager = mockk<InstanceManager>(relaxed = true)
        every { instanceManager.getInstanceByCreationRequestId(any()) } returns null
        val installs = RecordingInstallService(committedDigest = "wrong-digest")

        val result = engine(instanceManager, installs).createInstance(request(tempDir))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertTrue(
            result.message.orEmpty().contains("package_import_verification_failed:base_apk_digest"),
            result.message
        )
        assertTrue(result.message.orEmpty().contains("new_install_record_rolled_back"), result.message)
        verify(exactly = 0) { instanceManager.createInstance(any<InstanceManager.CreationRequest>()) }
    }

    @Test
    fun `sibling create reuses matching package generation without rewriting artifacts`(@TempDir tempDir: File) {
        val sourceApk = File(tempDir, "base.apk").apply { writeText("generation-one") }
        val existing = installRecord(sourceApk, sha256(sourceApk))
        val installs = RecordingInstallService(existing)
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(File(tempDir, "records")),
            dataRootBase = File(tempDir, "instances")
        )
        val baseRequest = request(tempDir)
        val request = baseRequest.copy(
            install = baseRequest.install.copy(
                originApkPath = sourceApk.absolutePath,
                splitApkPaths = emptyList(),
                splitPublicSourceDirs = emptyList(),
                splitNames = emptyList()
            )
        )

        val result = engine(instanceManager, installs).createInstance(request)

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(0, installs.ensureCount)
        assertEquals(1, instanceManager.listInstances().size)
    }

    @Test
    fun `create rejects a changed source generation instead of replacing sibling artifacts`(@TempDir tempDir: File) {
        val sourceApk = File(tempDir, "base.apk").apply { writeText("changed-generation") }
        val existing = installRecord(sourceApk, originDigest = sha256Text("old-generation"))
        val installs = RecordingInstallService(existing)
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(File(tempDir, "records")),
            dataRootBase = File(tempDir, "instances")
        )
        val baseRequest = request(tempDir)
        val request = baseRequest.copy(
            install = baseRequest.install.copy(
                originApkPath = sourceApk.absolutePath,
                splitApkPaths = emptyList(),
                splitPublicSourceDirs = emptyList(),
                splitNames = emptyList()
            )
        )

        val result = engine(instanceManager, installs).createInstance(request)

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("package_generation_mismatch_refresh_required:base_apk_digest", result.message)
        assertEquals(0, installs.ensureCount)
        assertTrue(instanceManager.listInstances().isEmpty())
    }

    @Test
    fun `failed instance creation rolls back a newly imported package record`(@TempDir tempDir: File) {
        val instanceManager = mockk<InstanceManager>()
        every { instanceManager.getInstanceByCreationRequestId(any()) } returns null
        every { instanceManager.createInstance(any<InstanceManager.CreationRequest>()) } returns
            Result.failure(IllegalStateException("record save failed"))
        every { instanceManager.getInstanceByOrigin(ORIGIN_PACKAGE) } returns emptyList()
        val installs = RecordingInstallService()
        val engine = engine(instanceManager, installs)

        val result = engine.createInstance(request(tempDir))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertTrue(result.message.orEmpty().contains("new_install_record_rolled_back"))
        assertEquals(1, installs.deleteCount)
        assertEquals(false, installs.hasInstallRecord(ORIGIN_PACKAGE))
    }

    @Test
    fun `failed sibling creation preserves shared package artifacts`(@TempDir tempDir: File) {
        val sibling = instanceRecord("sibling", creationRequestId = "older-request")
        val instanceManager = mockk<InstanceManager>()
        every { instanceManager.getInstanceByCreationRequestId(any()) } returns null
        every { instanceManager.createInstance(any<InstanceManager.CreationRequest>()) } returns
            Result.failure(IllegalStateException("record save failed"))
        every { instanceManager.getInstanceByOrigin(ORIGIN_PACKAGE) } returns listOf(sibling)
        val installs = RecordingInstallService()
        val engine = engine(instanceManager, installs)

        val result = engine.createInstance(request(tempDir))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertTrue(result.message.orEmpty().contains("install_record_preserved_for_sibling_instance"))
        assertEquals(0, installs.deleteCount)
        assertTrue(installs.hasInstallRecord(ORIGIN_PACKAGE))
        verify(exactly = 0) { instanceManager.deleteInstance(any()) }
    }

    private fun engine(
        instanceManager: InstanceManager,
        installs: VirtualInstallService
    ) = DefaultVirtualizationEngineCore(
        hostPackageName = "com.multiapp.app",
        instanceManager = instanceManager,
        virtualInstallService = installs,
        activityLauncher = EngineActivityLauncher { }
    )

    private fun request(tempDir: File): CreateInstanceRequest {
        val sourceApk = File(tempDir, "request-base.apk").apply {
            if (!exists()) writeText("request-base")
        }
        val splitApk = File(tempDir, "request-config.arm64.apk").apply {
            if (!exists()) writeText("request-split")
        }
        return CreateInstanceRequest(
        creationRequestId = "create-request-1",
        install = EnginePackageInstallRequest(
            originPackageName = ORIGIN_PACKAGE,
            originApkPath = sourceApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            packageLabel = "Test App",
            requestedPermissions = listOf("android.permission.CAMERA"),
            activityClassNames = listOf("com.test.app.MainActivity"),
            splitApkPaths = listOf(splitApk.absolutePath),
            splitPublicSourceDirs = listOf(splitApk.absolutePath),
            splitNames = listOf("config.arm64")
        ),
        displayName = "Test App Work",
        compatibilityMode = CompatibilityMode.DEFAULT
    )
    }

    private fun instanceRecord(instanceId: String, creationRequestId: String) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = "com.multiapp.instance.$instanceId",
        displayName = "Sibling",
        dataRoot = "/tmp/$instanceId",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        state = InstanceState.READY,
        creationRequestId = creationRequestId
    )

    private fun installRecord(sourceApk: File, originDigest: String) = InstallRecord(
        packageName = ORIGIN_PACKAGE,
        originApkPath = sourceApk.absolutePath,
        originApkSha256 = originDigest,
        originCertSha256 = "certificate",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        packageLabel = "Test App",
        installTimeMs = 1L
    )

    private fun sha256(file: File): String = sha256Bytes(file.readBytes())

    private fun sha256Text(value: String): String = sha256Bytes(value.toByteArray())

    private fun sha256Bytes(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }

    private class RecordingInstallService(
        initialRecord: InstallRecord? = null,
        private val committedDigest: String? = null
    ) : VirtualInstallService {
        private var record: InstallRecord? = initialRecord
        var ensureCount = 0
            private set
        var deleteCount = 0
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

        override fun ensureInstallRecord(app: VirtualApp): Result<ImportResult> {
            ensureCount += 1
            record = InstallRecord(
                packageName = app.packageName,
                originApkPath = app.apkPath,
                originApkSha256 = committedDigest ?: sha256(File(app.apkPath)),
                originCertSha256 = "certificate",
                versionCode = app.versionCode,
                versionName = app.versionName,
                targetSdk = app.targetSdkVersion,
                minSdk = app.minSdkVersion,
                applicationClassName = app.applicationClassName,
                packageLabel = app.appName,
                splitApkPaths = app.splitApkPaths,
                splitApkSha256s = app.splitApkPaths.map { sha256(File(it)) },
                splitPublicSourceDirs = app.splitPublicSourceDirs,
                splitNames = app.splitNames,
                isolatedSplits = app.isolatedSplits,
                installTimeMs = 1L
            )
            return Result.success(mockk())
        }

        override fun getInstallRecord(packageName: String): InstallRecord? = record
        override fun listInstallRecords(): List<InstallRecord> = listOfNotNull(record)

        override fun deleteInstallRecord(packageName: String): Boolean {
            if (record == null) return false
            record = null
            deleteCount += 1
            return true
        }

        override fun hasInstallRecord(packageName: String): Boolean = record != null

        private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val ORIGIN_PACKAGE = "com.test.app"
    }
}

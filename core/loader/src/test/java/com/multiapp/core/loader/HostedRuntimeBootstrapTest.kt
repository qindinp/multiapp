package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedRuntimeBootstrapTest {

    // ── Fakes ────────────────────────────────────────────────────────────

    private class FakeInstanceManager(
        private val records: Map<String, VirtualInstanceRecord> = emptyMap()
    ) : InstanceManager {
        override fun createInstance(
            originPackageName: String,
            displayName: String,
            compatibilityMode: CompatibilityMode
        ): Result<VirtualInstanceRecord> = Result.failure(UnsupportedOperationException())

        override fun getInstance(instanceId: String): VirtualInstanceRecord? = records[instanceId]

        override fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord> =
            records.values.filter { it.originPackageName == originPackageName }

        override fun listInstances(): List<VirtualInstanceRecord> = records.values.toList()

        override fun deleteInstance(instanceId: String): Boolean = false

        override fun updateLaunchState(instanceId: String): VirtualInstanceRecord? = null

        override fun getDataRoot(instanceId: String) = null
    }

    private class FakeInstallRecordStore(
        private val records: Map<String, InstallRecord> = emptyMap()
    ) : InstallRecordStore {
        override fun save(record: InstallRecord): Result<String> = Result.failure(UnsupportedOperationException())
        override fun load(packageName: String): InstallRecord? = records[packageName]
        override fun listAll(): List<InstallRecord> = records.values.toList()
        override fun delete(packageName: String): Boolean = false
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `run returns failure when instance not found`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(emptyMap()),
            installRecordStore = FakeInstallRecordStore()
        )

        val result = bootstrap.run("nonexistent-id")

        assertFalse(result.success)
        assertEquals("nonexistent-id", result.instanceId)
        assertNull(result.guestClassLoader)
        assertNull(result.guestApplication)
        assertTrue(result.stageResults.isNotEmpty())
        assertEquals(BootstrapStatus.FAILED, result.summary.overallStatus)
    }

    @Test
    fun `run returns failure when install record not found`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(emptyMap())
        )

        val result = bootstrap.run("inst-001")

        assertFalse(result.success)
        assertEquals("inst-001", result.instanceId)
        assertNull(result.guestClassLoader)
        assertEquals(BootstrapStatus.FAILED, result.summary.overallStatus)
    }

    @Test
    fun `run returns failure when origin APK does not exist`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = "/nonexistent/path.apk"))
            )
        )

        val result = bootstrap.run("inst-001")

        assertFalse(result.success)
        assertNull(result.guestClassLoader)
        assertEquals(BootstrapStatus.FAILED, result.summary.overallStatus)
    }

    @Test
    fun `run returns success with guest ClassLoader when valid instance and APK`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B)) // minimal APK magic bytes

        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            )
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertEquals("inst-001", result.instanceId)
        assertNotNull(result.guestClassLoader)
        assertEquals("com.example.app", result.originPackageName)
        assertEquals(apkFile.absolutePath, result.originApkPath)
        assertEquals(BootstrapStatus.SUCCESS, result.summary.overallStatus)
    }

    @Test
    fun `run populates stage results`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B))

        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            )
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.stageResults.isNotEmpty())
        // Should have at least: load instance, load install record, resolve APK, create ClassLoader, application (skipped)
        assertTrue(result.stageResults.size >= 4)
        // All stages except APPLICATION should be SUCCESS; APPLICATION is SKIPPED (phase 2)
        val nonAppStages = result.stageResults.filter { it.stage != RuntimeStage.APPLICATION }
        nonAppStages.forEach { stageResult ->
            assertEquals(BootstrapStatus.SUCCESS, stageResult.status)
        }
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.SKIPPED, appStage.status)
    }

    @Test
    fun `run includes instanceId in result`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(emptyMap()),
            installRecordStore = FakeInstallRecordStore()
        )

        val result = bootstrap.run("my-instance-42")

        assertEquals("my-instance-42", result.instanceId)
    }

    @Test
    fun `run populates installId from install record`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B))

        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            )
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertEquals("com.example.app", result.installId)
    }

    @Test
    fun `run records duration per stage`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B))

        var fakeMs = 1000L
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            clock = { fakeMs }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        // Each stage should have non-negative duration
        result.stageResults.forEach { stageResult ->
            assertTrue(stageResult.durationMs >= 0)
        }
    }

    @Test
    fun `run uses custom ClassLoader factory`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B))

        var factoryCalled = false
        val customClassLoader = ClassLoader.getSystemClassLoader()
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            classLoaderFactory = { _, _ ->
                factoryCalled = true
                customClassLoader
            }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertTrue(factoryCalled)
        assertEquals(customClassLoader, result.guestClassLoader)
    }

    @Test
    fun `run returns failure when ClassLoader factory throws`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B))

        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            classLoaderFactory = { _, _ -> throw RuntimeException("dex load failed") }
        )

        val result = bootstrap.run("inst-001")

        assertFalse(result.success)
        assertNull(result.guestClassLoader)
        assertEquals(BootstrapStatus.FAILED, result.summary.overallStatus)
    }
}

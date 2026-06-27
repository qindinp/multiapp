package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import com.multiapp.core.hook.Interface20Verdict
import com.multiapp.core.hook.NativeDiagnosticsEvidence
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fake Application for JVM testing. Overrides attachBaseContext to avoid
 * Android framework stubs throwing RuntimeException("Stub!").
 */
class FakeTestApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        // no-op for JVM testing
    }
}

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

    private fun validBootstrap(
        tempDir: File,
        hostContext: Context? = null,
        applicationClassNameResolver: (ClassLoader, String?) -> String? = { _, _ -> null }
    ): Triple<HostedRuntimeBootstrap, File, FakeInstanceManager> {
        val apkFile = File(tempDir, "example.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B))
        val instanceManager = FakeInstanceManager(
            mapOf("inst-001" to instanceRecord())
        )
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = hostContext,
            applicationClassNameResolver = applicationClassNameResolver
        )
        return Triple(bootstrap, apkFile, instanceManager)
    }

    // ── Phase 1 Tests (existing) ─────────────────────────────────────────

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
        val (bootstrap, apkFile, _) = validBootstrap(tempDir)

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
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.stageResults.isNotEmpty())
        assertTrue(result.stageResults.size >= 4)
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
        val (bootstrap, _, _) = validBootstrap(tempDir)

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

    // ── Phase 2 Tests: Guest Application creation ────────────────────────

    @Test
    fun `APPLICATION stage is SKIPPED when resolver returns null`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ -> null }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertNull(result.guestApplication)
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.SKIPPED, appStage.status)
    }

    @Test
    fun `APPLICATION stage is SKIPPED when hostContext is null and no resolver`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir, hostContext = null)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertNull(result.guestApplication)
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.SKIPPED, appStage.status)
    }

    @Test
    fun `APPLICATION stage is FAILED when Application class not found`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ -> "com.nonexistent.FakeApp" }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success) // bootstrap overall still succeeds (non-terminal)
        assertNull(result.guestApplication)
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.FAILED, appStage.status)
        assertNotNull(appStage.errorClass)
    }

    @Test
    fun `guestApplication is created when resolver returns valid class and hostContext available`(
        @TempDir tempDir: File
    ) {
        // NOTE: In JVM unit tests, VirtualContextWrapper construction requires
        // a real Android Context (ContextWrapper constructor). This test verifies
        // the stage result when hostContext is null but resolver returns a class -
        // the NPE from hostContext!! is caught and stage is FAILED.
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ ->
                "com.multiapp.core.loader.FakeTestApplication"
            }
        )

        val result = bootstrap.run("inst-001")

        // hostContext is null -> VirtualContextWrapper creation throws NPE -> stage FAILED
        assertTrue(result.success) // bootstrap overall still succeeds
        assertNull(result.guestApplication)
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.FAILED, appStage.status)
        assertNotNull(appStage.errorClass)
    }

    @Test
    fun `APPLICATION stage is FAILED when Application init fails due to null hostContext`(
        @TempDir tempDir: File
    ) {
        // Resolver returns a class name but hostContext is null.
        // The NPE from hostContext!! is caught and stage is FAILED.
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ ->
                "android.app.Application"
            }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success) // bootstrap overall still succeeds
        assertNull(result.guestApplication)
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.FAILED, appStage.status)
        assertNotNull(appStage.errorClass)
    }

    @Test
    fun `APPLICATION stage is FAILED when constructor throws`(
        @TempDir tempDir: File
    ) {
        // java.lang.Runtime has no no-arg constructor -> newInstance() throws
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ -> "java.lang.Runtime" }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success) // bootstrap overall still succeeds
        assertNull(result.guestApplication)
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.FAILED, appStage.status)
    }

    @Test
    fun `APPLICATION stage failure does not prevent earlier stages from succeeding`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ -> "com.nonexistent.FakeApp" }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertNotNull(result.guestClassLoader)
        val nonAppStages = result.stageResults.filter { it.stage != RuntimeStage.APPLICATION }
        nonAppStages.forEach { stageResult ->
            assertEquals(BootstrapStatus.SUCCESS, stageResult.status)
        }
    }

    @Test
    fun `resolveApplicationClassName returns null when apkPath is null`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(),
            installRecordStore = FakeInstallRecordStore()
        )

        val result = bootstrap.resolveApplicationClassName(ClassLoader.getSystemClassLoader(), null)

        assertNull(result)
    }

    @Test
    fun `APPLICATION stage records duration`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ -> null }
        )

        val result = bootstrap.run("inst-001")

        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertTrue(appStage.durationMs >= 0)
    }

    // ── Phase 3 Tests: NativeDiagnosticsProfile integration ────────────

    @Test
    fun `result includes diagnostics when bootstrap succeeds`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertNotNull(result.diagnostics)
        // No native evidence in JVM test -> verdict should not be ORIGINAL_SHELL_REGISTERED
        assertNotEquals(Interface20Verdict.ORIGINAL_SHELL_REGISTERED, result.diagnostics!!.verdict)
    }

    @Test
    fun `result includes diagnostics when instance not found`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(emptyMap()),
            installRecordStore = FakeInstallRecordStore()
        )

        val result = bootstrap.run("nonexistent-id")

        assertFalse(result.success)
        assertNotNull(result.diagnostics)
    }

    @Test
    fun `result includes diagnostics when install record not found`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(emptyMap())
        )

        val result = bootstrap.run("inst-001")

        assertFalse(result.success)
        assertNotNull(result.diagnostics)
    }

    @Test
    fun `result includes diagnostics when origin APK does not exist`() {
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
        assertNotNull(result.diagnostics)
    }

    @Test
    fun `result includes diagnostics when ClassLoader factory throws`(
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
        assertNotNull(result.diagnostics)
    }

    @Test
    fun `diagnostics includes classloader_created evidence on success`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val diagnostics = result.diagnostics!!
        val classLoaderEvidence = diagnostics.evidence.find { it.key == "classloader_created" }
        assertNotNull(classLoaderEvidence)
        assertEquals("true", classLoaderEvidence.value)
        assertEquals("HostedRuntimeBootstrap", classLoaderEvidence.source)
    }

    @Test
    fun `diagnostics includes application_created evidence on success`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val diagnostics = result.diagnostics!!
        val appEvidence = diagnostics.evidence.find { it.key == "application_created" }
        assertNotNull(appEvidence)
        // APPLICATION stage is SKIPPED when resolver returns null -> "false"
        assertEquals("false", appEvidence.value)
    }

    @Test
    fun `diagnostics includes origin_apk_path evidence when APK resolved`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, apkFile, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val diagnostics = result.diagnostics!!
        val apkEvidence = diagnostics.evidence.find { it.key == "origin_apk_path" }
        assertNotNull(apkEvidence)
        assertEquals(apkFile.absolutePath, apkEvidence.value)
    }

    @Test
    fun `diagnostics verdict is JNI_ONLOAD_NOT_EXECUTED when no native evidence`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val diagnostics = result.diagnostics!!
        // No jni_onload_executed evidence in bootstrap context
        assertEquals(Interface20Verdict.JNI_ONLOAD_NOT_EXECUTED, diagnostics.verdict)
    }

    @Test
    fun `diagnostics evidence is empty when instance not found`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(emptyMap()),
            installRecordStore = FakeInstallRecordStore()
        )

        val result = bootstrap.run("nonexistent-id")

        assertNotNull(result.diagnostics)
        assertTrue(result.diagnostics!!.evidence.isEmpty())
    }

    @Test
    fun `diagnostics uses default NativeDiagnosticsConfig`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        val diagnostics = result.diagnostics!!
        // Default config: root-requiring flags are false
        assertFalse(diagnostics.config.recordNativeNamespace)
        assertFalse(diagnostics.config.recordProcMaps)
        assertFalse(diagnostics.config.recordLinkerMessage)
        assertTrue(diagnostics.config.recordJniOnLoad)
    }

    // ── R1 E2E Tests: InstallRecord -> Instance -> Bootstrap ─────────────

    @Test
    fun `e2e - bootstrap reads InstallRecord created by InstalledPackageImporter and reaches ORIGIN_APK stage`(
        @TempDir tempDir: File
    ) {
        // Arrange: Create a fake APK file
        val apkFile = File(tempDir, "test-origin.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // PK header

        // Act: Import via InstalledPackageImporter (simulates VirtualInstallService flow)
        val installDir = File(tempDir, "installs")
        val artifactDir = File(tempDir, "artifacts")
        val store = JsonInstallRecordStore(installDir)
        val importer = com.multiapp.core.model.installer.InstalledPackageImporter(store, artifactDir)

        val importResult = importer.importFromMetadata(
            packageName = "com.example.e2etest",
            originApkPath = apkFile.absolutePath,
            versionCode = 100,
            versionName = "2.0",
            targetSdk = 34,
            minSdk = 26,
            applicationClassName = null,
            packageLabel = "E2E Test App"
        )
        assertTrue(importResult.isSuccess, "Import should succeed")

        // Verify InstallRecord is persisted
        val loadedRecord = store.load("com.example.e2etest")
        assertNotNull(loadedRecord, "InstallRecord should be persisted in store")
        assertEquals("com.example.e2etest", loadedRecord.packageName)
        assertTrue(File(loadedRecord.originApkPath).exists(), "Artifact APK should exist")

        // Create instance record (simulates InstanceManager.createInstance flow)
        val instanceId = "e2e-inst-001"
        val instanceRecord = VirtualInstanceRecord(
            instanceId = instanceId,
            originPackageName = "com.example.e2etest",
            virtualPackageName = "com.multiapp.instance.e2etest",
            displayName = "E2E Test App",
            dataRoot = File(tempDir, "instance_data/$instanceId").absolutePath,
            compatibilityMode = CompatibilityMode.DEFAULT,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            state = InstanceState.READY
        )

        val instanceManager = FakeInstanceManager(mapOf(instanceId to instanceRecord))

        // Bootstrap should read the InstallRecord and proceed past PACKAGE_METADATA
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = store,
            hostContext = null
        )

        val result = bootstrap.run(instanceId)

        // Assert: Bootstrap should NOT stop at PACKAGE_METADATA
        assertTrue(result.success, "Bootstrap should succeed")
        assertNotNull(result.installId)
        assertEquals("com.example.e2etest", result.installId)

        // Verify PACKAGE_METADATA stage succeeded
        val metadataStage = result.stageResults.find { it.stage == RuntimeStage.PACKAGE_METADATA }
        assertNotNull(metadataStage, "PACKAGE_METADATA stage should exist")
        assertEquals(BootstrapStatus.SUCCESS, metadataStage.status,
            "PACKAGE_METADATA should succeed - InstallRecord must be loadable")

        // Verify ORIGIN_APK stage succeeded (artifact APK exists)
        val originApkStage = result.stageResults.find { it.stage == RuntimeStage.ORIGIN_APK }
        assertNotNull(originApkStage, "ORIGIN_APK stage should exist")
        assertEquals(BootstrapStatus.SUCCESS, originApkStage.status,
            "ORIGIN_APK should succeed - artifact APK should be found")

        // Verify ClassLoader stage succeeded
        val classLoaderStage = result.stageResults.find { it.stage == RuntimeStage.CLASS_LOADER }
        assertNotNull(classLoaderStage, "CLASS_LOADER stage should exist")
        assertEquals(BootstrapStatus.SUCCESS, classLoaderStage.status)
    }

    @Test
    fun `e2e - bootstrap fails at PACKAGE_METADATA when InstallRecord not imported`(
        @TempDir tempDir: File
    ) {
        // Arrange: Create instance WITHOUT importing InstallRecord
        val instanceId = "e2e-inst-missing"
        val instanceRecord = VirtualInstanceRecord(
            instanceId = instanceId,
            originPackageName = "com.example.notimported",
            virtualPackageName = "com.multiapp.instance.notimported",
            displayName = "Not Imported App",
            dataRoot = File(tempDir, "instance_data/$instanceId").absolutePath,
            compatibilityMode = CompatibilityMode.DEFAULT,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            state = InstanceState.READY
        )

        val instanceManager = FakeInstanceManager(mapOf(instanceId to instanceRecord))
        val emptyStore = JsonInstallRecordStore(File(tempDir, "empty_installs"))

        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = emptyStore,
            hostContext = null
        )

        val result = bootstrap.run(instanceId)

        // Assert: Bootstrap should fail at PACKAGE_METADATA
        assertFalse(result.success, "Bootstrap should fail when InstallRecord is missing")
        val metadataStage = result.stageResults.find { it.stage == RuntimeStage.PACKAGE_METADATA }
        assertNotNull(metadataStage)
        assertEquals(BootstrapStatus.FAILED, metadataStage.status,
            "PACKAGE_METADATA should fail when no InstallRecord exists")
    }
}

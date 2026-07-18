package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.hook.Interface20Verdict
import com.multiapp.core.hook.NativeDiagnosticsEvidence
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageResolver
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fake Application for JVM testing. Overrides attachBaseContext to avoid
 * Android framework stubs throwing RuntimeException("Stub!").
 */
class FakeTestApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        // no-op for JVM testing
    }

    override fun onCreate() {
        // no-op for JVM testing
    }
}

/**
 * Fake Application that tracks whether onCreate() was called.
 * Used to verify that HostedRuntimeBootstrap invokes guestApplication.onCreate().
 */
class FakeTestApplicationWithOnCreate : Application() {
    override fun attachBaseContext(base: Context?) {
        // no-op for JVM testing
    }

    override fun onCreate() {
        // Do NOT call super.onCreate() �?Android stub would throw on JVM
        onCreateCalled = true
    }

    companion object {
        var onCreateCalled: Boolean = false
        fun reset() {
            onCreateCalled = false
        }
    }
}

class HostedRuntimeBootstrapTest {

    @Test
    fun `platform guest classloader parent matches Android application loader parent`() {
        val expected = ClassLoader.getSystemClassLoader().parent
            ?: ClassLoader.getSystemClassLoader()

        assertSame(expected, HostedRuntimeBootstrap.platformGuestClassLoaderParent())
    }

    @Test
    fun `platform classloader arguments support API 30 through 36 signatures`() {
        val parent = ClassLoader.getSystemClassLoader()
        val spec = GuestClassLoaderSpec(
            dexPath = "/artifact/base.apk",
            baseApkPath = "/artifact/base.apk",
            splitApkPaths = emptyList(),
            nativeLibraryDir = "/data/instances/inst/lib/arm64-v8a",
            librarySearchPath = "/data/instances/inst/lib/arm64-v8a",
            libraryPermittedPath = "/data/instances/inst",
            targetSdkVersion = 35
        )

        (8..10).forEach { parameterCount ->
            val args = HostedRuntimeBootstrap.platformClassLoaderFactoryArguments(
                spec = spec,
                parentClassLoader = parent,
                parameterCount = parameterCount
            )

            assertEquals(parameterCount, args.size)
            assertSame(parent, args[3])
            assertEquals(35, args[4])
            assertTrue(args[7] is List<*>)
            if (parameterCount >= 9) assertTrue(args[8] is List<*>)
            if (parameterCount >= 10) assertTrue(args[9] is List<*>)
        }
    }

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
        applicationClassNameResolver: (ClassLoader, String?) -> String? = { _, _ -> null },
        guestApplicationCreator: GuestApplicationCreator = LoadedApkGuestApplicationCreator(),
        packageManagerProxyInstaller: VirtualPackageManagerGlobalInstallAction = successfulPackageManagerProxyInstaller()
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
            applicationClassNameResolver = applicationClassNameResolver,
            guestApplicationCreator = guestApplicationCreator,
            packageManagerProxyInstaller = packageManagerProxyInstaller,
            runtimeUidProvider = { 42420 }
        )
        return Triple(bootstrap, apkFile, instanceManager)
    }

    private fun successfulPackageManagerProxyInstaller() = VirtualPackageManagerGlobalInstallAction { _, snapshot, runtimeUid ->
        VirtualPackageManagerGlobalInstallResult(
            status = VirtualPackageManagerGlobalInstallStatus.INSTALLED,
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            runtimeUid = runtimeUid,
            sPackageManagerRead = true,
            sPackageManagerPatched = true,
            ipackageManagerInterface = "fake.IPackageManager",
            originalPackageManagerClass = "fake.Pms",
            proxyClass = "fake.Proxy",
            applicationPackageManagerPatchResults = listOf(
                ActivityThreadPackageManagerPatchResult("hostContext.packageManager", patched = true)
            )
        )
    }

    private fun degradedPackageManagerProxyInstaller(reason: String) = VirtualPackageManagerGlobalInstallAction { _, snapshot, runtimeUid ->
        VirtualPackageManagerGlobalInstallResult(
            status = VirtualPackageManagerGlobalInstallStatus.DEGRADED,
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            runtimeUid = runtimeUid,
            sPackageManagerRead = false,
            sPackageManagerPatched = false,
            degradedReasons = listOf(reason)
        )
    }

    private fun jvmDefaultApplicationCreator() = GuestApplicationCreator { request ->
        GuestApplicationCreateResult(
            application = FakeTestApplication(),
            attachedContextPackageName = request.virtualContextConfig.originPackageName,
            evidence = listOf(BootstrapEvidence("applicationCreator", "TEST_DEFAULT_APPLICATION"))
        )
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
    fun `run returns structured config failure when instance id is blank`() {
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(emptyMap()),
            installRecordStore = FakeInstallRecordStore()
        )

        val result = bootstrap.run(" ")

        assertFalse(result.success)
        assertEquals(" ", result.instanceId)
        assertNull(result.guestClassLoader)
        assertNull(result.guestApplication)
        assertEquals(BootstrapStatus.FAILED, result.summary.overallStatus)
        val configStage = result.stageResults.single()
        assertEquals(RuntimeStage.CONFIG, configStage.stage)
        assertEquals(BootstrapStatus.FAILED, configStage.status)
        assertEquals("Instance not found:  ", configStage.message)
        assertNotNull(result.diagnostics)
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
    fun `run uses InstallRecord originApkPath as classloader APK source`(
        @TempDir tempDir: File
    ) {
        val installRecordApk = File(tempDir, "install-record-origin.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B))
        }
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val decoyInstanceApk = File(dataRoot, "base.apk").apply { writeText("not the install artifact") }
        var capturedClassLoaderApkPath: String? = null
        val instance = instanceRecord(originPackageName = "com.example.factsource").copy(
            dataRoot = dataRoot.absolutePath
        )
        val installRecord = installRecord(
            packageName = "com.example.factsource",
            originApkPath = installRecordApk.absolutePath
        )
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(mapOf("inst-001" to instance)),
            installRecordStore = FakeInstallRecordStore(mapOf("com.example.factsource" to installRecord)),
            classLoaderFactory = { apkPath, _ ->
                capturedClassLoaderApkPath = apkPath
                ClassLoader.getSystemClassLoader()
            }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertEquals("com.example.factsource", result.installId)
        assertEquals(installRecordApk.absolutePath, result.originApkPath)
        assertEquals(installRecordApk.absolutePath, capturedClassLoaderApkPath)
        assertNotEquals(decoyInstanceApk.absolutePath, capturedClassLoaderApkPath)
    }

    @Test
    fun `run populates stage results`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.stageResults.isNotEmpty())
        assertTrue(result.stageResults.size >= 4)
        val requiredStages = setOf(
            RuntimeStage.CONFIG,
            RuntimeStage.PACKAGE_METADATA,
            RuntimeStage.ORIGIN_APK,
            RuntimeStage.CLASS_LOADER
        )
        result.stageResults.filter { it.stage in requiredStages }.forEach { stageResult ->
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

        assertFalse(result.success) // P0-3: Application stage failure -> overall failure
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
        assertFalse(result.success) // P0-3: Application stage failure -> overall failure
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

        assertFalse(result.success) // P0-3: Application stage failure -> overall failure
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

        assertFalse(result.success) // P0-3: Application stage failure -> overall failure
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

        assertFalse(result.success) // P0-3: Application stage failure -> overall failure
        assertNotNull(result.guestClassLoader)
        val requiredStages = setOf(
            RuntimeStage.CONFIG,
            RuntimeStage.PACKAGE_METADATA,
            RuntimeStage.ORIGIN_APK,
            RuntimeStage.CLASS_LOADER
        )
        result.stageResults.filter { it.stage in requiredStages }.forEach { stageResult ->
            assertEquals(BootstrapStatus.SUCCESS, stageResult.status)
        }
    }

    @Test
    fun `APPLICATION stage failure causes overall bootstrap to fail`(
        @TempDir tempDir: File
    ) {
        // com.nonexistent.FakeApp -> ClassNotFoundException -> stage FAILED
        // Per P0-3 requirement: Application stage failure must cause success=false
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            applicationClassNameResolver = { _, _ -> "com.nonexistent.FakeApp" }
        )

        val result = bootstrap.run("inst-001")

        assertFalse(result.success, "Bootstrap must fail when APPLICATION stage fails")
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.FAILED, appStage.status)
        assertEquals(BootstrapStatus.FAILED, result.summary.overallStatus)
    }

    @Test
    fun `launcher Activity resolver prefers manifest package resolver over InstallRecord fallback`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val installRecord = installRecord(originApkPath = apkFile.absolutePath).copy(
            activities = listOf(ComponentInfo(name = "java.lang.Integer", exported = true))
        )
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                assertEquals(apkFile.absolutePath, apkPath)
                return ResolvedPackage(
                    packageName = "com.example.app",
                    versionCode = 1,
                    versionName = "1.0",
                    targetSdk = 35,
                    minSdk = 28,
                    launcherActivityName = "java.lang.String",
                    applicationLabel = "Example Label"
                )
            }
        }
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(mapOf("inst-001" to instanceRecord())),
            installRecordStore = FakeInstallRecordStore(mapOf("com.example.app" to installRecord)),
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            applicationClassNameResolver = { _, _ -> null },
            guestApplicationCreator = jvmDefaultApplicationCreator(),
            packageResolver = packageResolver
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertEquals("java.lang.String", result.launcherActivityClassName)
        assertEquals("Example Label", result.applicationLabel)
        val launcherStage = result.stageResults.first { it.stage == RuntimeStage.LAUNCHER_ACTIVITY }
        assertEquals(BootstrapStatus.SUCCESS, launcherStage.status)
        assertEquals(
            "VirtualPackageResolver",
            launcherStage.evidence.first { it.key == "resolver" }.value
        )
    }
    @Test
    fun `nativeLibraryDir is passed to classLoaderFactory when instance lib dir exists`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }
        var capturedNativeLibraryDir: String? = "not-called"
        val instanceManager = FakeInstanceManager(
            mapOf("inst-001" to instanceRecord().copy(dataRoot = dataRoot.absolutePath))
        )
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            classLoaderFactory = { _, nativeLibraryDir ->
                capturedNativeLibraryDir = nativeLibraryDir
                ClassLoader.getSystemClassLoader()
            },
            applicationClassNameResolver = { _, _ -> null }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertEquals(libDir.absolutePath, capturedNativeLibraryDir)
        val classLoaderStage = result.stageResults.find { it.stage == RuntimeStage.CLASS_LOADER }
        assertNotNull(classLoaderStage)
        assertEquals(
            libDir.absolutePath,
            classLoaderStage.evidence.find { it.key == "nativeLibraryDir" }?.value
        )
    }

    @Test
    fun `native libraries are extracted from origin apk before classloader creation`(
        @TempDir tempDir: File
    ) {
        val selectedAbi = NativeLibraryPaths.currentProcessSupportedAbis().first()
        val apkFile = File(tempDir, "native-origin.apk").also { apk ->
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/$selectedAbi/libapp_lib.so"))
                zip.write(byteArrayOf(9, 8, 7, 6))
                zip.closeEntry()
            }
        }
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val instanceManager = FakeInstanceManager(
            mapOf("inst-001" to instanceRecord().copy(dataRoot = dataRoot.absolutePath))
        )
        var capturedNativeLibraryDir: String? = null
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            classLoaderFactory = { _, nativeLibraryDir ->
                capturedNativeLibraryDir = nativeLibraryDir
                ClassLoader.getSystemClassLoader()
            },
            applicationClassNameResolver = { _, _ -> null }
        )

        val result = bootstrap.run("inst-001")

        val abiDir = File(dataRoot, "lib/$selectedAbi")
        assertTrue(result.success)
        assertEquals(abiDir.absolutePath, capturedNativeLibraryDir)
        assertTrue(File(abiDir, "libapp_lib.so").isFile)
        val nativeStage = result.stageResults.first { it.stage == RuntimeStage.NATIVE_LIBS }
        val nativeEvidence = nativeStage.evidence.associate { it.key to it.value }
        assertEquals("APK_EXTRACTED_ABI_DIR", nativeEvidence["nativeLibrarySource"])
        assertEquals("EXTRACTED", nativeEvidence["nativeLibrariesExtraction"])
        assertEquals(abiDir.absolutePath, nativeEvidence["nativeLibraryDir"])
        val classLoaderStage = result.stageResults.first { it.stage == RuntimeStage.CLASS_LOADER }
        assertEquals(abiDir.absolutePath, classLoaderStage.evidence.find { it.key == "nativeLibraryDir" }?.value)
    }

    @Test
    fun `bootstrap records provider routing evidence when manifest declares providers`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val mockContext: Context = mockk(relaxed = true)
        every { mockContext.packageName } returns "com.multiapp.app"
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                assertEquals(apkFile.absolutePath, apkPath)
                return ResolvedPackage(
                    packageName = "com.example.app",
                    versionCode = 1,
                    versionName = "1.0",
                    targetSdk = 35,
                    minSdk = 28,
                    providers = listOf(
                        ResolvedComponent(
                            name = "com.example.app.ProbeProvider",
                            exported = false,
                            authorities = listOf("com.example.app.probe"),
                            permission = "com.example.app.permission.PROBE",
                            grantUriPermissions = true
                        )
                    )
                )
            }
        }
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(mapOf("inst-001" to instanceRecord())),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = mockContext,
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            applicationClassNameResolver = { _, _ -> null },
            guestApplicationCreator = jvmDefaultApplicationCreator(),
            packageResolver = packageResolver
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val classLoaderStage = result.stageResults.first { it.stage == RuntimeStage.CLASS_LOADER }
        val evidence = classLoaderStage.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["providerRoutingEnabled"])
        assertEquals("AUTHORITY_MAP_READY", evidence["providerRoutingReason"])
        assertEquals("ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY", evidence["providerRoutingPrimary"])
        assertEquals("NONE", evidence["providerRoutingFallback"])
        assertEquals("1", evidence["providerAuthorityMapSize"])
        assertEquals("INSTANCE", evidence["providerRoutingScope"])
        assertEquals("false", evidence["processWideProviderHook"])
        assertEquals("VirtualContentResolver", evidence["authorityRewriteEntry"])
        assertEquals("1", evidence["providerPolicyPermissionCount"])
        assertEquals("1", evidence["providerPolicyGrantUriPermissionCount"])
        assertEquals("INTERNAL_ONLY", evidence["providerPolicyStatuses"])
        assertEquals("ROUTED_BY_STUB_PROVIDER", evidence["providerOperationOpenTypedAssetFileStatus"])
        assertEquals("CONTENT_RESOLVER_HOOK_DISABLED", evidence["providerOperationNotifyChangeStatus"])
        assertEquals("CONTENT_RESOLVER_HOOK_DISABLED", evidence["providerOperationRegisterContentObserverStatus"])
        assertEquals("CONTENT_RESOLVER_HOOK_DISABLED", evidence["providerOperationUnregisterContentObserverStatus"])
        assertEquals("CONTENT_RESOLVER_HOOK_DISABLED", evidence["providerOperationGrantUriPermissionStatus"])
        assertEquals("SKIPPED", evidence["providerHookInstallStatus"])
        assertEquals("PROFILE_DISABLED", evidence["providerHookInstallReason"])
    }

    @Test
    fun `run records slice 3 stage results before classloader evidence`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }
        val mockContext: Context = mockk(relaxed = true)
        every { mockContext.packageName } returns "com.multiapp.app"
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                assertEquals(apkFile.absolutePath, apkPath)
                return ResolvedPackage(
                    packageName = "com.example.app",
                    versionCode = 1L,
                    versionName = "1.0",
                    targetSdk = 35,
                    minSdk = 28,
                    providers = listOf(
                        ResolvedComponent(
                            name = "com.example.app.ProbeProvider",
                            exported = false,
                            authorities = listOf("com.example.app.probe")
                        )
                    )
                )
            }
        }
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord().copy(dataRoot = dataRoot.absolutePath))
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = mockContext,
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            applicationClassNameResolver = { _, _ -> null },
            guestApplicationCreator = jvmDefaultApplicationCreator(),
            packageResolver = packageResolver
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val nativeStage = result.stageResults.first { it.stage == RuntimeStage.NATIVE_LIBS }
        assertEquals(BootstrapStatus.SUCCESS, nativeStage.status)
        assertEquals(libDir.absolutePath, nativeStage.evidence.find { it.key == "nativeLibraryDir" }?.value)
        val snapshotStage = result.stageResults.first { it.stage == RuntimeStage.RESOURCES }
        assertEquals(BootstrapStatus.SUCCESS, snapshotStage.status)
        assertEquals("1", snapshotStage.evidence.find { it.key == "providerCount" }?.value)
        val providerStage = result.stageResults.first { it.stage == RuntimeStage.GUEST_CONTEXT }
        assertEquals(BootstrapStatus.SUCCESS, providerStage.status)
        assertEquals("SKIPPED", providerStage.evidence.find { it.key == "providerHookInstallStatus" }?.value)
        assertEquals("PROFILE_DISABLED", providerStage.evidence.find { it.key == "providerHookInstallReason" }?.value)
        val classLoaderStage = result.stageResults.first { it.stage == RuntimeStage.CLASS_LOADER }
        assertEquals(libDir.absolutePath, classLoaderStage.evidence.find { it.key == "nativeLibraryDir" }?.value)
        assertEquals("true", classLoaderStage.evidence.find { it.key == "providerRoutingEnabled" }?.value)
    }

    @Test
    fun `package manager proxy stage runs after snapshot and before application`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(tempDir)

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val stageOrder = result.stageResults.map { it.stage }
        val snapshotIndex = stageOrder.indexOf(RuntimeStage.RESOURCES)
        val proxyIndex = stageOrder.indexOf(RuntimeStage.PACKAGE_MANAGER_PROXY)
        val applicationIndex = stageOrder.indexOf(RuntimeStage.APPLICATION)
        assertTrue(proxyIndex > snapshotIndex)
        assertTrue(proxyIndex < applicationIndex)
        val proxyStage = result.stageResults.first { it.stage == RuntimeStage.PACKAGE_MANAGER_PROXY }
        assertEquals(BootstrapStatus.SUCCESS, proxyStage.status)
        val evidence = proxyStage.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["globalPmsProxyEnabled"])
        assertEquals("package,application,component,intent,permission,uid", evidence["virtualizedQueryFamilies"])
    }

    @Test
    fun `package manager proxy degraded result does not block application or launcher stages`(
        @TempDir tempDir: File
    ) {
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            packageManagerProxyInstaller = degradedPackageManagerProxyInstaller("S_PACKAGE_MANAGER_NULL")
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val proxyStage = result.stageResults.first { it.stage == RuntimeStage.PACKAGE_MANAGER_PROXY }
        assertEquals(BootstrapStatus.DEGRADED, proxyStage.status)
        assertTrue(proxyStage.evidence.first { it.key == "degradedReasons" }.value.contains("S_PACKAGE_MANAGER_NULL"))
        val appStage = result.stageResults.first { it.stage == RuntimeStage.APPLICATION }
        val launcherStage = result.stageResults.first { it.stage == RuntimeStage.LAUNCHER_ACTIVITY }
        assertEquals(BootstrapStatus.SKIPPED, appStage.status)
        assertEquals(BootstrapStatus.SKIPPED, launcherStage.status)
    }

    @Test
    fun `bootstrap installs provider hook when profile enables provider hook install`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val mockContext: Context = mockk(relaxed = true)
        every { mockContext.packageName } returns "com.multiapp.app"
        val hookEngine = mockk<HookEngine>(relaxed = true)
        every { hookEngine.initLsplant(any()) } returns true
        every { hookEngine.hookMethodPassThrough(any(), any(), any()) } returns true
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? = ResolvedPackage(
                packageName = "com.example.app",
                versionCode = 1,
                versionName = "1.0",
                targetSdk = 35,
                minSdk = 28,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.example.app.ProbeProvider",
                        exported = false,
                        authorities = listOf("com.example.app.probe")
                    )
                )
            )
        }
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(mapOf("inst-001" to instanceRecord())),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = mockContext,
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            applicationClassNameResolver = { _, _ -> null },
            guestApplicationCreator = jvmDefaultApplicationCreator(),
            packageResolver = packageResolver,
            providerHookInstallEnabled = true,
            providerHookInstaller = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine })
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val classLoaderStage = result.stageResults.first { it.stage == RuntimeStage.CLASS_LOADER }
        val evidence = classLoaderStage.evidence.associate { it.key to it.value }
        assertEquals("INSTALLED", evidence["providerHookInstallStatus"])
        assertEquals("1", evidence["providerHookInstallAuthorityMapSize"])
        assertEquals("AUTHORITY_MAP_READY", evidence["providerHookInstallReason"])
    }

    @Test
    fun `guestApplication onCreate is called after attachBaseContext succeeds`(
        @TempDir tempDir: File
    ) {
        FakeTestApplicationWithOnCreate.reset()
        val mockContext: Context = mockk(relaxed = true)
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            hostContext = mockContext,
            applicationClassNameResolver = { _, _ ->
                "com.multiapp.core.loader.FakeTestApplicationWithOnCreate"
            },
            guestApplicationCreator = ReflectiveGuestApplicationCreator()
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success, "Bootstrap should succeed when Application.onCreate() works")
        assertNotNull(result.guestApplication)
        assertTrue(
            FakeTestApplicationWithOnCreate.onCreateCalled,
            "Application.onCreate() must be called after attachBaseContext"
        )
        val appStage = result.stageResults.find { it.stage == RuntimeStage.APPLICATION }
        assertNotNull(appStage)
        assertEquals(BootstrapStatus.SUCCESS, appStage.status)
    }

    @Test
    fun `prepare does not create guest Application until attachAndLaunch`(
        @TempDir tempDir: File
    ) {
        FakeTestApplicationWithOnCreate.reset()
        val mockContext: Context = mockk(relaxed = true)
        val (bootstrap, _, _) = validBootstrap(
            tempDir,
            hostContext = mockContext,
            applicationClassNameResolver = { _, _ ->
                "com.multiapp.core.loader.FakeTestApplicationWithOnCreate"
            },
            guestApplicationCreator = ReflectiveGuestApplicationCreator()
        )

        val preparation = bootstrap.prepare("inst-001")

        assertFalse(preparation.isTerminal)
        assertNotNull(preparation.context?.guestClassLoader)
        assertFalse(
            FakeTestApplicationWithOnCreate.onCreateCalled,
            "prepare() must not call Application.onCreate() off the UI thread"
        )
        assertNull(preparation.stageResults.find { it.stage == RuntimeStage.APPLICATION })
        assertNull(preparation.stageResults.find { it.stage == RuntimeStage.LAUNCHER_ACTIVITY })

        val result = bootstrap.attachAndLaunch(preparation)

        assertTrue(result.success)
        assertNotNull(result.guestApplication)
        assertTrue(FakeTestApplicationWithOnCreate.onCreateCalled)
    }

    @Test
    fun `prepareBeforeClassLoader defers classloader and application work`(
        @TempDir tempDir: File
    ) {
        FakeTestApplicationWithOnCreate.reset()
        var classLoaderFactoryCalls = 0
        val mockContext: Context = mockk(relaxed = true)
        val apkFile = File(tempDir, "example.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B))
        }
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = mockContext,
            classLoaderFactory = { _, _ ->
                classLoaderFactoryCalls += 1
                ClassLoader.getSystemClassLoader()
            },
            applicationClassNameResolver = { _, _ ->
                "com.multiapp.core.loader.FakeTestApplicationWithOnCreate"
            },
            guestApplicationCreator = ReflectiveGuestApplicationCreator(),
            runtimeUidProvider = { 42420 }
        )

        val preClassLoader = bootstrap.prepareBeforeClassLoader("inst-001")

        assertFalse(preClassLoader.isTerminal)
        assertNull(preClassLoader.context?.guestClassLoader)
        assertEquals(0, classLoaderFactoryCalls)
        assertFalse(FakeTestApplicationWithOnCreate.onCreateCalled)
        assertNull(preClassLoader.stageResults.find { it.stage == RuntimeStage.CLASS_LOADER })

        val withClassLoader = bootstrap.createClassLoader(preClassLoader)

        assertFalse(withClassLoader.isTerminal)
        assertNotNull(withClassLoader.context?.guestClassLoader)
        assertEquals(1, classLoaderFactoryCalls)
        assertFalse(FakeTestApplicationWithOnCreate.onCreateCalled)
        assertNotNull(withClassLoader.stageResults.find { it.stage == RuntimeStage.CLASS_LOADER })

        val result = bootstrap.attachAndLaunch(withClassLoader)

        assertTrue(result.success)
        assertTrue(FakeTestApplicationWithOnCreate.onCreateCalled)
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

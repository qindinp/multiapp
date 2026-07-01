package com.multiapp.core.model.installer

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VirtualInstallServiceTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `ensureInstallRecord imports VirtualApp metadata before instance creation`() {
        val originApk = File(tempDir, "origin.apk").apply { writeText("fake apk") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts")
        )
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(File(tempDir, "instances")),
            dataRootBase = File(tempDir, "data"),
            installRecordStore = installStore
        )
        val app = VirtualApp(
            packageName = "com.example.app",
            appName = "Example App",
            versionName = "1.2.3",
            versionCode = 123L,
            apkPath = originApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36,
            applicationClassName = "com.example.App"
        )

        val importResult = service.ensureInstallRecord(app)
        val createResult = instanceManager.createInstance(app.packageName, app.appName)

        assertTrue(importResult.isSuccess)
        assertTrue(createResult.isSuccess)

        val record = installStore.load(app.packageName)!!
        assertEquals(app.packageName, record.packageName)
        assertEquals(app.appName, record.packageLabel)
        assertEquals(app.versionName, record.versionName)
        assertEquals(app.versionCode, record.versionCode)
        assertEquals(app.minSdkVersion, record.minSdk)
        assertEquals(app.targetSdkVersion, record.targetSdk)
        assertEquals(app.applicationClassName, record.applicationClassName)
        val importedArtifact = File(record.originApkPath)
        assertTrue(importedArtifact.exists())
        val osName = System.getProperty("os.name") ?: ""
        if (!osName.contains("Windows", ignoreCase = true)) {
            assertTrue(!importedArtifact.canWrite())
        }
        assertNotEquals(originApk.absolutePath, record.originApkPath)
    }

    @Test
    fun `ensureInstallRecord persists VirtualApp manifest facts before instance creation`() {
        val originApk = File(tempDir, "origin-with-manifest.apk").apply { writeText("fake apk") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_manifest"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts_manifest")
        )
        val app = VirtualApp(
            packageName = "com.example.factsource",
            appName = "Fact Source App",
            versionName = "5.6.7",
            versionCode = 567L,
            apkPath = originApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36,
            applicationClassName = "com.example.factsource.App",
            requestedPermissions = listOf("android.permission.INTERNET"),
            activities = listOf("com.example.factsource.MainActivity"),
            services = listOf("com.example.factsource.SyncService"),
            receivers = listOf("com.example.factsource.BootReceiver"),
            providers = listOf("com.example.factsource.DataProvider"),
            nativeAbis = listOf("arm64-v8a")
        )

        val importResult = service.ensureInstallRecord(app)

        assertTrue(importResult.isSuccess)
        val record = installStore.load(app.packageName)!!
        assertEquals(app.packageName, record.packageName)
        assertEquals(app.versionCode, record.versionCode)
        assertEquals(app.versionName, record.versionName)
        assertEquals(app.minSdkVersion, record.minSdk)
        assertEquals(app.targetSdkVersion, record.targetSdk)
        assertEquals(app.applicationClassName, record.applicationClassName)
        assertEquals(app.appName, record.packageLabel)
        assertEquals(listOf("android.permission.INTERNET"), record.permissions)
        assertEquals(listOf(ComponentInfo("com.example.factsource.MainActivity")), record.activities)
        assertEquals(listOf(ComponentInfo("com.example.factsource.SyncService")), record.services)
        assertEquals(listOf(ComponentInfo("com.example.factsource.BootReceiver")), record.receivers)
        assertEquals(listOf(ComponentInfo("com.example.factsource.DataProvider")), record.providers)
        assertEquals(emptyList(), record.nativeLibraries)
        assertEquals(listOf("arm64-v8a"), record.abiList)
        assertTrue(record.originApkSha256.isNotBlank())
        assertTrue(File(record.originApkPath).exists())
        assertNotEquals(originApk.absolutePath, record.originApkPath)
    }

    @Test
    fun `importFromMetadata rejects unsafe packageName before store lookup`() {
        val originApk = File(tempDir, "origin.apk").apply { writeText("fake apk") }
        var loadCalled = false
        val installStore = object : InstallRecordStore {
            override fun save(record: InstallRecord): Result<String> = Result.failure(UnsupportedOperationException())
            override fun load(packageName: String): InstallRecord? {
                loadCalled = true
                return null
            }
            override fun listAll(): List<InstallRecord> = emptyList()
            override fun delete(packageName: String): Boolean = false
        }
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts_import_unsafe")
        )

        val result = service.importFromMetadata(
            packageName = "../evil",
            originApkPath = originApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            applicationClassName = null,
            packageLabel = "Unsafe"
        )

        assertTrue(result.isFailure)
        assertFalse(loadCalled)
    }

    @Test
    fun `ensureInstallRecord refreshes stale record when VirtualApp version changes`() {
        val originalApk = File(tempDir, "original.apk").apply { writeText("old apk") }
        val updatedApk = File(tempDir, "updated.apk").apply { writeText("new apk") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_stale"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts_stale")
        )
        assertTrue(
            service.ensureInstallRecord(
                VirtualApp(
                    packageName = "com.example.updated",
                    appName = "Updated App",
                    versionName = "1.0",
                    versionCode = 1L,
                    apkPath = originalApk.absolutePath,
                    instanceId = "",
                    minSdkVersion = 28,
                    targetSdkVersion = 36,
                    activities = listOf("com.example.MainActivity")
                )
            ).isSuccess
        )
        val firstRecord = installStore.load("com.example.updated")!!

        val result = service.ensureInstallRecord(
            VirtualApp(
                packageName = "com.example.updated",
                appName = "Updated App",
                versionName = "2.0",
                versionCode = 2L,
                apkPath = updatedApk.absolutePath,
                instanceId = "",
                minSdkVersion = 28,
                targetSdkVersion = 36,
                activities = listOf("com.example.MainActivity")
            )
        )

        assertTrue(result.isSuccess)
        val updatedRecord = installStore.load("com.example.updated")!!
        assertEquals(2L, updatedRecord.versionCode)
        assertEquals("2.0", updatedRecord.versionName)
        assertNotEquals(firstRecord.originApkSha256, updatedRecord.originApkSha256)
        assertTrue(File(updatedRecord.originApkPath).exists())
        assertEquals("new apk", File(updatedRecord.originApkPath).readText())
    }

    @Test
    fun `ensureInstallRecord rejects unsafe packageName before creating artifact`() {
        val originApk = File(tempDir, "origin.apk").apply { writeText("fake apk") }
        val artifactDir = File(tempDir, "artifacts_unsafe")
        val escapedArtifact = File(tempDir, "evil-origin.apk")
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_unsafe"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = artifactDir
        )

        val result = service.ensureInstallRecord(
            VirtualApp(
                packageName = "../evil",
                appName = "Unsafe App",
                versionName = "1.0",
                versionCode = 1L,
                apkPath = originApk.absolutePath,
                instanceId = "",
                minSdkVersion = 28,
                targetSdkVersion = 36
            )
        )

        assertTrue(result.isFailure)
        assertFalse(escapedArtifact.exists())
        assertTrue(artifactDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `importFromMetadata persists resolved component metadata`() {
        val originApk = File(tempDir, "origin.apk").apply { writeText("fake apk") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(
                    permissions = listOf("android.permission.INTERNET"),
                    activities = listOf(ComponentInfo("com.example.MainActivity", exported = true)),
                    services = listOf(ComponentInfo("com.example.SyncService")),
                    receivers = listOf(ComponentInfo("com.example.BootReceiver")),
                    providers = listOf(ComponentInfo("com.example.ProbeProvider"))
                )
            }
        )

        val result = service.importFromMetadata(
            packageName = "com.example.app",
            originApkPath = originApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            applicationClassName = "com.example.App",
            packageLabel = "Example"
        )

        assertTrue(result.isSuccess)
        val record = installStore.load("com.example.app")!!
        assertEquals(listOf("android.permission.INTERNET"), record.permissions)
        assertEquals(listOf(ComponentInfo("com.example.MainActivity", exported = true)), record.activities)
        assertEquals(listOf(ComponentInfo("com.example.SyncService")), record.services)
        assertEquals(listOf(ComponentInfo("com.example.BootReceiver")), record.receivers)
        assertEquals(listOf(ComponentInfo("com.example.ProbeProvider")), record.providers)
    }

    @Test
    fun `importFromMetadata refreshes existing record when component metadata was missing`() {
        val originApk = File(tempDir, "origin.apk").apply { writeText("fake apk") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs"))
        val initialService = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts")
        )
        assertTrue(
            initialService.importFromMetadata(
                packageName = "com.example.app",
                originApkPath = originApk.absolutePath,
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                applicationClassName = "com.example.App",
                packageLabel = "Example"
            ).isSuccess
        )
        assertEquals(emptyList(), installStore.load("com.example.app")!!.activities)

        val refreshingService = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(
                    activities = listOf(ComponentInfo("com.example.MainActivity", exported = true))
                )
            }
        )
        val refreshed = refreshingService.importFromMetadata(
            packageName = "com.example.app",
            originApkPath = originApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            applicationClassName = "com.example.App",
            packageLabel = "Example"
        )

        assertTrue(refreshed.isSuccess)
        assertEquals(
            listOf(ComponentInfo("com.example.MainActivity", exported = true)),
            installStore.load("com.example.app")!!.activities
        )
    }
}

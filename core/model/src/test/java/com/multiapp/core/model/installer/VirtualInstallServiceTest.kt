package com.multiapp.core.model.installer

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
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

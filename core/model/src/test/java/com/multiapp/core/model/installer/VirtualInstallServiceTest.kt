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
}

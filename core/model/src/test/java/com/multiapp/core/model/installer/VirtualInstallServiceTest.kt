package com.multiapp.core.model.installer

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `ensureInstallRecord enriches picker metadata from resolver`() {
        val originApk = File(tempDir, "origin-resolved.apk").apply { writeText("fake apk") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_resolved"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts_resolved"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(
                    permissions = listOf("android.permission.INTERNET"),
                    activities = listOf(ComponentInfo("com.example.resolved.MainActivity", exported = true)),
                    services = listOf(ComponentInfo("com.example.resolved.SyncService")),
                    receivers = listOf(ComponentInfo("com.example.resolved.BootReceiver")),
                    providers = listOf(ComponentInfo("com.example.resolved.DataProvider"))
                )
            }
        )
        val app = VirtualApp(
            packageName = "com.example.resolved",
            appName = "Resolved App",
            versionName = "1.0",
            versionCode = 1L,
            apkPath = originApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36
        )

        val importResult = service.ensureInstallRecord(app)

        assertTrue(importResult.isSuccess)
        val record = installStore.load(app.packageName)!!
        assertEquals(listOf("android.permission.INTERNET"), record.permissions)
        assertEquals(
            listOf(ComponentInfo("com.example.resolved.MainActivity", exported = true)),
            record.activities
        )
        assertEquals(listOf(ComponentInfo("com.example.resolved.SyncService")), record.services)
        assertEquals(listOf(ComponentInfo("com.example.resolved.BootReceiver")), record.receivers)
        assertEquals(listOf(ComponentInfo("com.example.resolved.DataProvider")), record.providers)
    }

    @Test
    fun `ensureInstallRecord copies split apks into record and manifest`() {
        val originApk = File(tempDir, "origin-split.apk").apply { writeText("base apk") }
        val featureSplit = File(tempDir, "feature.apk").apply { writeText("feature split") }
        val densitySplit = File(tempDir, "density.apk").apply { writeText("density split") }
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_split"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "artifacts_split")
        )
        val splitNames = listOf("config.feature", "config.xxhdpi")
        val app = VirtualApp(
            packageName = "com.example.split",
            appName = "Split App",
            versionName = "3.4.5",
            versionCode = 345L,
            apkPath = originApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36,
            splitApkPaths = listOf(featureSplit.absolutePath, densitySplit.absolutePath),
            splitPublicSourceDirs = listOf(featureSplit.absolutePath, densitySplit.absolutePath),
            splitNames = splitNames,
            hasSplitApks = true,
            isolatedSplits = true
        )

        val importResult = service.ensureInstallRecord(app)

        assertTrue(importResult.isSuccess)
        val result = importResult.getOrThrow()
        val record = installStore.load(app.packageName)!!
        val expectedSplitSha256s = listOf(sha256(featureSplit), sha256(densitySplit))
        assertEquals(2, record.splitApkPaths.size)
        assertEquals(record.splitApkPaths, record.splitPublicSourceDirs)
        assertEquals(splitNames, record.splitNames)
        assertEquals(expectedSplitSha256s, record.splitApkSha256s)
        assertEquals(true, record.isolatedSplits)
        record.splitApkPaths.forEachIndexed { index, copiedPath ->
            val copiedSplit = File(copiedPath)
            assertTrue(copiedSplit.exists())
            assertNotEquals(app.splitApkPaths[index], copiedPath)
        }

        assertEquals(record.splitApkPaths, result.manifest.splitApks.map { it.path })
        assertEquals(expectedSplitSha256s, result.manifest.splitApks.map { it.sha256 })
        assertEquals(splitNames, result.manifest.splitApks.map { it.splitName })
        assertEquals(record.splitApkPaths, result.packageRecord.installManifest!!.splitApks.map { it.path })
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
            artifactDir = File(tempDir, "artifacts_stale"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(signerSha256Digests = listOf("same-signer"))
            }
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
        assertEquals(firstRecord.installTimeMs, updatedRecord.installTimeMs)
        assertTrue(updatedRecord.updatedAtMs > firstRecord.updatedAtMs)
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
    fun `deleteInstallRecord removes base and split artifacts after deleting the record`() {
        val originApk = File(tempDir, "delete-origin.apk").apply { writeText("base") }
        val splitApk = File(tempDir, "delete-split.apk").apply { writeText("split") }
        val installStore = JsonInstallRecordStore(File(tempDir, "delete-installs"))
        val artifactDir = File(tempDir, "delete-artifacts")
        val service = ProductionVirtualInstallService(installStore, artifactDir)
        service.ensureInstallRecord(
            VirtualApp(
                packageName = "com.example.delete",
                appName = "Delete",
                versionName = "1.0",
                versionCode = 1L,
                apkPath = originApk.absolutePath,
                instanceId = "",
                minSdkVersion = 28,
                targetSdkVersion = 35,
                splitApkPaths = listOf(splitApk.absolutePath),
                splitNames = listOf("config")
            )
        ).getOrThrow()
        val record = installStore.load("com.example.delete")!!
        val artifacts = record.codeSourceDirs.map(::File)
        assertTrue(artifacts.all { it.isFile })

        assertTrue(service.deleteInstallRecord("com.example.delete"))

        assertNull(installStore.load("com.example.delete"))
        assertTrue(artifacts.none { it.exists() })
        assertTrue(artifactDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `deleteInstallRecord restores staged artifact when record deletion fails`() {
        val originApk = File(tempDir, "restore-origin.apk").apply { writeText("base") }
        val realStore = JsonInstallRecordStore(File(tempDir, "restore-installs"))
        val failingDeleteStore = object : InstallRecordStore by realStore {
            override fun delete(packageName: String): Boolean = false
        }
        val service = ProductionVirtualInstallService(
            installRecordStore = failingDeleteStore,
            artifactDir = File(tempDir, "restore-artifacts")
        )
        service.importFromMetadata(
            packageName = "com.example.restore",
            originApkPath = originApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            packageLabel = "Restore"
        ).getOrThrow()
        val record = realStore.load("com.example.restore")!!
        val artifact = File(record.originApkPath)

        assertFalse(service.deleteInstallRecord("com.example.restore"))

        assertNotNull(realStore.load("com.example.restore"))
        assertTrue(artifact.isFile)
        assertEquals("base", artifact.readText())
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
                    activities = listOf(
                        ComponentInfo(
                            "com.example.MainActivity",
                            exported = true,
                            metaData = mapOf("mode" to VirtualMetaDataValue.string("main"))
                        )
                    ),
                    services = listOf(ComponentInfo("com.example.SyncService")),
                    receivers = listOf(ComponentInfo("com.example.BootReceiver")),
                    providers = listOf(ComponentInfo("com.example.ProbeProvider")),
                    applicationMetaData = mapOf("enabled" to VirtualMetaDataValue.boolean(true)),
                    signerSha256Digests = listOf("signer-sha")
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
        assertEquals("main", record.activities.single().metaData.getValue("mode").encodedValue)
        assertEquals(listOf(ComponentInfo("com.example.SyncService")), record.services)
        assertEquals(listOf(ComponentInfo("com.example.BootReceiver")), record.receivers)
        assertEquals(listOf(ComponentInfo("com.example.ProbeProvider")), record.providers)
        assertEquals(true, record.applicationMetaData.getValue("enabled").encodedValue.toBoolean())
        assertEquals(listOf("signer-sha"), record.signerSha256Digests)
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
        val installed = installStore.load("com.example.app")!!
        assertTrue(
            installStore.save(
                installed.copy(
                    originCertSha256 = "current-signer",
                    signerSha256Digests = listOf("old-signer", "current-signer")
                )
            ).isSuccess
        )

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
        assertEquals(
            listOf("old-signer", "current-signer"),
            installStore.load("com.example.app")!!.signerSha256Digests
        )
    }

    @Test
    fun `refreshInstallRecord rejects downgrade without mutating record or artifacts`() {
        val oldApk = File(tempDir, "downgrade-old.apk").apply { writeText("old generation") }
        val downgradeApk = File(tempDir, "downgrade-new.apk").apply { writeText("downgrade") }
        val installStore = JsonInstallRecordStore(File(tempDir, "downgrade-records"))
        val artifactDir = File(tempDir, "downgrade-artifacts")
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = artifactDir,
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(signerSha256Digests = listOf("stable-signer"))
            }
        )
        val installed = VirtualApp(
            packageName = "com.example.downgrade",
            appName = "Downgrade",
            versionName = "2.0",
            versionCode = 2L,
            apkPath = oldApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36
        )
        service.ensureInstallRecord(installed).getOrThrow()
        val before = installStore.load(installed.packageName)!!
        val artifactNames = artifactDir.listFiles().orEmpty().map(File::getName).sorted()

        val result = service.refreshInstallRecord(
            installed.copy(
                versionName = "1.0",
                versionCode = 1L,
                apkPath = downgradeApk.absolutePath
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("package_downgrade_rejected"))
        assertEquals(before, installStore.load(installed.packageName))
        assertEquals(artifactNames, artifactDir.listFiles().orEmpty().map(File::getName).sorted())
    }

    @Test
    fun `refreshInstallRecord rejects same version with changed base digest`() {
        val oldApk = File(tempDir, "same-version-old.apk").apply { writeText("old content") }
        val changedApk = File(tempDir, "same-version-changed.apk").apply { writeText("changed content") }
        val installStore = JsonInstallRecordStore(File(tempDir, "same-version-records"))
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "same-version-artifacts"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(signerSha256Digests = listOf("stable-signer"))
            }
        )
        val installed = VirtualApp(
            packageName = "com.example.sameversion",
            appName = "Same Version",
            versionName = "1.0",
            versionCode = 1L,
            apkPath = oldApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36
        )
        service.ensureInstallRecord(installed).getOrThrow()
        val before = installStore.load(installed.packageName)!!

        val result = service.refreshInstallRecord(installed.copy(apkPath = changedApk.absolutePath))

        assertTrue(result.isFailure)
        assertEquals("same_version_content_changed", result.exceptionOrNull()?.message)
        assertEquals(before, installStore.load(installed.packageName))
    }

    @Test
    fun `refreshInstallRecord rejects same version signer discontinuity`() {
        val originApk = File(tempDir, "same-version-signer.apk").apply { writeText("same content") }
        val installStore = JsonInstallRecordStore(File(tempDir, "same-signer-records"))
        var signer = "original-signer"
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "same-signer-artifacts"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(signerSha256Digests = listOf(signer))
            }
        )
        val installed = VirtualApp(
            packageName = "com.example.samesigner",
            appName = "Same Signer",
            versionName = "1.0",
            versionCode = 1L,
            apkPath = originApk.absolutePath,
            instanceId = "",
            minSdkVersion = 28,
            targetSdkVersion = 36
        )
        service.ensureInstallRecord(installed).getOrThrow()
        val before = installStore.load(installed.packageName)!!
        signer = "replacement-signer"

        val result = service.refreshInstallRecord(installed)

        assertTrue(result.isFailure)
        assertEquals("signing_identity_mismatch", result.exceptionOrNull()?.message)
        assertEquals(before, installStore.load(installed.packageName))
    }

    @Test
    fun `importFromMetadata cannot bypass package generation guards`() {
        val oldApk = File(tempDir, "legacy-old.apk").apply { writeText("old generation") }
        val downgradeApk = File(tempDir, "legacy-downgrade.apk").apply { writeText("downgrade") }
        val installStore = JsonInstallRecordStore(File(tempDir, "legacy-guard-records"))
        var activityName = "com.example.LegacyMain"
        val service = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = File(tempDir, "legacy-guard-artifacts"),
            metadataResolver = InstallMetadataResolver { _, _ ->
                InstallMetadata(
                    activities = listOf(ComponentInfo(activityName)),
                    signerSha256Digests = listOf("stable-signer")
                )
            }
        )
        service.importFromMetadata(
            packageName = "com.example.legacyguard",
            originApkPath = oldApk.absolutePath,
            versionCode = 2L,
            versionName = "2.0",
            targetSdk = 36,
            minSdk = 28,
            applicationClassName = null,
            packageLabel = "Legacy Guard"
        ).getOrThrow()
        val before = installStore.load("com.example.legacyguard")!!
        activityName = "com.example.ReplacementMain"

        val result = service.importFromMetadata(
            packageName = "com.example.legacyguard",
            originApkPath = downgradeApk.absolutePath,
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            applicationClassName = null,
            packageLabel = "Legacy Guard"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("package_downgrade_rejected"))
        assertEquals(before, installStore.load("com.example.legacyguard"))
    }

    /**
     * 验证 tombstone 清理失败时 deleteInstallRecord 返回 false 且保留 tombstone。
     *
     * 依赖"非空目录删除失败"的 POSIX 语义；Windows 上 JDK 的 File.delete()
     * 对非空目录返回 true 并整体删除（平台行为差异），此断言仅在 POSIX 平台有效。
     */
    @EnabledOnOs(OS.LINUX, OS.MAC)
    @Test
    fun `deleteInstallRecord reports tombstone deletion failure`() {
        val installStore = JsonInstallRecordStore(File(tempDir, "tombstone-records"))
        val artifactDir = File(tempDir, "tombstone-artifacts").apply { mkdirs() }
        val corruptArtifact = File(artifactDir, "corrupt-artifact").apply { mkdirs() }
        File(corruptArtifact, "child").writeText("prevents directory deletion")
        val packageName = "com.example.tombstone"
        installStore.save(
            InstallRecord(
                packageName = packageName,
                originApkPath = corruptArtifact.absolutePath,
                originApkSha256 = "corrupt",
                originCertSha256 = "corrupt-cert",
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                installTimeMs = 1L
            )
        ).getOrThrow()
        val service = ProductionVirtualInstallService(installStore, artifactDir)

        val result = service.deleteInstallRecord(packageName)

        assertFalse(result)
        assertNull(installStore.load(packageName))
        assertTrue(
            artifactDir.listFiles().orEmpty().any { candidate ->
                candidate.name.startsWith(".corrupt-artifact.delete-") &&
                    File(candidate, "child").exists()
            }
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

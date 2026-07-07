package com.multiapp.core.loader

import android.content.pm.ActivityInfo
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualPackageSnapshotFactoryTest {

    @Test
    fun `factory creates stable snapshot from install instance and resolved package`() {
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = ResolvedPackage(
                packageName = "com.test.minimal",
                versionCode = 1,
                versionName = "ignored",
                targetSdk = 36,
                minSdk = 28,
                applicationClassName = "com.test.minimal.MinimalApp",
                metaData = mapOf("channel" to "play"),
                launcherActivityName = "com.test.minimal.MainActivity",
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.MainActivity",
                        exported = true,
                        processName = ":ui",
                        taskAffinity = "com.test.minimal.main",
                        themeId = 0x7f010002,
                        screenOrientation = "portrait",
                        configChanges = "orientation|screenSize|keyboardHidden",
                        permission = "com.test.permission.START",
                        metaData = mapOf("activity.mode" to "main")
                    )
                ),
                services = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.SyncService",
                        processName = ":sync",
                        permission = "com.test.permission.SYNC",
                        metaData = mapOf("service.mode" to "sync")
                    )
                ),
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.ProbeProvider",
                        exported = false,
                        authorities = listOf("com.test.minimal.probe"),
                        permission = "com.test.permission.PROBE",
                        grantUriPermissions = true,
                        metaData = mapOf("provider.mode" to "probe")
                    )
                ),
                applicationLabel = "MinimalTest"
            ),
            nativeLibraryDir = "/data/inst/lib"
        )

        assertEquals("inst-001", snapshot.instanceId)
        assertEquals("com.test.minimal", snapshot.originPackageName)
        assertEquals("com.multiapp.instance.abc", snapshot.virtualPackageName)
        assertEquals("MinimalTest", snapshot.applicationLabel)
        assertEquals("/data/apks/minimal.apk", snapshot.sourceDir)
        assertEquals("/data/inst", snapshot.dataDir)
        assertEquals("/data/inst/lib", snapshot.nativeLibraryDir)
        assertEquals("play", snapshot.metaData["channel"])
        assertEquals(":ui", snapshot.activities.single().processName)
        assertEquals("com.test.minimal.main", snapshot.activities.single().taskAffinity)
        assertEquals(0x7f010002, snapshot.activities.single().themeId)
        assertEquals("portrait", snapshot.activities.single().screenOrientation)
        assertEquals("orientation|screenSize|keyboardHidden", snapshot.activities.single().configChanges)
        assertEquals("com.test.permission.START", snapshot.activities.single().permission)
        assertEquals("com.test.permission.PROBE", snapshot.providers.single().permission)
        assertEquals(true, snapshot.providers.single().grantUriPermissions)
        assertEquals("main", snapshot.activities.single().metaData["activity.mode"])
        assertTrue(snapshot.matchesPackageName("com.test.minimal"))
        assertTrue(snapshot.matchesPackageName("com.multiapp.instance.abc"))

        val infoSnapshot = snapshot.copy(
            processName = "com.test.minimal:worker",
            taskAffinity = "com.test.minimal.task",
            themeId = 0x7f010001,
            metaData = emptyMap(),
            activities = snapshot.activities.map { it.copy(metaData = emptyMap()) },
            services = snapshot.services.map { it.copy(metaData = emptyMap()) },
            providers = snapshot.providers.map { it.copy(metaData = emptyMap()) }
        )
        val packageInfo = VirtualPackageInfoFactory.packageInfo(infoSnapshot)
        val appInfo = requireNotNull(packageInfo.applicationInfo)
        val activityInfo = requireNotNull(packageInfo.activities).single()
        val serviceInfo = requireNotNull(packageInfo.services).single()
        val providerInfo = requireNotNull(packageInfo.providers).single()

        assertEquals("com.test.minimal:worker", appInfo.processName)
        assertEquals("com.test.minimal.task", appInfo.taskAffinity)
        assertEquals(0x7f010001, appInfo.theme)
        assertEquals(":ui", activityInfo.processName)
        assertEquals("com.test.minimal.main", activityInfo.taskAffinity)
        assertEquals(0x7f010002, activityInfo.theme)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activityInfo.screenOrientation)
        assertEquals(
            ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
            activityInfo.configChanges
        )
        assertEquals("com.test.permission.START", activityInfo.permission)
        assertEquals(":sync", serviceInfo.processName)
        assertEquals("com.test.permission.SYNC", serviceInfo.permission)
        assertEquals("com.test.minimal.probe", providerInfo.authority)
        assertEquals("com.test.permission.PROBE", providerInfo.readPermission)
        assertEquals("com.test.permission.PROBE", providerInfo.writePermission)
        assertEquals(true, providerInfo.grantUriPermissions)
    }

    @Test
    fun `activity info falls back to application theme when activity theme is missing`() {
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = ResolvedPackage(
                packageName = "com.test.minimal",
                versionCode = 1,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                themeId = 0x7f010010,
                launcherActivityName = "com.test.minimal.MainActivity",
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.MainActivity",
                        exported = true,
                        themeId = 0
                    )
                )
            ),
            nativeLibraryDir = null
        )

        val activityInfo = VirtualPackageInfoFactory.activityInfo(snapshot, snapshot.activities.single())

        assertEquals(0x7f010010, activityInfo.theme)
    }

    @Test
    fun `findActivity resolves alias target activity theme`() {
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = ResolvedPackage(
                packageName = "com.test.minimal",
                versionCode = 1,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                themeId = 0x7f010010,
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.LauncherAlias",
                        exported = true,
                        targetActivityName = "com.test.minimal.RealActivity",
                        themeId = 0x7f010020
                    )
                )
            ),
            nativeLibraryDir = null
        )

        val activityInfo = VirtualPackageInfoFactory.findActivity(
            snapshot,
            "com.test.minimal.RealActivity"
        )

        assertEquals(0x7f010020, activityInfo?.theme)
    }

    @Test
    fun `factory leaves launcher null when launcher metadata is missing`() {
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = ResolvedPackage(
                packageName = "com.test.minimal",
                versionCode = 1,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                activities = listOf(
                    ResolvedComponent(name = "com.test.minimal.InternalActivity", exported = false),
                    ResolvedComponent(name = "com.test.minimal.EntryActivity", exported = true)
                )
            ),
            nativeLibraryDir = null
        )

        assertNull(snapshot.launcherActivityName)
    }

    @Test
    fun `factory preserves explicit launcher alias instead of replacing it with target`() {
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = ResolvedPackage(
                packageName = "com.test.minimal",
                versionCode = 1,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                launcherActivityName = "com.test.minimal.LauncherAlias",
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.LauncherAlias",
                        exported = true,
                        targetActivityName = "com.test.minimal.RealActivity"
                    )
                )
            ),
            nativeLibraryDir = null
        )

        assertEquals("com.test.minimal.LauncherAlias", snapshot.launcherActivityName)
        assertEquals("com.test.minimal.RealActivity", snapshot.activities.single().targetActivityName)
    }

    @Test
    fun `factory preserves fallback launcher alias instead of replacing it with target`() {
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = ResolvedPackage(
                packageName = "com.test.minimal",
                versionCode = 1,
                versionName = "1.0",
                targetSdk = 36,
                minSdk = 28,
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.LauncherAlias",
                        exported = true,
                        intentFilters = listOf(
                            "android.intent.action.MAIN",
                            "android.intent.category.LAUNCHER"
                        ),
                        targetActivityName = "com.test.minimal.RealActivity"
                    )
                )
            ),
            nativeLibraryDir = null
        )

        assertEquals("com.test.minimal.LauncherAlias", snapshot.launcherActivityName)
        assertEquals("com.test.minimal.RealActivity", snapshot.activities.single().targetActivityName)
    }

    @Test
    fun `registry resolves snapshot by instance origin and virtual package`() {
        val registry = VirtualPackageRegistry()
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instanceRecord(),
            installRecord = installRecord(),
            resolvedPackage = null,
            nativeLibraryDir = null
        )

        registry.register(snapshot)

        assertEquals(snapshot, registry.getByInstanceId("inst-001"))
        assertEquals(snapshot, registry.getByPackageName("com.test.minimal"))
        assertEquals(snapshot, registry.getByPackageName("com.multiapp.instance.abc"))
    }

    private fun instanceRecord() = VirtualInstanceRecord(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        displayName = "MinimalTest",
        dataRoot = "/data/inst",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        state = InstanceState.READY
    )

    private fun installRecord() = InstallRecord(
        packageName = "com.test.minimal",
        originApkPath = "/data/apks/minimal.apk",
        originApkSha256 = "apk-sha",
        originCertSha256 = "cert-sha",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 35,
        minSdk = 28,
        packageLabel = "Install Label",
        installTimeMs = 1L
    )
}

package com.multiapp.core.instance

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstalledAppRepositoryTest {

    private lateinit var packageManager: PackageManager

    @BeforeEach
    fun setUp() {
        packageManager = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `listInstalledApps filters host app sorts and caches`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returns listOf(
            packageInfo("com.multiapp.app", "Host", launcher = true),
            packageInfo("com.zeta.app", "Zeta", launcher = true),
            packageInfo("com.alpha.app", "Alpha", launcher = true)
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.zeta.app"),
            launcherResolveInfo("com.alpha.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val first = repository.listInstalledApps()
        val second = repository.listInstalledApps()

        assertEquals(listOf("Alpha", "Zeta"), first.map { it.appName })
        assertEquals(first, second)
        verify(exactly = 1) { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) }
        verify(exactly = 1) { packageManager.queryIntentActivities(any(), 0) }
        verify(exactly = 0) { packageManager.getLaunchIntentForPackage(any()) }
    }

    @Test
    fun `listInstalledApps does not cache empty result and re-queries on next call`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returnsMany listOf(
            emptyList(),
            listOf(packageInfo("com.alpha.app", "Alpha", launcher = true))
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.alpha.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val first = repository.listInstalledApps()
        val second = repository.listInstalledApps()

        assertEquals(emptyList(), first)
        assertEquals(listOf("Alpha"), second.map { it.appName })
        // 空结果未被缓存，第二次调用仍会重新查询
        verify(exactly = 2) { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) }
    }

    @Test
    fun `listInstalledApps forceRefresh bypasses non-empty cache`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returnsMany listOf(
            listOf(packageInfo("com.alpha.app", "Alpha", launcher = true)),
            listOf(packageInfo("com.beta.app", "Beta", launcher = true))
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.alpha.app"),
            launcherResolveInfo("com.beta.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val cached = repository.listInstalledApps()
        val refreshed = repository.listInstalledApps(forceRefresh = true)

        assertEquals(listOf("Alpha"), cached.map { it.appName })
        assertEquals(listOf("Beta"), refreshed.map { it.appName })
        verify(exactly = 2) { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) }
    }

    @Test
    fun `recommendedCloneTargets keeps only launchable non-system apps`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returns listOf(
            packageInfo("com.user.app", "User", launcher = true),
            packageInfo("com.no.launcher", "No Launcher", launcher = false),
            packageInfo("com.system.app", "System", launcher = true, system = true)
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.user.app"),
            launcherResolveInfo("com.system.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val recommended = repository.recommendedCloneTargets()

        assertEquals(listOf("com.user.app"), recommended.map { it.packageName })
        assertTrue(recommended.single().isCloneCandidate())
    }

    @Test
    fun `listInstalledApps does not load Android icons into model`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returns listOf(
            packageInfo("com.alpha.app", "Alpha", launcher = true)
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.alpha.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val apps = repository.listInstalledApps()

        assertEquals("com.alpha.app", apps.single().packageName)
        verify(exactly = 0) { packageManager.getApplicationIcon(any<String>()) }
    }

    @Test
    fun `listInstalledApps maps split apk metadata from application info`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returns listOf(
            packageInfo(
                packageName = "com.split.app",
                label = "Split",
                launcher = true,
                splitSourceDirs = arrayOf(
                    "/data/app/com.split.app/split_config.arm64.apk",
                    "/data/app/com.split.app/split_config.xxhdpi.apk"
                ),
                splitPublicSourceDirs = arrayOf(
                    "/mnt/asec/com.split.app/split_config.arm64.apk",
                    "/mnt/asec/com.split.app/split_config.xxhdpi.apk"
                ),
                splitNames = arrayOf("config.arm64", "config.xxhdpi")
            )
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.split.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val app = repository.listInstalledApps().single()

        assertEquals(
            listOf(
                "/data/app/com.split.app/split_config.arm64.apk",
                "/data/app/com.split.app/split_config.xxhdpi.apk"
            ),
            app.splitApkPaths
        )
        assertEquals(
            listOf(
                "/mnt/asec/com.split.app/split_config.arm64.apk",
                "/mnt/asec/com.split.app/split_config.xxhdpi.apk"
            ),
            app.splitPublicSourceDirs
        )
        assertEquals(listOf("config.arm64", "config.xxhdpi"), app.splitNames)
        assertTrue(app.hasSplitApks)
    }

    @Test
    fun `listInstalledApps maps package identity fields from package info`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returns listOf(
            packageInfo(
                packageName = "com.shared.app",
                label = "Shared",
                launcher = true,
                debuggable = true,
                uid = 10_123,
                sharedUserId = "android.uid.shared",
                sharedUserLabel = 0x7f01_0203
            )
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.shared.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val app = repository.listInstalledApps().single()

        assertTrue(app.isDebuggable)
        assertEquals("android.uid.shared", app.sharedUserId)
        assertEquals(0x7f01_0203, app.sharedUserLabel)
    }

    @Test
    fun `listInstalledApps drops blank shared user identity`() {
        every { packageManager.getInstalledPackages(PackageManager.GET_META_DATA) } returns listOf(
            packageInfo(
                packageName = "com.plain.app",
                label = "Plain",
                launcher = true,
                uid = 10_456,
                sharedUserId = " ",
                sharedUserLabel = 0
            )
        )
        every { packageManager.queryIntentActivities(any(), 0) } returns listOf(
            launcherResolveInfo("com.plain.app")
        )

        val repository = InstalledAppRepository(
            packageManagerProvider = { packageManager },
            hostPackageName = "com.multiapp.app",
            launcherIntentFactory = { launcherQueryIntent() }
        )

        val app = repository.listInstalledApps().single()

        assertFalse(app.isDebuggable)
        assertNull(app.sharedUserId)
        assertEquals(0, app.sharedUserLabel)
    }

    @Test
    fun `isCloneCandidate rejects system or no launcher apps`() {
        assertTrue(virtualApp(mainActivity = "Main", system = false).isCloneCandidate())
        assertFalse(virtualApp(mainActivity = null, system = false).isCloneCandidate())
        assertFalse(virtualApp(mainActivity = "Main", system = true).isCloneCandidate())
    }

    private fun packageInfo(
        packageName: String,
        label: String,
        launcher: Boolean,
        system: Boolean = false,
        debuggable: Boolean = false,
        uid: Int = 0,
        sharedUserId: String? = null,
        sharedUserLabel: Int = 0,
        splitSourceDirs: Array<String>? = null,
        splitPublicSourceDirs: Array<String>? = null,
        splitNames: Array<String>? = null
    ): PackageInfo {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = label
            sourceDir = "/data/app/$packageName/base.apk"
            targetSdkVersion = 36
            minSdkVersion = 28
            this.uid = uid
            flags = (if (system) ApplicationInfo.FLAG_SYSTEM else 0) or
                (if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0)
            this.splitSourceDirs = splitSourceDirs
            this.splitPublicSourceDirs = splitPublicSourceDirs
            this.splitNames = splitNames
        }
        return PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = appInfo
            versionName = "1.0"
            versionCode = 1
            this.sharedUserId = sharedUserId
            this.sharedUserLabel = sharedUserLabel
            if (!launcher) {
                activities = emptyArray()
            }
        }
    }

    private fun launcherResolveInfo(packageName: String): ResolveInfo {
        return ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.MainActivity"
            }
        }
    }

    private fun launcherQueryIntent(): Intent = mockk(relaxed = true)

    private fun virtualApp(mainActivity: String?, system: Boolean): com.multiapp.core.model.VirtualApp {
        return com.multiapp.core.model.VirtualApp(
            packageName = "com.example",
            appName = "Example",
            apkPath = "/tmp/example.apk",
            instanceId = "",
            mainActivity = mainActivity,
            isSystemApp = system
        )
    }
}

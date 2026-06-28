package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.mockk.mockk
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivityThreadLoadedApkInstallerTest {

    @Test
    fun `install patches loaded apk and registers package aliases`() {
        val activityThread = FakeActivityThread()
        val loadedApk = FakeLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
        }
        val resources = mockk<Resources>(relaxed = true)
        val classLoader = ClassLoader.getSystemClassLoader()

        val result = ActivityThreadLoadedApkInstaller.install(
            activityThread = activityThread,
            loadedApk = loadedApk,
            state = LoadedApkRuntimeState(
                packageName = "com.test.minimal",
                applicationInfo = appInfo,
                resources = resources,
                classLoader = classLoader
            ),
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc", "com.test.minimal")
        )

        assertEquals(listOf("com.test.minimal", "com.multiapp.instance.abc"), result.aliases)
        assertTrue(result.installedAliasCount >= 4)
        assertSame(appInfo, loadedApk.applicationInfo())
        assertSame(resources, loadedApk.resources())
        assertSame(classLoader, loadedApk.classLoader())
        assertSame(loadedApk, activityThread.loadedApkFrom("mPackages", "com.test.minimal"))
        assertSame(loadedApk, activityThread.loadedApkFrom("mPackages", "com.multiapp.instance.abc"))
        assertSame(loadedApk, activityThread.loadedApkFrom("mResourcePackages", "com.test.minimal"))
        assertSame(loadedApk, activityThread.loadedApkFrom("mResourcePackages", "com.multiapp.instance.abc"))
    }

    @Test
    fun `install skips patching host loaded apk when guard matches host package`() {
        val activityThread = FakeActivityThread()
        val loadedApk = FakeLoadedApk().applyHostPackage("com.multiapp.app")
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
        }

        val result = ActivityThreadLoadedApkInstaller.install(
            activityThread = activityThread,
            loadedApk = loadedApk,
            state = LoadedApkRuntimeState(
                packageName = "com.test.minimal",
                applicationInfo = appInfo,
                resources = mockk(relaxed = true),
                classLoader = ClassLoader.getSystemClassLoader()
            ),
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc"),
            hostPackageName = "com.multiapp.app"
        )

        assertTrue(result.skipped)
        assertEquals("HOST_LOADED_APK_GUARD:com.multiapp.app", result.skippedReason)
        assertEquals(0, result.installedAliasCount)
        assertEquals("com.multiapp.app", loadedApk.packageName())
        assertEquals(null, activityThread.loadedApkFrom("mPackages", "com.test.minimal"))
    }

    private class FakeActivityThread {
        private val mPackages = linkedMapOf<Any?, Any?>()
        private val mResourcePackages = linkedMapOf<Any?, Any?>()

        fun loadedApkFrom(fieldName: String, packageName: String): Any? {
            val map = when (fieldName) {
                "mPackages" -> mPackages
                "mResourcePackages" -> mResourcePackages
                else -> error("unknown field: $fieldName")
            }
            return (map[packageName] as? WeakReference<*>)?.get()
        }
    }

    @Suppress("unused")
    private class FakeLoadedApk {
        private var mApplicationInfo: ApplicationInfo? = null
        private var mResources: Resources? = null
        private var mClassLoader: ClassLoader? = null
        private var mPackageName: String? = null
        private var mAppDir: String? = null
        private var mResDir: String? = null

        fun applicationInfo(): ApplicationInfo? = mApplicationInfo
        fun resources(): Resources? = mResources
        fun classLoader(): ClassLoader? = mClassLoader
        fun packageName(): String? = mPackageName

        fun applyHostPackage(packageName: String): FakeLoadedApk {
            mPackageName = packageName
            mApplicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            return this
        }
    }
}

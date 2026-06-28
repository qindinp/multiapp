package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoadedApkBridgeTest {

    @Test
    fun `inspect reads loaded apk package identity`() {
        val target = FakeLoadedApk().applyPackage("com.multiapp.app")

        val inspection = LoadedApkBridge.inspect(target)

        assertEquals("com.multiapp.app", inspection.packageName)
        assertEquals("com.multiapp.app", inspection.applicationInfoPackageName)
        assertTrue(inspection.matchesPackage("com.multiapp.app"))
    }

    @Test
    fun `patch replaces LoadedApk-like runtime fields`() {
        val target = FakeLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
        }
        val resources = mockk<Resources>(relaxed = true)
        val classLoader = ClassLoader.getSystemClassLoader()

        val result = LoadedApkBridge.patch(
            target = target,
            state = LoadedApkRuntimeState(
                packageName = "com.test.minimal",
                applicationInfo = appInfo,
                resources = resources,
                classLoader = classLoader
            )
        )

        assertTrue("mApplicationInfo" in result.patchedFields)
        assertTrue("mResources" in result.patchedFields)
        assertTrue("mClassLoader" in result.patchedFields)
        assertTrue("mPackageName" in result.patchedFields)
        assertSame(appInfo, target.applicationInfo())
        assertSame(resources, target.resources())
        assertSame(classLoader, target.classLoader())
        assertEquals("com.test.minimal", target.packageName())
        assertEquals("/data/app/minimal.apk", target.appDir())
        assertEquals("/data/app/minimal.apk", target.resDir())
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
        fun appDir(): String? = mAppDir
        fun resDir(): String? = mResDir

        fun applyPackage(packageName: String): FakeLoadedApk {
            mPackageName = packageName
            mApplicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            return this
        }
    }
}

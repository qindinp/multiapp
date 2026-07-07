package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.mockk.mockk
import java.io.File
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
        val appInfo = FakeProtectedStorageApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
            dataDir = "/data/user/0/com.multiapp/instance/minimal"
            credentialProtectedDataDir = "/data/user/0/com.multiapp/instance/minimal/credential"
            deviceProtectedDataDir = "/data/user_de/0/com.multiapp/instance/minimal/device"
            nativeLibraryDir = "/data/user/0/com.multiapp/instance/minimal/lib"
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
        assertTrue("mBaseClassLoader" in result.patchedFields)
        assertTrue("mPackageName" in result.patchedFields)
        assertTrue("mLibDir" in result.patchedFields)
        assertTrue("mDataDir" in result.patchedFields)
        assertTrue("mDataDirFile" in result.patchedFields)
        assertSame(appInfo, target.applicationInfo())
        assertSame(resources, target.resources())
        assertSame(classLoader, target.classLoader())
        assertSame(classLoader, target.baseClassLoader())
        assertEquals("com.test.minimal", target.packageName())
        assertEquals("/data/app/minimal.apk", target.appDir())
        assertEquals("/data/app/minimal.apk", target.resDir())
        assertEquals("/data/user/0/com.multiapp/instance/minimal/lib", target.libDir())
        assertEquals("/data/user/0/com.multiapp/instance/minimal", target.dataDir())
        assertEquals(File("/data/user/0/com.multiapp/instance/minimal"), target.dataDirFile())
        assertEquals(
            File("/data/user/0/com.multiapp/instance/minimal/credential"),
            target.credentialProtectedDataDirFile()
        )
        assertEquals(
            File("/data/user_de/0/com.multiapp/instance/minimal/device"),
            target.deviceProtectedDataDirFile()
        )
    }

    @Test
    fun `patch falls back to data dir when protected storage dirs are absent`() {
        val target = FakeLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
            dataDir = "/data/user/0/com.multiapp/instance/minimal"
            nativeLibraryDir = "/data/user/0/com.multiapp/instance/minimal/lib"
        }

        LoadedApkBridge.patch(
            target = target,
            state = LoadedApkRuntimeState(
                packageName = "com.test.minimal",
                applicationInfo = appInfo,
                resources = mockk(relaxed = true),
                classLoader = ClassLoader.getSystemClassLoader()
            )
        )

        assertEquals(File("/data/user/0/com.multiapp/instance/minimal"), target.credentialProtectedDataDirFile())
        assertEquals(File("/data/user/0/com.multiapp/instance/minimal"), target.deviceProtectedDataDirFile())
    }

    @Test
    fun `patch records skipped field reasons`() {
        val target = FakePartialLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
            dataDir = "/data/user/0/com.multiapp/instance/minimal"
        }

        val result = LoadedApkBridge.patch(
            target = target,
            state = LoadedApkRuntimeState(
                packageName = "com.test.minimal",
                applicationInfo = appInfo,
                resources = mockk(relaxed = true),
                classLoader = ClassLoader.getSystemClassLoader()
            )
        )

        assertTrue("mApplicationInfo" in result.patchedFields)
        assertTrue("mResources:FIELD_NOT_FOUND" in result.skippedFieldReasons)
        assertTrue(result.skippedFieldReasons.any { it.startsWith("mDataDir:TYPE_MISMATCH:") })
    }

    @Test
    fun `frameworkSafeNativeLibraryDir falls back to instance lib path when missing`() {
        val nativeLibraryDir = ApplicationInfoNativePathCompat.frameworkSafeNativeLibraryDir(
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            nativeLibraryDir = null
        )

        assertEquals(
            "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
            nativeLibraryDir
        )
    }

    private class FakeProtectedStorageApplicationInfo : ApplicationInfo() {
        var credentialProtectedDataDir: String? = null
        var deviceProtectedDataDir: String? = null
    }

    @Suppress("unused")
    private class FakeLoadedApk {
        private var mApplicationInfo: ApplicationInfo? = null
        private var mResources: Resources? = null
        private var mClassLoader: ClassLoader? = null
        private var mBaseClassLoader: ClassLoader? = null
        private var mPackageName: String? = null
        private var mAppDir: String? = null
        private var mResDir: String? = null
        private var mLibDir: String? = null
        private var mDataDir: String? = null
        private var mDataDirFile: File? = null
        private var mCredentialProtectedDataDirFile: File? = null
        private var mDeviceProtectedDataDirFile: File? = null

        fun applicationInfo(): ApplicationInfo? = mApplicationInfo
        fun resources(): Resources? = mResources
        fun classLoader(): ClassLoader? = mClassLoader
        fun baseClassLoader(): ClassLoader? = mBaseClassLoader
        fun packageName(): String? = mPackageName
        fun appDir(): String? = mAppDir
        fun resDir(): String? = mResDir
        fun libDir(): String? = mLibDir
        fun dataDir(): String? = mDataDir
        fun dataDirFile(): File? = mDataDirFile
        fun credentialProtectedDataDirFile(): File? = mCredentialProtectedDataDirFile
        fun deviceProtectedDataDirFile(): File? = mDeviceProtectedDataDirFile

        fun applyPackage(packageName: String): FakeLoadedApk {
            mPackageName = packageName
            mApplicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            return this
        }
    }

    @Suppress("unused")
    private class FakePartialLoadedApk {
        private var mApplicationInfo: ApplicationInfo? = null
        private var mDataDir: Int = 0
    }
}

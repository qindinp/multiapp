package com.multiapp.core.loader

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import io.mockk.mockk
import java.io.File
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            dataDir = "/data/user/0/com.multiapp/instance/minimal"
            nativeLibraryDir = "/data/user/0/com.multiapp/instance/minimal/lib"
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
        assertEquals("/data/user/0/com.multiapp/instance/minimal/lib", loadedApk.libDir())
        assertEquals("/data/user/0/com.multiapp/instance/minimal", loadedApk.dataDir())
        assertEquals(File("/data/user/0/com.multiapp/instance/minimal"), loadedApk.dataDirFile())
        assertSame(loadedApk, activityThread.loadedApkFrom("mPackages", "com.test.minimal"))
        assertSame(loadedApk, activityThread.loadedApkFrom("mPackages", "com.multiapp.instance.abc"))
        assertSame(loadedApk, activityThread.loadedApkFrom("mResourcePackages", "com.test.minimal"))
        assertSame(loadedApk, activityThread.loadedApkFrom("mResourcePackages", "com.multiapp.instance.abc"))
    }

    @Test
    fun `bindApplication patches installed loaded apk application through bridge`() {
        val activityThread = FakeActivityThread()
        val loadedApk = FakeLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
            dataDir = "/data/user/0/com.multiapp/instance/minimal"
            nativeLibraryDir = "/data/user/0/com.multiapp/instance/minimal/lib"
        }
        val state = LoadedApkRuntimeState(
            packageName = "com.test.minimal",
            applicationInfo = appInfo,
            resources = mockk(relaxed = true),
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val installResult = ActivityThreadLoadedApkInstaller.install(
            activityThread = activityThread,
            loadedApk = loadedApk,
            state = state,
            packageAliases = listOf("com.test.minimal")
        )
        val application = mockk<Application>(relaxed = true)

        val bindResult = ActivityThreadLoadedApkInstaller.bindApplication(
            activityThread = activityThread,
            installResult = installResult,
            state = state,
            application = application
        )

        assertTrue(bindResult.successful)
        assertTrue("mApplication" in bindResult.loadedApkPatchResult.patchedFields)
        assertSame(application, loadedApk.application())
        assertSame(loadedApk, activityThread.boundLoadedApk())
        assertSame(appInfo, activityThread.boundApplicationInfo())
        assertSame(application, activityThread.initialApplication())
        assertTrue(activityThread.allApplications().any { it === application })
        assertTrue("mBoundApplication.info" in bindResult.activityThreadPatchedFields)
        assertTrue("mBoundApplication.appInfo" in bindResult.activityThreadPatchedFields)
        assertTrue("mInitialApplication" in bindResult.activityThreadPatchedFields)
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

    @Test
    fun `installGuestSandbox creates LoadedApk through ActivityThread and registers aliases`() {
        val activityThread = FakeActivityThread()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
        }
        val resources = mockk<Resources>(relaxed = true)
        val classLoader = ClassLoader.getSystemClassLoader()

        val result = ActivityThreadLoadedApkInstaller.installGuestSandbox(
            activityThread = activityThread,
            state = LoadedApkRuntimeState(
                packageName = "com.test.minimal",
                applicationInfo = appInfo,
                resources = resources,
                classLoader = classLoader
            ),
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc")
        )

        assertEquals(LoadedApkInstallSource.GUEST_SANDBOX, result.source)
        assertEquals(1, activityThread.createCount)
        assertEquals(null, result.skippedReason)
        assertSame(appInfo, result.loadedApk?.let { (it as FakeLoadedApk).applicationInfo() })
        assertSame(result.loadedApk, activityThread.loadedApkFrom("mPackages", "com.test.minimal"))
        assertSame(result.loadedApk, activityThread.loadedApkFrom("mResourcePackages", "com.multiapp.instance.abc"))
    }

    @Test
    fun `guest sandbox patches virtual package identity while installing origin and virtual aliases`() {
        val activityThread = FakeActivityThread()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.multiapp.instance.abc"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001"
            nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib"
        }

        val result = ActivityThreadLoadedApkInstaller.installGuestSandbox(
            activityThread = activityThread,
            state = LoadedApkRuntimeState(
                packageName = "com.multiapp.instance.abc",
                applicationInfo = appInfo,
                resources = mockk(relaxed = true),
                classLoader = ClassLoader.getSystemClassLoader()
            ),
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc")
        )

        val loadedApk = result.loadedApk as FakeLoadedApk
        assertEquals(LoadedApkInstallSource.GUEST_SANDBOX, result.source)
        assertEquals("com.multiapp.instance.abc", loadedApk.packageName())
        assertEquals("com.multiapp.instance.abc", loadedApk.applicationInfo()?.packageName)
        assertEquals(4, result.installedAliasCount)
        assertEquals(
            listOf("com.test.minimal", "com.multiapp.instance.abc"),
            result.installedAliasesByField["mPackages"]
        )
        assertEquals(
            listOf("com.test.minimal", "com.multiapp.instance.abc"),
            result.installedAliasesByField["mResourcePackages"]
        )
    }

    @Test
    fun `findInstalledGuest reuses the bound sandbox without creating another LoadedApk`() {
        val activityThread = FakeActivityThread()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
        }
        val state = LoadedApkRuntimeState(
            packageName = "com.test.minimal",
            applicationInfo = appInfo,
            resources = mockk(relaxed = true),
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val installed = ActivityThreadLoadedApkInstaller.installGuestSandbox(
            activityThread = activityThread,
            state = state,
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc")
        )
        val application = mockk<Application>(relaxed = true)
        ActivityThreadLoadedApkInstaller.bindApplication(activityThread, installed, state, application)

        val reused = ActivityThreadLoadedApkInstaller.findInstalledGuest(
            activityThread = activityThread,
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc")
        )

        assertEquals(1, activityThread.createCount)
        assertEquals(LoadedApkInstallSource.PREWARMED_GUEST, reused?.source)
        assertSame(installed.loadedApk, reused?.loadedApk)
        assertSame(application, reused?.loadedApk?.let(LoadedApkBridge::application))
    }

    @Test
    fun `install records package map skip reasons`() {
        val activityThread = FakeActivityThreadWithoutResourcePackages()
        val loadedApk = FakeLoadedApk()
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
            packageAliases = listOf("com.test.minimal")
        )

        assertTrue(result.aliasInstallSkipped)
        assertEquals("PACKAGE_MAP_UNAVAILABLE", result.skippedAliasInstallReasonsByField["mResourcePackages"])
        assertSame(loadedApk, activityThread.loadedApkFrom("com.test.minimal"))
    }

    @Test
    fun `bind failure rolls back aliases loaded apk and ActivityThread identity`() {
        val activityThread = FakeActivityThread()
        val loadedApk = FakeLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = sourceDir
            dataDir = "/data/user/0/com.multiapp.app/files/instances/inst-001"
            nativeLibraryDir = "$dataDir/lib"
        }
        val state = LoadedApkRuntimeState(
            packageName = appInfo.packageName,
            applicationInfo = appInfo,
            resources = mockk(relaxed = true),
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val installed = ActivityThreadLoadedApkInstaller.install(
            activityThread = activityThread,
            loadedApk = loadedApk,
            state = state,
            packageAliases = listOf(appInfo.packageName, "com.multiapp.instance.abc")
        )
        val hostInitialApplication = activityThread.initialApplication()
        val incompatibleBoundApplication = FakeAppBindDataWithWrongAppInfoType(
            info = Any(),
            appInfo = "com.multiapp.app"
        )
        val hostBoundLoadedApk = incompatibleBoundApplication.info
        activityThread.replaceBoundApplication(incompatibleBoundApplication)
        val application = mockk<Application>(relaxed = true)

        val result = ActivityThreadLoadedApkInstaller.bindApplication(
            activityThread = activityThread,
            installResult = installed,
            state = state,
            application = application
        )

        assertFalse(result.successful)
        assertTrue(result.failureReasons.single().contains("appInfo"))
        assertTrue(result.rollbackResult?.success == true)
        assertNull(activityThread.loadedApkFrom("mPackages", appInfo.packageName))
        assertNull(activityThread.loadedApkFrom("mResourcePackages", "com.multiapp.instance.abc"))
        assertNull(loadedApk.applicationInfo())
        assertNull(loadedApk.application())
        assertSame(hostInitialApplication, activityThread.initialApplication())
        assertFalse(activityThread.allApplications().any { it === application })
        assertSame(hostBoundLoadedApk, incompatibleBoundApplication.info)
        assertEquals("com.multiapp.app", incompatibleBoundApplication.appInfo)
    }

    @Test
    fun `install rejects missing required LoadedApk lib field and restores aliases`() {
        val activityThread = FakeActivityThread()
        val loadedApk = FakeLoadedApkWithoutLibDir()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = sourceDir
            nativeLibraryDir = "/data/instances/inst-001/lib"
        }

        val result = ActivityThreadLoadedApkInstaller.install(
            activityThread = activityThread,
            loadedApk = loadedApk,
            state = LoadedApkRuntimeState(
                packageName = appInfo.packageName,
                applicationInfo = appInfo,
                resources = mockk(relaxed = true),
                classLoader = ClassLoader.getSystemClassLoader()
            ),
            packageAliases = listOf(appInfo.packageName)
        )

        assertTrue(result.skipped)
        assertTrue(result.skippedReason.orEmpty().contains("mLibDir:FIELD_NOT_FOUND"))
        assertTrue(result.rollbackResult?.success == true)
        assertNull(activityThread.loadedApkFrom("mPackages", appInfo.packageName))
        assertNull(activityThread.loadedApkFrom("mResourcePackages", appInfo.packageName))
    }

    @Test
    fun `successful binding rollback is idempotent and restores host ActivityThread`() {
        val activityThread = FakeActivityThread()
        val hostApplication = activityThread.initialApplication()
        val hostLoadedApk = activityThread.boundLoadedApk()
        val hostAppInfo = activityThread.boundApplicationInfo()
        val loadedApk = FakeLoadedApk()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = sourceDir
            nativeLibraryDir = "/data/instances/inst-001/lib"
        }
        val state = LoadedApkRuntimeState(
            packageName = appInfo.packageName,
            applicationInfo = appInfo,
            resources = mockk(relaxed = true),
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val installed = ActivityThreadLoadedApkInstaller.install(
            activityThread,
            loadedApk,
            state,
            listOf(appInfo.packageName)
        )
        val application = mockk<Application>(relaxed = true)
        val bound = ActivityThreadLoadedApkInstaller.bindApplication(
            activityThread,
            installed,
            state,
            application
        )

        val first = requireNotNull(bound.rollbackHandle).rollback()
        val second = bound.rollbackHandle.rollback()

        assertTrue(first.success)
        assertSame(first, second)
        assertSame(hostApplication, activityThread.initialApplication())
        assertSame(hostLoadedApk, activityThread.boundLoadedApk())
        assertSame(hostAppInfo, activityThread.boundApplicationInfo())
        assertNull(loadedApk.application())
        assertNull(activityThread.loadedApkFrom("mPackages", appInfo.packageName))
    }

    @Test
    fun `skipped install result records explicit fallback failure reason`() {
        val result = ActivityThreadLoadedApkInstaller.skippedInstallResult(
            targetClassName = "",
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc", "com.test.minimal"),
            skippedReason = "LOADED_APK_TARGET_NOT_FOUND_AFTER_GUEST_SANDBOX_FAILED:IllegalStateException",
            source = LoadedApkInstallSource.EXISTING_PATCH
        )

        assertTrue(result.skipped)
        assertEquals(
            "LOADED_APK_TARGET_NOT_FOUND_AFTER_GUEST_SANDBOX_FAILED:IllegalStateException",
            result.skippedReason
        )
        assertEquals(listOf("com.test.minimal", "com.multiapp.instance.abc"), result.aliases)
        assertEquals(0, result.installedAliasCount)
        assertEquals(LoadedApkInstallSource.EXISTING_PATCH, result.source)
    }

    private class FakeActivityThread {
        private val mPackages = linkedMapOf<Any?, Any?>()
        private val mResourcePackages = linkedMapOf<Any?, Any?>()
        private var mBoundApplication: Any = FakeAppBindData(
            info = Any(),
            appInfo = ApplicationInfo().apply { packageName = "com.multiapp.app" }
        )
        private var mInitialApplication: Application = mockk(relaxed = true)
        private val mAllApplications = mutableListOf(mInitialApplication)
        var createCount: Int = 0
            private set

        @Suppress("unused")
        fun getPackageInfoNoCheck(applicationInfo: ApplicationInfo, compatibilityInfo: Any?): FakeLoadedApk {
            createCount += 1
            return FakeLoadedApk().applyHostPackage(applicationInfo.packageName)
        }

        fun loadedApkFrom(fieldName: String, packageName: String): Any? {
            val map = when (fieldName) {
                "mPackages" -> mPackages
                "mResourcePackages" -> mResourcePackages
                else -> error("unknown field: $fieldName")
            }
            return (map[packageName] as? WeakReference<*>)?.get()
        }

        fun boundLoadedApk(): Any? = (mBoundApplication as? FakeAppBindData)?.info

        fun boundApplicationInfo(): ApplicationInfo? = (mBoundApplication as? FakeAppBindData)?.appInfo

        fun initialApplication(): Application = mInitialApplication

        fun allApplications(): List<Application> = mAllApplications.toList()

        fun replaceBoundApplication(value: Any) {
            mBoundApplication = value
        }
    }

    private class FakeActivityThreadWithoutResourcePackages {
        private val mPackages = linkedMapOf<Any?, Any?>()

        fun loadedApkFrom(packageName: String): Any? = (mPackages[packageName] as? WeakReference<*>)?.get()
    }

    @Suppress("unused")
    private class FakeLoadedApk {
        private var mApplicationInfo: ApplicationInfo? = null
        private var mApplication: Application? = null
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
        fun application(): Application? = mApplication
        fun resources(): Resources? = mResources
        fun classLoader(): ClassLoader? = mClassLoader
        fun packageName(): String? = mPackageName
        fun libDir(): String? = mLibDir
        fun dataDir(): String? = mDataDir
        fun dataDirFile(): File? = mDataDirFile

        fun applyHostPackage(packageName: String): FakeLoadedApk {
            mPackageName = packageName
            mApplicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            return this
        }
    }

    @Suppress("unused")
    private class FakeLoadedApkWithoutLibDir {
        private var mApplicationInfo: ApplicationInfo? = null
        private var mApplication: Application? = null
        private var mResources: Resources? = null
        private var mClassLoader: ClassLoader? = null
        private var mBaseClassLoader: ClassLoader? = null
        private var mPackageName: String? = null
    }

    private data class FakeAppBindData(
        var info: Any?,
        var appInfo: ApplicationInfo?
    )

    private data class FakeAppBindDataWithWrongAppInfoType(
        var info: Any?,
        var appInfo: String?
    )
}

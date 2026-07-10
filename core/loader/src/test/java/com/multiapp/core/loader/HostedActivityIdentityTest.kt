package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HostedActivityIdentityTest {

    @Test
    fun `application info for runtime uses virtual package and instance paths without mutating source`() {
        val source = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            className = "com.test.minimal.MinimalApp"
            name = "com.test.minimal.MinimalApp"
            sourceDir = "/host/old.apk"
            publicSourceDir = "/host/old.apk"
            dataDir = "/host/data"
            nativeLibraryDir = "/host/lib"
            processName = "com.test.minimal"
            taskAffinity = "com.test.minimal"
            theme = 0x7f010001
        }
        val config = config(publicSourceDir = "/public/apks/minimal.apk")

        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(config, source)

        assertEquals("com.multiapp.instance.abc", runtimeInfo.packageName)
        assertEquals("com.test.minimal.MinimalApp", runtimeInfo.className)
        assertEquals("com.test.minimal.MinimalApp", runtimeInfo.name)
        assertEquals("/data/apks/minimal.apk", runtimeInfo.sourceDir)
        assertEquals("/public/apks/minimal.apk", runtimeInfo.publicSourceDir)
        assertEquals("/data/user/0/com.multiapp.app/files/instance_data/inst-001", runtimeInfo.dataDir)
        assertEquals("/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib", runtimeInfo.nativeLibraryDir)
        assertEquals("com.test.minimal", runtimeInfo.processName)
        assertEquals("com.test.minimal", runtimeInfo.taskAffinity)
        assertEquals("com.test.minimal", source.packageName)
        assertEquals("/host/data", source.dataDir)
    }

    @Test
    fun `application info for runtime derives process identity from snapshot instead of host source`() {
        val source = ApplicationInfo().apply {
            packageName = "com.multiapp.app"
            processName = "com.multiapp.app"
            taskAffinity = "com.multiapp.app"
            theme = 0x01030000
        }
        val config = config(
            snapshot = snapshot(
                processName = "com.test.minimal:reader",
                taskAffinity = "com.test.minimal.reader"
            )
        )

        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(config, source)

        assertEquals("com.test.minimal:reader", runtimeInfo.processName)
        assertEquals("com.test.minimal.reader", runtimeInfo.taskAffinity)
        assertEquals(0x7f010001, runtimeInfo.theme)
    }

    @Test
    fun `application info for runtime falls back to origin identity when snapshot process metadata is missing`() {
        val source = ApplicationInfo().apply {
            packageName = "com.multiapp.app"
            processName = "com.multiapp.app"
            taskAffinity = "com.multiapp.app"
        }
        val config = config(snapshot = snapshot(processName = null, taskAffinity = null))

        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(config, source)

        assertEquals("com.test.minimal", runtimeInfo.processName)
        assertEquals("com.test.minimal", runtimeInfo.taskAffinity)
    }

    @Test
    fun `application info for runtime supplies instance lib dir when native path is absent`() {
        val source = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            nativeLibraryDir = null
        }
        val config = config(nativeLibraryDir = null)

        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(config, source)

        assertEquals(
            "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
            runtimeInfo.nativeLibraryDir
        )
    }

    @Test
    fun `application info for runtime rewrites source protected storage identity`() {
        val source = ApplicationInfo().apply {
            packageName = "com.multiapp.app"
            dataDir = "/host/data"
            deviceProtectedDataDir = "/host/device"
        }
        val config = config()

        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(config, source)

        assertEquals(config.dataDir, readStringField(runtimeInfo, "deviceProtectedDataDir"))
    }

    @Test
    fun `activity info for record uses virtual package and supplied virtual application info`() {
        val config = config(snapshot = snapshot())
        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = ApplicationInfo().apply {
                packageName = "com.test.minimal"
                sourceDir = "/data/apks/minimal.apk"
                publicSourceDir = "/data/apks/minimal.apk"
                dataDir = "/origin/data"
                nativeLibraryDir = "/origin/lib"
            }
        )

        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = "com.test.minimal.MainActivity",
            applicationInfo = runtimeInfo
        )

        assertEquals("com.multiapp.instance.abc", activityInfo.packageName)
        assertEquals("com.test.minimal.MainActivity", activityInfo.name)
        assertEquals(0x7f010002, activityInfo.theme)
        assertEquals("com.test.minimal", activityInfo.processName)
        assertEquals("com.test.minimal", activityInfo.taskAffinity)
        assertSame(runtimeInfo, activityInfo.applicationInfo)
        assertEquals("com.multiapp.instance.abc", activityInfo.applicationInfo.packageName)
    }

    @Test
    fun `activity info for record resolves alias target activity theme`() {
        val config = config(
            snapshot = snapshot().copy(
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.LauncherAlias",
                        targetActivityName = "com.test.minimal.RealActivity",
                        exported = true,
                        themeId = 0x7f010004
                    )
                )
            )
        )
        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = ApplicationInfo().apply {
                packageName = "com.test.minimal"
                theme = 0x7f010001
            }
        )

        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = "com.test.minimal.RealActivity",
            applicationInfo = runtimeInfo
        )

        assertEquals("com.test.minimal.RealActivity", activityInfo.name)
        assertEquals(0x7f010004, activityInfo.theme)
    }

    @Test
    fun `activity info fallback uses virtual package when snapshot component is missing`() {
        val config = config(snapshot = snapshot())
        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = ApplicationInfo().apply {
                packageName = "com.test.minimal"
                theme = 0x7f010003
                processName = "com.test.minimal"
                taskAffinity = "com.test.minimal"
            }
        )

        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = "com.test.minimal.MissingActivity",
            applicationInfo = runtimeInfo
        )

        assertEquals("com.multiapp.instance.abc", activityInfo.packageName)
        assertEquals("com.test.minimal.MissingActivity", activityInfo.name)
        assertEquals(0x7f010001, activityInfo.theme)
        assertEquals(false, activityInfo.exported)
        assertSame(runtimeInfo, activityInfo.applicationInfo)
    }

    private fun config(
        snapshot: VirtualPackageSnapshot? = null,
        nativeLibraryDir: String? = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
        publicSourceDir: String = snapshot?.publicSourceDir ?: "/data/apks/minimal.apk"
    ) = VirtualContextConfig(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        sourceDir = "/data/apks/minimal.apk",
        nativeLibraryDir = nativeLibraryDir,
        classLoader = ClassLoader.getSystemClassLoader(),
        applicationLabel = "MinimalTest",
        packageSnapshot = snapshot,
        publicSourceDir = publicSourceDir
    )

    private fun snapshot(
        processName: String? = null,
        taskAffinity: String? = null
    ) = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
        applicationClassName = "com.test.minimal.MinimalApp",
        processName = processName,
        taskAffinity = taskAffinity,
        launcherActivityName = "com.test.minimal.MainActivity",
        themeId = 0x7f010001,
        activities = listOf(
            ResolvedComponent(
                name = "com.test.minimal.MainActivity",
                exported = true,
                themeId = 0x7f010002
            )
        )
    )

    private fun readStringField(target: Any, fieldName: String): String? =
        runCatching { target.javaClass.getField(fieldName).get(target) as? String }.getOrNull()
}

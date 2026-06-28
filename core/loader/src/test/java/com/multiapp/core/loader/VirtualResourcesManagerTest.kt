package com.multiapp.core.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import com.multiapp.core.model.virtual.VirtualContextConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualResourcesManagerTest {

    @Test
    fun `createApplicationInfo maps guest identity and paths`() {
        val hostInfo = ApplicationInfo().apply {
            packageName = "com.multiapp.app"
            sourceDir = "/host/base.apk"
            publicSourceDir = "/host/base.apk"
            dataDir = "/data/user/0/com.multiapp.app"
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationInfo } returns hostInfo

        val manager = VirtualResourcesManager(context)
        val appInfo = manager.createApplicationInfo(config())

        assertEquals("com.test.minimal", appInfo.packageName)
        assertEquals("/data/local/tmp/minimal.apk", appInfo.sourceDir)
        assertEquals("/data/local/tmp/minimal.apk", appInfo.publicSourceDir)
        assertEquals("/data/user/0/com.multiapp.app/files/instance_data/inst-001", appInfo.dataDir)
        assertEquals("/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib", appInfo.nativeLibraryDir)
        assertEquals("MinimalTest", appInfo.nonLocalizedLabel)
    }

    @Test
    fun `create returns package manager resources when available`() {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>()
        val resources = mockk<Resources>(relaxed = true)
        every { context.applicationInfo } returns ApplicationInfo()
        every { context.packageManager } returns packageManager
        every { packageManager.getResourcesForApplication(any<ApplicationInfo>()) } returns resources

        val bundle = VirtualResourcesManager(context).create(config())

        assertEquals(resources, bundle.resources)
        assertEquals(ResourceSource.PACKAGE_MANAGER, bundle.source)
    }

    private fun config() = VirtualContextConfig(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        sourceDir = "/data/local/tmp/minimal.apk",
        nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
        classLoader = ClassLoader.getSystemClassLoader(),
        applicationLabel = "MinimalTest"
    )
}

package com.multiapp.core.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import com.multiapp.core.model.virtual.VirtualContextConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun `createApplicationInfo maps split source directories and names`() {
        val hostInfo = ApplicationInfo().apply {
            packageName = "com.multiapp.app"
            sourceDir = "/host/base.apk"
            publicSourceDir = "/host/base.apk"
            dataDir = "/data/user/0/com.multiapp.app"
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationInfo } returns hostInfo

        val manager = VirtualResourcesManager(context)
        val appInfo = manager.createApplicationInfo(
            config(
                splitSourceDirs = listOf(
                    "/data/local/tmp/split_config.en.apk",
                    "/data/local/tmp/split_feature.apk"
                ),
                splitPublicSourceDirs = listOf(
                    "/data/local/tmp/public_split_config.en.apk",
                    "/data/local/tmp/public_split_feature.apk"
                ),
                splitNames = listOf("config.en", "feature")
            )
        )

        assertContentEquals(
            arrayOf("/data/local/tmp/split_config.en.apk", "/data/local/tmp/split_feature.apk"),
            appInfo.splitSourceDirs
        )
        assertContentEquals(
            arrayOf("/data/local/tmp/public_split_config.en.apk", "/data/local/tmp/public_split_feature.apk"),
            appInfo.splitPublicSourceDirs
        )
        assertContentEquals(arrayOf("config.en", "feature"), appInfo.splitNames)
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

    private fun config(
        splitSourceDirs: List<String> = emptyList(),
        splitPublicSourceDirs: List<String> = emptyList(),
        splitNames: List<String> = emptyList()
    ) = VirtualContextConfig(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        sourceDir = "/data/local/tmp/minimal.apk",
        nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
        classLoader = ClassLoader.getSystemClassLoader(),
        applicationLabel = "MinimalTest",
        splitSourceDirs = splitSourceDirs,
        splitPublicSourceDirs = splitPublicSourceDirs,
        splitNames = splitNames
    )
}

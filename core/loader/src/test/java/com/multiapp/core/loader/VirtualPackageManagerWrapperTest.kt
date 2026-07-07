package com.multiapp.core.loader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VirtualPackageManagerWrapperTest {

    @Test
    fun `self package queries return virtual snapshot data`() {
        val pm = VirtualPackageManagerWrapper(mockk<PackageManager>(relaxed = true), snapshot())

        val packageInfo = pm.getPackageInfo("com.multiapp.instance.abc", 0)
        val applicationInfo = pm.getApplicationInfo("com.test.minimal", 0)
        val launcherIntent = mockk<Intent> {
            every { component } returns null
            every { action } returns Intent.ACTION_MAIN
            every { categories } returns setOf(Intent.CATEGORY_LAUNCHER)
        }
        val launcher = pm.resolveActivity(launcherIntent, 0)
        val provider = pm.resolveContentProvider("com.test.minimal.probe", 0)

        assertEquals("com.test.minimal", packageInfo.packageName)
        assertEquals("4.2", packageInfo.versionName)
        assertEquals("com.test.minimal", applicationInfo.packageName)
        assertEquals("MinimalTest", applicationInfo.nonLocalizedLabel)
        assertEquals("com.test.minimal.MainActivity", launcher?.activityInfo?.name)
        assertNotNull(provider)
        assertEquals("com.test.minimal.ProbeProvider", provider.name)
    }

    @Test
    fun `uid package and provider queries return virtual snapshot data before base delegation`() {
        val runtimeUid = 42420
        val pm = VirtualPackageManagerWrapper(
            base = mockk<PackageManager>(relaxed = true),
            snapshot = snapshot(),
            runtimeUid = runtimeUid
        )

        assertEquals(runtimeUid, pm.getPackageUid("com.test.minimal", 0))
        assertEquals(runtimeUid, pm.getPackageUid("com.multiapp.instance.abc", 0))
        assertContentEquals(
            arrayOf("com.test.minimal", "com.multiapp.instance.abc"),
            pm.getPackagesForUid(runtimeUid)
        )
        assertEquals("com.test.minimal", pm.getNameForUid(runtimeUid))
        assertEquals(
            listOf("com.test.minimal"),
            pm.getPackagesHoldingPermissions(arrayOf("android.permission.CAMERA"), 0).map { it.packageName }
        )
        assertEquals(
            listOf("com.test.minimal.ProbeProvider"),
            pm.queryContentProviders(null, runtimeUid, 0).map { it.name }
        )
        assertEquals(
            listOf("com.test.minimal.ProbeProvider"),
            pm.queryContentProviders("com.test.minimal:probe", runtimeUid, 0).map { it.name }
        )
        assertTrue(pm.queryContentProviders("com.other", runtimeUid, 0).isEmpty())
    }

    @Test
    fun `component enabled setting for virtual component does not hit base PMS`() {
        val base = mockk<PackageManager>(relaxed = true)
        val component = component("com.test.minimal.MainActivity")
        val pm = VirtualPackageManagerWrapper(base, snapshot())

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(component))
        pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)

        verify(exactly = 0) { base.getComponentEnabledSetting(any()) }
        verify(exactly = 0) { base.setComponentEnabledSetting(any(), any(), any()) }
    }

    @Test
    fun `resources for virtual package use snapshot application info before base package lookup`() {
        val base = mockk<PackageManager>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        every { base.getResourcesForApplication(any<ApplicationInfo>()) } returns resources
        val pm = VirtualPackageManagerWrapper(base, snapshot())

        assertEquals(resources, pm.getResourcesForApplication("com.multiapp.instance.abc"))

        verify(exactly = 1) {
            base.getResourcesForApplication(
                match<ApplicationInfo> {
                    it.packageName == "com.test.minimal" &&
                        it.sourceDir == "/data/apks/minimal.apk"
                }
            )
        }
        verify(exactly = 0) { base.getResourcesForApplication("com.multiapp.instance.abc") }
    }

    @Test
    fun `virtual package drawable and text lookups do not fall through to system package name lookup`() {
        val base = mockk<PackageManager>(relaxed = true)
        val icon = mockk<Drawable>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        every { base.getApplicationIcon(any<ApplicationInfo>()) } returns icon
        every { base.getResourcesForApplication(any<ApplicationInfo>()) } returns resources
        every { resources.getText(7) } returns "Virtual label"
        val pm = VirtualPackageManagerWrapper(base, snapshot())

        assertEquals(icon, pm.getApplicationIcon("com.multiapp.instance.abc"))
        assertEquals("Virtual label", pm.getText("com.multiapp.instance.abc", 7, null))

        verify(exactly = 0) { base.getApplicationIcon("com.multiapp.instance.abc") }
        verify(exactly = 0) { base.getText("com.multiapp.instance.abc", 7, null) }
    }

    @Test
    fun `virtual application info always exposes non-null metadata bundle`() {
        val pm = VirtualPackageManagerWrapper(mockk<PackageManager>(relaxed = true), snapshot())

        assertNotNull(pm.getApplicationInfo("com.multiapp.instance.abc", 0).metaData)
    }

    @Test
    fun `permission controller runtime methods delegate or fall back without abstract PackageManager crash`() {
        val base = mockk<PackageManager>(relaxed = true)
        every { base.resolveActivity(any(), any<Int>()) } returns ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.android.permissioncontroller" }
        }
        val pm = VirtualPackageManagerWrapper(base, snapshot())

        val controller = pm.javaClass
            .getMethod("getPermissionControllerPackageName")
            .invoke(pm)
        val requestIntentMethod = pm.javaClass
            .getMethod("buildRequestPermissionsIntent", Array<String>::class.java)
        val rationale = pm.javaClass
            .getMethod("shouldShowRequestPermissionRationale", String::class.java)
            .invoke(pm, "android.permission.BLUETOOTH_CONNECT")
        val rationaleWithDeviceId = pm.javaClass
            .getMethod("shouldShowRequestPermissionRationale", String::class.java, Integer.TYPE)
            .invoke(pm, "android.permission.BLUETOOTH_CONNECT", 0)

        assertEquals("com.android.permissioncontroller", controller)
        assertNotNull(requestIntentMethod)
        assertEquals(false, rationale)
        assertEquals(false, rationaleWithDeviceId)
    }

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = "/data/inst",
        applicationClassName = "com.test.minimal.MinimalApp",
        processName = "com.test.minimal",
        launcherActivityName = "com.test.minimal.MainActivity",
        activities = listOf(
            ResolvedComponent(
                name = "com.test.minimal.MainActivity",
                exported = true,
                intentFilters = listOf(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER)
            )
        ),
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                processName = "com.test.minimal:probe",
                authorities = listOf("com.test.minimal.probe")
            )
        ),
        permissions = listOf("android.permission.CAMERA")
    )

    private fun component(
        className: String,
        packageName: String = "com.test.minimal"
    ) = mockk<android.content.ComponentName> {
        every { this@mockk.packageName } returns packageName
        every { this@mockk.className } returns className
    }
}

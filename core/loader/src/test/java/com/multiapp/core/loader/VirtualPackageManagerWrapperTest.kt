package com.multiapp.core.loader

import android.content.Intent
import android.content.pm.PackageManager
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
                authorities = listOf("com.test.minimal.probe")
            )
        )
    )
}

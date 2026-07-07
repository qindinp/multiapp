package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualPackageServiceTest {

    @Test
    fun `package and application queries are backed by snapshot package aliases`() {
        val service = VirtualPackageService(snapshot())

        assertEquals("com.test.minimal", service.getPackageInfo("com.test.minimal")?.packageName)
        assertEquals("com.test.minimal", service.getPackageInfo("com.multiapp.instance.abc")?.packageName)
        assertEquals("MinimalTest", service.getApplicationInfo("com.test.minimal")?.nonLocalizedLabel)
        assertNotNull(service.getApplicationInfo("com.multiapp.instance.abc")?.metaData)
        assertNull(service.getPackageInfo("com.other"))
    }

    @Test
    fun `component and provider queries resolve from snapshot`() {
        val service = VirtualPackageService(snapshot())

        assertEquals("com.test.minimal.MainActivity", service.getActivityInfo(component("com.test.minimal.MainActivity"))?.name)
        assertEquals("com.test.minimal.SyncService", service.getServiceInfo(component("com.test.minimal.SyncService"))?.name)
        assertEquals("com.test.minimal.BootReceiver", service.getReceiverInfo(component("com.test.minimal.BootReceiver"))?.name)
        assertEquals("com.test.minimal.ProbeProvider", service.getProviderInfo(component("com.test.minimal.ProbeProvider"))?.name)
        assertEquals("com.test.minimal.ProbeProvider", service.resolveContentProvider("com.test.minimal.probe")?.name)
        assertNull(service.getActivityInfo(component("com.other.Missing", packageName = "com.other")))
    }

    @Test
    fun `component enabled setting is answered only for snapshot components`() {
        val service = VirtualPackageService(snapshot())

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            service.getComponentEnabledSetting(component("com.test.minimal.MainActivity"))
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            service.getComponentEnabledSetting(component("com.test.minimal.SyncService"))
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            service.getComponentEnabledSetting(component("com.test.minimal.BootReceiver"))
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            service.getComponentEnabledSetting(component("com.test.minimal.ProbeProvider"))
        )
        assertTrue(
            service.setComponentEnabledSetting(
                component("com.test.minimal.SyncService"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                0
            )
        )
        assertNull(service.getComponentEnabledSetting(component("com.test.minimal.Missing")))
        assertEquals(false, service.setComponentEnabledSetting(component("com.test.minimal.Missing"), 0, 0))
    }

    @Test
    fun `intent queries resolve activities services receivers and providers`() {
        val service = VirtualPackageService(snapshot())

        val launcherIntent = intent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER))
        val serviceIntent = intent("com.test.SYNC")
        val receiverIntent = intent("com.test.BOOT")
        val providerIntent = intent("com.test.PROBE")

        assertEquals("com.test.minimal.MainActivity", service.resolveActivity(launcherIntent)?.activityInfo?.name)
        assertEquals("com.test.minimal.SyncService", service.resolveService(serviceIntent)?.serviceInfo?.name)
        assertEquals("com.test.minimal.BootReceiver", service.queryBroadcastReceivers(receiverIntent).single().activityInfo.name)
        assertEquals("com.test.minimal.ProbeProvider", service.queryIntentContentProviders(providerIntent).single().providerInfo.name)
    }

    @Test
    fun `launcher activity alias keeps alias info with target activity`() {
        val service = VirtualPackageService(aliasSnapshot())

        val result = service.resolveActivity(intent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))

        assertEquals("com.test.minimal.launcher4", result?.activityInfo?.name)
        assertEquals("com.test.minimal.MainActivity", result?.activityInfo?.targetActivity)
    }

    @Test
    fun `launch intent is null when snapshot has no launcher metadata`() {
        val service = VirtualPackageService(
            snapshot().copy(
                launcherActivityName = null,
                activities = listOf(
                    ResolvedComponent(name = "com.test.minimal.ExportedActivity", exported = true)
                )
            )
        )

        assertNull(service.getLaunchIntentForPackage("com.test.minimal"))
    }

    @Test
    fun `launcher query is empty when snapshot has no launcher metadata`() {
        val service = VirtualPackageService(
            snapshot().copy(
                launcherActivityName = null,
                activities = listOf(
                    ResolvedComponent(name = "com.test.minimal.ExportedActivity", exported = true),
                    ResolvedComponent(name = "com.test.minimal.MainActivity", exported = false)
                )
            )
        )

        val result = service.resolveActivity(intent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))

        assertNull(result)
        assertNull(
            VirtualPackageInfoFactory.launcherResolveInfo(
                snapshot().copy(
                    launcherActivityName = null,
                    activities = listOf(
                        ResolvedComponent(name = "com.test.minimal.ExportedActivity", exported = true),
                        ResolvedComponent(name = "com.test.minimal.MainActivity", exported = false)
                    )
                )
            )
        )
    }

    @Test
    fun `view activity matches http scheme filter`() {
        val service = VirtualPackageService(snapshot())

        val result = service.resolveActivity(
            intent(
                action = Intent.ACTION_VIEW,
                categories = setOf(Intent.CATEGORY_DEFAULT),
                scheme = "http"
            )
        )

        assertEquals("com.test.minimal.ViewActivity", result?.activityInfo?.name)
    }

    @Test
    fun `custom action and category must match component filter`() {
        val service = VirtualPackageService(snapshot())

        val result = service.resolveActivity(
            intent(
                action = "com.test.CUSTOM",
                categories = setOf("com.test.category.SPECIAL")
            )
        )

        assertEquals("com.test.minimal.CustomActivity", result?.activityInfo?.name)
    }

    @Test
    fun `scheme mismatch and missing data do not match scheme filter`() {
        val service = VirtualPackageService(snapshot())

        val https = intent(
            action = Intent.ACTION_VIEW,
            categories = setOf(Intent.CATEGORY_DEFAULT),
            scheme = "https"
        )
        val noData = intent(
            action = Intent.ACTION_VIEW,
            categories = setOf(Intent.CATEGORY_DEFAULT)
        )

        assertNull(service.resolveActivity(https))
        assertNull(service.resolveActivity(noData))
    }

    @Test
    fun `explicit component wins even when intent filter would not match`() {
        val service = VirtualPackageService(snapshot())

        val result = service.resolveActivity(
            intent(
                action = "com.test.UNRELATED",
                component = component("com.test.minimal.ViewActivity")
            )
        )

        assertEquals("com.test.minimal.ViewActivity", result?.activityInfo?.name)
    }

    @Test
    fun `permission and installed queries are snapshot scoped`() {
        val service = VirtualPackageService(snapshot())

        assertEquals(PackageManager.PERMISSION_GRANTED, service.checkPermission("android.permission.CAMERA", "com.test.minimal"))
        assertEquals(PackageManager.PERMISSION_DENIED, service.checkPermission("android.permission.RECORD_AUDIO", "com.test.minimal"))
        assertNull(service.checkPermission("android.permission.CAMERA", "com.other"))
        assertEquals(listOf("com.test.minimal"), service.getInstalledPackages().map { it.packageName })
        assertEquals(listOf("com.test.minimal"), service.getInstalledApplications().map { it.packageName })
        assertTrue(service.isInstantApp("com.test.minimal") == false)
        assertNull(service.isInstantApp("com.other"))
    }

    @Test
    fun `runtime uid queries are snapshot scoped to package aliases`() {
        val service = VirtualPackageService(snapshot())
        val runtimeUid = 42420

        assertEquals(runtimeUid, service.getPackageUid("com.test.minimal", runtimeUid))
        assertEquals(runtimeUid, service.getPackageUid("com.multiapp.instance.abc", runtimeUid))
        assertNull(service.getPackageUid("com.other", runtimeUid))
        assertContentEquals(
            arrayOf("com.test.minimal", "com.multiapp.instance.abc"),
            service.getPackagesForUid(runtimeUid, runtimeUid)
        )
        assertEquals("com.test.minimal", service.getNameForUid(runtimeUid, runtimeUid))
        assertNull(service.getPackagesForUid(98765, runtimeUid))
        assertNull(service.getNameForUid(98765, runtimeUid))
    }

    @Test
    fun `packages holding permissions are answered from snapshot permissions`() {
        val service = VirtualPackageService(snapshot())

        val packages = service.getPackagesHoldingPermissions(
            arrayOf("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION")
        )

        assertEquals(listOf("com.test.minimal"), packages.map { it.packageName })
        assertTrue(service.getPackagesHoldingPermissions(arrayOf("android.permission.RECORD_AUDIO")).isEmpty())
        assertTrue(service.getPackagesHoldingPermissions(emptyArray()).isEmpty())
    }

    @Test
    fun `content provider queries are scoped by runtime uid and process name`() {
        val service = VirtualPackageService(snapshot())
        val runtimeUid = 42420

        val allProviders = service.queryContentProviders(null, runtimeUid, runtimeUid)
        val processProviders = service.queryContentProviders("com.test.minimal:probe", runtimeUid, runtimeUid)

        assertEquals(listOf("com.test.minimal.ProbeProvider"), allProviders.map { it.name })
        assertEquals(listOf("com.test.minimal.ProbeProvider"), processProviders.map { it.name })
        assertTrue(service.queryContentProviders("com.other", runtimeUid, runtimeUid).isEmpty())
        assertTrue(service.queryContentProviders(null, 98765, runtimeUid).isEmpty())
    }

    private fun component(
        className: String,
        packageName: String = "com.test.minimal"
    ) = mockk<ComponentName> {
        every { this@mockk.packageName } returns packageName
        every { this@mockk.className } returns className
    }

    private fun intent(
        action: String,
        categories: Set<String> = emptySet(),
        component: ComponentName? = null,
        scheme: String? = null,
        packageName: String? = null
    ) = mockk<Intent> {
        every { this@mockk.component } returns component
        every { this@mockk.`package` } returns packageName
        every { this@mockk.action } returns action
        every { this@mockk.categories } returns categories
        every { this@mockk.scheme } returns scheme
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
                intentFilters = listOf(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER),
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf(Intent.ACTION_MAIN),
                        categories = listOf(Intent.CATEGORY_LAUNCHER)
                    )
                )
            ),
            ResolvedComponent(
                name = "com.test.minimal.ViewActivity",
                exported = true,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf(Intent.ACTION_VIEW),
                        categories = listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE),
                        dataSchemes = listOf("http")
                    )
                )
            ),
            ResolvedComponent(
                name = "com.test.minimal.CustomActivity",
                exported = true,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf("com.test.CUSTOM"),
                        categories = listOf("com.test.category.SPECIAL")
                    )
                )
            )
        ),
        services = listOf(
            ResolvedComponent(
                name = "com.test.minimal.SyncService",
                exported = false,
                intentFilters = listOf("com.test.SYNC")
            )
        ),
        receivers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.BootReceiver",
                exported = false,
                intentFilters = listOf("com.test.BOOT")
            )
        ),
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                processName = "com.test.minimal:probe",
                intentFilters = listOf("com.test.PROBE"),
                authorities = listOf("com.test.minimal.probe")
            )
        ),
        permissions = listOf("android.permission.CAMERA")
    )

    private fun aliasSnapshot() = VirtualPackageSnapshot(
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
        launcherActivityName = "com.test.minimal.MainActivity",
        activities = listOf(
            ResolvedComponent(
                name = "com.test.minimal.launcher4",
                exported = true,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf(Intent.ACTION_MAIN),
                        categories = listOf(Intent.CATEGORY_LAUNCHER)
                    )
                ),
                targetActivityName = "com.test.minimal.MainActivity"
            ),
            ResolvedComponent(name = "com.test.minimal.MainActivity", exported = false)
        )
    )
}

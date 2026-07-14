package com.multiapp.core.loader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualPackageManagerWrapperTest {
    private lateinit var bundleSupport: MockAndroidBundleSupport

    @BeforeTest
    fun setUpBundleSupport() {
        bundleSupport = MockAndroidBundleSupport()
    }

    @AfterTest
    fun tearDownBundleSupport() {
        bundleSupport.close()
    }

    @Test
    fun `self package queries return virtual snapshot data`() {
        val pm = VirtualPackageManagerWrapper(
            mockk<PackageManager>(relaxed = true),
            snapshot(),
            RUNTIME_UID
        )

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
        assertEquals(RUNTIME_UID, applicationInfo.uid)
        assertEquals(RUNTIME_UID, assertNotNull(packageInfo.applicationInfo).uid)
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
            runtimeUid = runtimeUid,
            permissionCheckDispatcher = VirtualPermissionCheckDispatcher {
                VirtualPermissionCheckDispatchResult(
                    handled = true,
                    granted = true,
                    reason = "test_permission_state"
                )
            }
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
    fun `virtual enabled settings fail closed without hitting base PMS`() {
        val base = mockk<PackageManager>(relaxed = true)
        val component = component("com.test.minimal.MainActivity")
        val pm = VirtualPackageManagerWrapper(
            base = base,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID,
            enabledStateDispatcher = VirtualPackageEnabledStateDispatcher {
                VirtualPackageEnabledStateDispatchResult.unavailable("test_authority_unavailable")
            }
        )

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getApplicationEnabledSetting("com.test.minimal")
        )
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component))
        pm.setApplicationEnabledSetting(
            "com.multiapp.instance.abc",
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            0
        )
        pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)

        verify(exactly = 0) { base.getApplicationEnabledSetting(any()) }
        verify(exactly = 0) { base.setApplicationEnabledSetting(any(), any(), any()) }
        verify(exactly = 0) { base.getComponentEnabledSetting(any()) }
        verify(exactly = 0) { base.setComponentEnabledSetting(any(), any(), any()) }
    }

    @Test
    fun `resources for virtual package use snapshot application info before base package lookup`() {
        val base = mockk<PackageManager>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        every { base.getResourcesForApplication(any<ApplicationInfo>()) } returns resources
        val pm = VirtualPackageManagerWrapper(base, snapshot(), RUNTIME_UID)

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
        val pm = VirtualPackageManagerWrapper(base, snapshot(), RUNTIME_UID)

        assertEquals(icon, pm.getApplicationIcon("com.multiapp.instance.abc"))
        assertEquals("Virtual label", pm.getText("com.multiapp.instance.abc", 7, null))

        verify(exactly = 0) { base.getApplicationIcon("com.multiapp.instance.abc") }
        verify(exactly = 0) { base.getText("com.multiapp.instance.abc", 7, null) }
    }

    @Test
    fun `virtual application info exposes metadata only when requested`() {
        val pm = VirtualPackageManagerWrapper(
            mockk<PackageManager>(relaxed = true),
            snapshot(),
            RUNTIME_UID
        )

        assertNull(pm.getApplicationInfo("com.multiapp.instance.abc", 0).metaData)
        assertNotNull(
            pm.getApplicationInfo("com.multiapp.instance.abc", PackageManager.GET_META_DATA).metaData
        )
    }

    @Test
    fun `package and component flags project only requested optional fields`() {
        val signingInfo = signingInfo()
        val pm = VirtualPackageManagerWrapper(
            base = mockk(relaxed = true),
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID,
            packageSigningInfo = signingInfo
        )

        val basic = pm.getPackageInfo("com.test.minimal", 0)
        val requestedFlags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            PackageManager.GET_SIGNING_CERTIFICATES
        val applicationWithMetaData = pm.getApplicationInfo(
            "com.test.minimal",
            PackageManager.GET_META_DATA
        )
        assertEquals("app", assertNotNull(applicationWithMetaData.metaData).getString("scope"))
        val full = pm.getPackageInfo("com.test.minimal", requestedFlags)
        val activityWithoutMetaData = pm.getActivityInfo(component("com.test.minimal.MainActivity"), 0)
        val activityWithMetaData = pm.getActivityInfo(
            component("com.test.minimal.MainActivity"),
            PackageManager.GET_META_DATA
        )

        val basicApplication = assertNotNull(basic.applicationInfo)
        val fullApplication = assertNotNull(full.applicationInfo)
        val fullActivities = assertNotNull(full.activities)
        val fullReceivers = assertNotNull(full.receivers)
        val fullServices = assertNotNull(full.services)
        val fullProviders = assertNotNull(full.providers)
        assertNull(basic.activities)
        assertNull(basic.receivers)
        assertNull(basic.services)
        assertNull(basic.providers)
        assertNull(basic.requestedPermissions)
        assertNull(basicApplication.metaData)
        @Suppress("DEPRECATION")
        assertNull(basic.signatures)
        assertNull(basic.signingInfo)

        assertEquals(1, fullActivities.size)
        assertEquals(1, fullReceivers.size)
        assertEquals(1, fullServices.size)
        assertEquals(1, fullProviders.size)
        assertContentEquals(arrayOf("android.permission.CAMERA"), full.requestedPermissions)
        assertNotNull(fullApplication.metaData)
        assertEquals(RUNTIME_UID, assertNotNull(fullActivities.single().applicationInfo).uid)
        assertEquals(RUNTIME_UID, assertNotNull(fullReceivers.single().applicationInfo).uid)
        assertEquals(RUNTIME_UID, assertNotNull(fullServices.single().applicationInfo).uid)
        assertEquals(RUNTIME_UID, assertNotNull(fullProviders.single().applicationInfo).uid)
        @Suppress("DEPRECATION")
        assertEquals(1, assertNotNull(full.signatures).size)
        assertNotNull(full.signingInfo)
        assertNull(activityWithoutMetaData.metaData)
        assertEquals("activity", activityWithMetaData.metaData.getString("scope"))
    }

    @Test
    fun `provider keeps all authorities while each authority resolves independently`() {
        val pm = VirtualPackageManagerWrapper(
            mockk(relaxed = true),
            snapshot(),
            RUNTIME_UID
        )

        val first = assertNotNull(pm.resolveContentProvider("com.test.minimal.probe", 0))
        val second = assertNotNull(pm.resolveContentProvider("com.test.minimal.probe.alt", 0))

        assertEquals("com.test.minimal.probe;com.test.minimal.probe.alt", first.authority)
        assertEquals(first.authority, second.authority)
        assertEquals(RUNTIME_UID, first.applicationInfo.uid)
        assertEquals(RUNTIME_UID, second.applicationInfo.uid)
    }

    @Test
    fun `virtual signing queries use verified snapshot identity without guest PMS delegation`() {
        val base = mockk<PackageManager>(relaxed = true)
        val certificate = "verified-guest-certificate".toByteArray()
        val pm = VirtualPackageManagerWrapper(
            base = base,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID,
            packageSigningInfo = signingInfo(certificate)
        )

        assertEquals(
            PackageManager.SIGNATURE_MATCH,
            pm.checkSignatures("com.test.minimal", "com.multiapp.instance.abc")
        )
        assertEquals(PackageManager.SIGNATURE_MATCH, pm.checkSignatures(RUNTIME_UID, RUNTIME_UID))
        assertTrue(
            pm.hasSigningCertificate(
                "com.multiapp.instance.abc",
                certificate,
                PackageManager.CERT_INPUT_RAW_X509
            )
        )
        assertTrue(
            pm.hasSigningCertificate(
                RUNTIME_UID,
                certificate.sha256(),
                PackageManager.CERT_INPUT_SHA256
            )
        )

        verify(exactly = 0) { base.checkSignatures(any<String>(), any<String>()) }
        verify(exactly = 0) { base.checkSignatures(any<Int>(), any<Int>()) }
        verify(exactly = 0) { base.hasSigningCertificate(any<String>(), any(), any()) }
        verify(exactly = 0) { base.hasSigningCertificate(any<Int>(), any(), any()) }
        verify(exactly = 0) { base.getPackageInfo(any<String>(), any<Int>()) }
    }

    @Test
    fun `unknown virtual signing identity fails closed without guest PMS delegation`() {
        val base = mockk<PackageManager>(relaxed = true)
        val pm = VirtualPackageManagerWrapper(
            base = base,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID,
            packageSigningInfo = null
        )

        assertEquals(
            PackageManager.SIGNATURE_NO_MATCH,
            pm.checkSignatures("com.test.minimal", "com.multiapp.instance.abc")
        )
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, pm.checkSignatures(RUNTIME_UID, RUNTIME_UID))
        assertFalse(
            pm.hasSigningCertificate(
                "com.test.minimal",
                byteArrayOf(1, 2, 3),
                PackageManager.CERT_INPUT_RAW_X509
            )
        )

        verify(exactly = 0) { base.checkSignatures(any<String>(), any<String>()) }
        verify(exactly = 0) { base.checkSignatures(any<Int>(), any<Int>()) }
        verify(exactly = 0) { base.hasSigningCertificate(any<String>(), any(), any()) }
    }

    @Test
    fun `permission controller runtime methods delegate or fall back without abstract PackageManager crash`() {
        val base = mockk<PackageManager>(relaxed = true)
        every { base.resolveActivity(any(), any<Int>()) } returns ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.android.permissioncontroller" }
        }
        val pm = VirtualPackageManagerWrapper(base, snapshot(), RUNTIME_UID)

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
        metaData = mapOf("scope" to "app"),
        activities = listOf(
            ResolvedComponent(
                name = "com.test.minimal.MainActivity",
                exported = true,
                intentFilters = listOf(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER),
                metaData = mapOf("scope" to "activity")
            )
        ),
        services = listOf(
            ResolvedComponent(name = "com.test.minimal.SyncService", exported = false)
        ),
        receivers = listOf(
            ResolvedComponent(name = "com.test.minimal.BootReceiver", exported = false)
        ),
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                processName = "com.test.minimal:probe",
                authorities = listOf("com.test.minimal.probe", "com.test.minimal.probe.alt")
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

    private fun signingInfo(certificate: ByteArray = "verified-certificate".toByteArray()) =
        VirtualPackageSigningInfo(
            legacySignatures = arrayOf(Signature(certificate)),
            signingInfo = mockk<SigningInfo>(),
            signerSha256Digests = listOf(certificate.sha256().toHex())
        )

    private fun ByteArray.sha256(): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val RUNTIME_UID = 42420
    }
}

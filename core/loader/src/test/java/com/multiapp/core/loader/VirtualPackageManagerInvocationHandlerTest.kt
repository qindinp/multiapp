package com.multiapp.core.loader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualPackageManagerInvocationHandlerTest {
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
    fun `virtual package and application queries return snapshot data before original PMS`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )

        val packageInfo = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf("com.multiapp.instance.abc", 0)
        ) as PackageInfo
        val applicationInfo = handler.invoke(
            proxy = Any(),
            method = apiMethod("getApplicationInfo", String::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf("com.test.minimal", 0)
        ) as ApplicationInfo

        assertEquals("com.test.minimal", packageInfo.packageName)
        assertEquals("4.2", packageInfo.versionName)
        assertEquals("com.test.minimal", applicationInfo.packageName)
        assertEquals("MinimalTest", applicationInfo.nonLocalizedLabel)
        assertTrue(original.calls.isEmpty())
    }

    @Test
    fun `permission uid and resolve methods use explicit argument extractors`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = permissionAwareService(),
            runtimeUid = RUNTIME_UID
        )

        val permission = handler.invoke(
            proxy = Any(),
            method = apiMethod("checkPermission", String::class.java, String::class.java),
            args = arrayOf("android.permission.CAMERA", "com.test.minimal")
        ) as Int
        val packageUid = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackageUid", String::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf("com.multiapp.instance.abc", 0)
        ) as Int
        val launcherResult = handler.invoke(
            proxy = Any(),
            method = apiMethod("resolveActivity", Intent::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(launcherIntent(), 0)
        ) as ResolveInfo

        assertEquals(PackageManager.PERMISSION_GRANTED, permission)
        assertEquals(RUNTIME_UID, packageUid)
        assertEquals("com.test.minimal.MainActivity", launcherResult.activityInfo.name)
        assertTrue(original.calls.isEmpty())
    }

    @Test
    fun `intent query methods return snapshot results and merge launcher aggregate PMS`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )

        val activities = handler.invoke(
            proxy = Any(),
            method = apiMethod("queryIntentActivities", Intent::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(launcherIntent(), 0)
        ) as List<ResolveInfo>
        val services = handler.invoke(
            proxy = Any(),
            method = apiMethod("queryIntentServices", Intent::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(actionIntent("com.test.SYNC"), 0)
        ) as List<ResolveInfo>
        val receivers = handler.invoke(
            proxy = Any(),
            method = apiMethod("queryIntentReceivers", Intent::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(actionIntent("com.test.BOOT"), 0)
        ) as List<ResolveInfo>
        val providers = handler.invoke(
            proxy = Any(),
            method = apiMethod("queryIntentContentProviders", Intent::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(actionIntent("com.test.PROBE"), 0)
        ) as List<ResolveInfo>

        assertEquals(listOf("com.test.minimal.MainActivity"), activities.map { it.activityInfo.name })
        assertEquals(listOf("com.test.minimal.SyncService"), services.map { it.serviceInfo.name })
        assertEquals(listOf("com.test.minimal.BootReceiver"), receivers.map { it.activityInfo.name })
        assertEquals(listOf("com.test.minimal.ProbeProvider"), providers.map { it.providerInfo.name })
        assertEquals(listOf("queryIntentActivities:${Intent.ACTION_MAIN}"), original.calls)
    }

    @Test
    fun `hidden intent query overloads pass resolved MIME type to virtual matcher`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(mimeSnapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )
        val intent = actionIntent("com.test.MIME")
        val signature = arrayOf(
            Intent::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
        val args: Array<Any?> = arrayOf(intent, "image/png", 0L, 0)

        val activity = handler.invoke(Any(), apiMethod("resolveIntent", *signature), args) as ResolveInfo
        val activities = handler.invoke(
            Any(),
            apiMethod("queryIntentActivities", *signature),
            args
        ) as List<ResolveInfo>
        val service = handler.invoke(Any(), apiMethod("resolveService", *signature), args) as ResolveInfo
        val services = handler.invoke(
            Any(),
            apiMethod("queryIntentServices", *signature),
            args
        ) as List<ResolveInfo>
        val receivers = handler.invoke(
            Any(),
            apiMethod("queryIntentReceivers", *signature),
            args
        ) as List<ResolveInfo>
        val providers = handler.invoke(
            Any(),
            apiMethod("queryIntentContentProviders", *signature),
            args
        ) as List<ResolveInfo>

        assertEquals("com.test.minimal.MimeActivity", activity.activityInfo.name)
        assertEquals("com.test.minimal.MimeActivity", activities.single().activityInfo.name)
        assertEquals("com.test.minimal.MimeService", service.serviceInfo.name)
        assertEquals("com.test.minimal.MimeService", services.single().serviceInfo.name)
        assertEquals("com.test.minimal.MimeReceiver", receivers.single().activityInfo.name)
        assertEquals("com.test.minimal.MimeProvider", providers.single().providerInfo.name)
        assertTrue(original.calls.isEmpty())
    }

    @Test
    fun `installed permission and content provider aggregate methods return snapshot data`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = permissionAwareService(),
            runtimeUid = RUNTIME_UID
        )

        val packages = handler.invoke(
            proxy = Any(),
            method = apiMethod("getInstalledPackages", Int::class.javaPrimitiveType!!),
            args = arrayOf(0)
        ) as List<PackageInfo>
        val applications = handler.invoke(
            proxy = Any(),
            method = apiMethod("getInstalledApplications", Int::class.javaPrimitiveType!!),
            args = arrayOf(0)
        ) as List<ApplicationInfo>
        val holdingPermission = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackagesHoldingPermissions", Array<String>::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(arrayOf("android.permission.CAMERA"), 0)
        ) as List<PackageInfo>
        val providers = handler.invoke(
            proxy = Any(),
            method = apiMethod("queryContentProviders", String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            args = arrayOf("com.test.minimal:probe", RUNTIME_UID, 0)
        ) as List<ProviderInfo>

        assertEquals(listOf("base.package", "com.test.minimal"), packages.map { it.packageName })
        assertEquals(listOf("base.package", "com.test.minimal"), applications.map { it.packageName })
        assertEquals(listOf("com.test.minimal"), holdingPermission.map { it.packageName })
        assertEquals(listOf("com.test.minimal.ProbeProvider"), providers.map { it.name })
        assertEquals(listOf("getInstalledPackages:0", "getInstalledApplications:0"), original.calls)
    }

    @Test
    fun `unscoped launcher query preserves base apps while adding virtual launcher`() {
        val original = FakePackageManagerApiImpl(
            launcherActivities = listOf(resolveInfo("base.package", "base.package.MainActivity"))
        )
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )

        val activities = handler.invoke(
            proxy = Any(),
            method = apiMethod("queryIntentActivities", Intent::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(launcherIntent(), 0)
        ) as List<ResolveInfo>

        assertEquals(
            listOf("base.package/base.package.MainActivity", "com.test.minimal/com.test.minimal.MainActivity"),
            activities.map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        )
        assertEquals(listOf("queryIntentActivities:${Intent.ACTION_MAIN}"), original.calls)
    }

    @Test
    fun `aggregate PMS methods preserve list container shape when original returns slice-like result`() {
        val original = FakePackageManagerSliceApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )

        val result = handler.invoke(
            proxy = Any(),
            method = FakePackageManagerSliceApi::class.java.getMethod(
                "getInstalledPackages",
                Int::class.javaPrimitiveType!!
            ),
            args = arrayOf(0)
        ) as FakePackageInfoSlice

        assertEquals(listOf("base.package", "com.test.minimal"), result.getList().map { it.packageName })
        assertEquals(listOf("getInstalledPackages:0"), original.calls)
    }

    @Test
    fun `scoped intent query preserves hidden PMS slice shape without leaking real package results`() {
        val original = FakeResolveInfoSliceApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )
        val method = FakeResolveInfoSliceApi::class.java.getMethod(
            "queryIntentActivities",
            Intent::class.java,
            Int::class.javaPrimitiveType!!
        )

        val result = handler.invoke(
            proxy = Any(),
            method = method,
            args = arrayOf(scopedLauncherIntent(), 0)
        ) as FakeResolveInfoSlice

        assertEquals(
            listOf("com.test.minimal/com.test.minimal.MainActivity"),
            result.getList().map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        )
        assertEquals(listOf("queryIntentActivities:${Intent.ACTION_MAIN}"), original.calls)
    }

    @Test
    fun `virtual enabled settings fail closed without delegating to original PMS`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(
                snapshot = snapshot(),
                runtimeUid = RUNTIME_UID,
                enabledStateDispatcher = VirtualPackageEnabledStateDispatcher {
                    VirtualPackageEnabledStateDispatchResult.unavailable("test_authority_unavailable")
                }
            ),
            runtimeUid = RUNTIME_UID
        )
        val component = component("com.test.minimal.SyncService")

        val applicationState = handler.invoke(
            proxy = Any(),
            method = apiMethod("getApplicationEnabledSetting", String::class.java),
            args = arrayOf("com.test.minimal")
        ) as Int
        val componentState = handler.invoke(
            proxy = Any(),
            method = apiMethod("getComponentEnabledSetting", android.content.ComponentName::class.java),
            args = arrayOf(component)
        ) as Int
        val setApplicationResult = handler.invoke(
            proxy = Any(),
            method = apiMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            args = arrayOf("com.test.minimal", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
        )
        val setComponentResult = handler.invoke(
            proxy = Any(),
            method = apiMethod(
                "setComponentEnabledSetting",
                android.content.ComponentName::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            args = arrayOf(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        )

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, applicationState)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, componentState)
        assertEquals(null, setApplicationResult)
        assertEquals(null, setComponentResult)
        assertTrue(original.calls.isEmpty())
    }

    @Test
    fun `unrelated package delegates to original PMS and original exception is unwrapped`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )

        val delegated = handler.invoke(
            proxy = Any(),
            method = apiMethod("originalOnly", String::class.java),
            args = arrayOf("com.other")
        )

        assertEquals("base:com.other", delegated)
        assertEquals(listOf("originalOnly:com.other"), original.calls)
        val thrown = assertFailsWith<IllegalStateException> {
            handler.invoke(
                proxy = Any(),
                method = apiMethod("throwsFor", String::class.java),
                args = arrayOf("com.other")
            )
        }
        assertEquals("boom:com.other", thrown.message)
    }

    @Test
    fun `object methods use proxy identity semantics without delegating`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )
        val proxy = Any()

        val toStringResult = handler.invoke(proxy, Any::class.java.getMethod("toString"), null) as String
        val hashCodeResult = handler.invoke(proxy, Any::class.java.getMethod("hashCode"), null) as Int
        val equalsSelf = handler.invoke(proxy, Any::class.java.getMethod("equals", Any::class.java), arrayOf(proxy)) as Boolean
        val equalsOther = handler.invoke(proxy, Any::class.java.getMethod("equals", Any::class.java), arrayOf(Any())) as Boolean

        assertTrue(toStringResult.contains("VirtualPackageManagerInvocationHandler"))
        assertEquals(System.identityHashCode(proxy), hashCodeResult)
        assertEquals(true, equalsSelf)
        assertEquals(false, equalsOther)
        assertTrue(original.calls.isEmpty())
    }

    @Test
    fun `global handler delegates runtime uid aggregate queries when uid virtualization is disabled`() {
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID,
            virtualizeUidQueries = false
        )

        val packagesForUid = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackagesForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(RUNTIME_UID)
        ) as Array<String>
        val nameForUid = handler.invoke(
            proxy = Any(),
            method = apiMethod("getNameForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(RUNTIME_UID)
        ) as String

        assertContentEquals(arrayOf("base.uid.$RUNTIME_UID"), packagesForUid)
        assertEquals("base.name.$RUNTIME_UID", nameForUid)
        assertEquals(listOf("getPackagesForUid:$RUNTIME_UID", "getNameForUid:$RUNTIME_UID"), original.calls)
    }

    @Test
    fun `uid virtualization merges runtime uid packages with original PMS result`() {
        val original = FakePackageManagerApiImpl(
            packagesForUid = { arrayOf("host.package", "com.test.minimal") }
        )
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID,
            virtualizeUidQueries = true
        )

        val packagesForUid = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackagesForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(RUNTIME_UID)
        ) as Array<String>

        assertContentEquals(arrayOf("host.package", "com.test.minimal", "com.multiapp.instance.abc"), packagesForUid)
        assertEquals(listOf("getPackagesForUid:$RUNTIME_UID"), original.calls)
    }

    @Test
    fun `uid virtualization preserves original uid name and falls back to virtual name`() {
        val originalWithName = FakePackageManagerApiImpl(nameForUid = { "host.uid.name" })
        val handlerWithName = VirtualPackageManagerInvocationHandler(
            originalPackageManager = originalWithName,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID,
            virtualizeUidQueries = true
        )

        val originalName = handlerWithName.invoke(
            proxy = Any(),
            method = apiMethod("getNameForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(RUNTIME_UID)
        ) as String

        assertEquals("host.uid.name", originalName)
        assertEquals(listOf("getNameForUid:$RUNTIME_UID"), originalWithName.calls)

        val originalWithoutName = FakePackageManagerApiImpl(nameForUid = { null })
        val handlerWithoutName = VirtualPackageManagerInvocationHandler(
            originalPackageManager = originalWithoutName,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID,
            virtualizeUidQueries = true
        )

        val fallbackName = handlerWithoutName.invoke(
            proxy = Any(),
            method = apiMethod("getNameForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(RUNTIME_UID)
        ) as String

        assertEquals("com.test.minimal", fallbackName)
        assertEquals(listOf("getNameForUid:$RUNTIME_UID"), originalWithoutName.calls)
    }

    @Test
    fun `uid virtualization still delegates unrelated uid aggregate queries`() {
        val unrelatedUid = 98765
        val original = FakePackageManagerApiImpl()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID,
            virtualizeUidQueries = true
        )

        val packagesForUid = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackagesForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(unrelatedUid)
        ) as Array<String>
        val nameForUid = handler.invoke(
            proxy = Any(),
            method = apiMethod("getNameForUid", Int::class.javaPrimitiveType!!),
            args = arrayOf(unrelatedUid)
        ) as String

        assertContentEquals(arrayOf("base.uid.$unrelatedUid"), packagesForUid)
        assertEquals("base.name.$unrelatedUid", nameForUid)
        assertEquals(listOf("getPackagesForUid:$unrelatedUid", "getNameForUid:$unrelatedUid"), original.calls)
    }

    @Test
    fun `resolver routes package queries to matching snapshot service`() {
        val original = FakePackageManagerApiImpl()
        val first = snapshot(
            instanceId = "inst-001",
            originPackageName = "com.test.first",
            virtualPackageName = "com.multiapp.instance.first",
            label = "First"
        )
        val second = snapshot(
            instanceId = "inst-002",
            originPackageName = "com.test.second",
            virtualPackageName = "com.multiapp.instance.second",
            label = "Second"
        )
        val resolver = FakeResolver(listOf(first, second))
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(first, RUNTIME_UID),
            runtimeUid = RUNTIME_UID,
            serviceResolver = resolver
        )

        val secondInfo = handler.invoke(
            proxy = Any(),
            method = apiMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf("com.test.second", 0)
        ) as PackageInfo

        assertEquals("com.test.second", secondInfo.packageName)
        assertEquals("Second", secondInfo.applicationInfo?.nonLocalizedLabel)
        assertTrue(original.calls.isEmpty())
    }

    @Test
    fun `binder handler applies flags and keeps virtual signing queries off original PMS`() {
        val original = FakePackageManagerApiImpl()
        val certificate = "binder-verified-certificate".toByteArray()
        val handler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(
                snapshot = snapshot(),
                runtimeUid = RUNTIME_UID,
                packageSigningInfo = signingInfo(certificate)
            ),
            runtimeUid = RUNTIME_UID
        )

        val basic = handler.invoke(
            Any(),
            apiMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!),
            arrayOf("com.test.minimal", 0)
        ) as PackageInfo
        val applicationWithMetaData = handler.invoke(
            Any(),
            apiMethod("getApplicationInfo", String::class.java, Int::class.javaPrimitiveType!!),
            arrayOf("com.test.minimal", PackageManager.GET_META_DATA)
        ) as ApplicationInfo
        assertEquals("app", assertNotNull(applicationWithMetaData.metaData).getString("scope"))
        val requested = handler.invoke(
            Any(),
            apiMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!),
            arrayOf(
                "com.test.minimal",
                PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
            )
        ) as PackageInfo
        val packageSignatureResult = handler.invoke(
            Any(),
            apiMethod("checkSignatures", String::class.java, String::class.java),
            arrayOf("com.test.minimal", "com.multiapp.instance.abc")
        ) as Int
        val uidSignatureResult = handler.invoke(
            Any(),
            apiMethod("checkUidSignatures", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(RUNTIME_UID, RUNTIME_UID)
        ) as Int
        val hasCertificate = handler.invoke(
            Any(),
            apiMethod(
                "hasSigningCertificate",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!
            ),
            arrayOf("com.test.minimal", certificate, PackageManager.CERT_INPUT_RAW_X509)
        ) as Boolean

        val basicApplication = assertNotNull(basic.applicationInfo)
        val requestedApplication = assertNotNull(requested.applicationInfo)
        val requestedActivities = assertNotNull(requested.activities)
        assertNull(basic.activities)
        assertNull(basicApplication.metaData)
        assertEquals(1, requestedActivities.size)
        assertNotNull(requestedApplication.metaData)
        assertEquals(RUNTIME_UID, assertNotNull(requestedActivities.single().applicationInfo).uid)
        @Suppress("DEPRECATION")
        assertEquals(1, assertNotNull(requested.signatures).size)
        assertEquals(PackageManager.SIGNATURE_MATCH, packageSignatureResult)
        assertEquals(PackageManager.SIGNATURE_MATCH, uidSignatureResult)
        assertTrue(hasCertificate)
        assertTrue(original.calls.isEmpty())

        val unknownHandler = VirtualPackageManagerInvocationHandler(
            originalPackageManager = original,
            service = VirtualPackageService(snapshot(), RUNTIME_UID),
            runtimeUid = RUNTIME_UID
        )
        val unknownResult = unknownHandler.invoke(
            Any(),
            apiMethod("checkSignatures", String::class.java, String::class.java),
            arrayOf("com.test.minimal", "com.multiapp.instance.abc")
        ) as Int
        val unknownCertificate = unknownHandler.invoke(
            Any(),
            apiMethod(
                "hasUidSigningCertificate",
                Int::class.javaPrimitiveType!!,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!
            ),
            arrayOf(RUNTIME_UID, certificate, PackageManager.CERT_INPUT_RAW_X509)
        ) as Boolean

        assertEquals(PackageManager.SIGNATURE_NO_MATCH, unknownResult)
        assertFalse(unknownCertificate)
        assertTrue(original.calls.isEmpty())
    }

    private interface FakePackageManagerApi {
        fun getPackageInfo(packageName: String, flags: Int): PackageInfo?
        fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo?
        fun checkSignatures(packageName1: String, packageName2: String): Int
        fun checkUidSignatures(uid1: Int, uid2: Int): Int
        fun hasSigningCertificate(packageName: String, certificate: ByteArray, type: Int): Boolean
        fun hasUidSigningCertificate(uid: Int, certificate: ByteArray, type: Int): Boolean
        fun checkPermission(permissionName: String, packageName: String): Int
        fun getPackageUid(packageName: String, flags: Int): Int
        fun getPackagesForUid(uid: Int): Array<String>?
        fun getNameForUid(uid: Int): String?
        fun resolveActivity(intent: Intent, flags: Int): ResolveInfo?
        fun resolveIntent(intent: Intent, resolvedType: String?, flags: Long, userId: Int): ResolveInfo?
        fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo>
        fun queryIntentActivities(intent: Intent, resolvedType: String?, flags: Long, userId: Int): List<ResolveInfo>
        fun queryIntentServices(intent: Intent, flags: Int): List<ResolveInfo>
        fun queryIntentServices(intent: Intent, resolvedType: String?, flags: Long, userId: Int): List<ResolveInfo>
        fun resolveService(intent: Intent, resolvedType: String?, flags: Long, userId: Int): ResolveInfo?
        fun queryIntentReceivers(intent: Intent, flags: Int): List<ResolveInfo>
        fun queryIntentReceivers(intent: Intent, resolvedType: String?, flags: Long, userId: Int): List<ResolveInfo>
        fun queryIntentContentProviders(intent: Intent, flags: Int): List<ResolveInfo>
        fun queryIntentContentProviders(intent: Intent, resolvedType: String?, flags: Long, userId: Int): List<ResolveInfo>
        fun getInstalledPackages(flags: Int): List<PackageInfo>
        fun getInstalledApplications(flags: Int): List<ApplicationInfo>
        fun getPackagesHoldingPermissions(permissions: Array<String>, flags: Int): List<PackageInfo>
        fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo>
        fun getApplicationEnabledSetting(packageName: String): Int
        fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int)
        fun getComponentEnabledSetting(componentName: android.content.ComponentName): Int
        fun setComponentEnabledSetting(componentName: android.content.ComponentName, newState: Int, flags: Int)
        fun originalOnly(packageName: String): String
        fun throwsFor(packageName: String): String
    }

    private class FakePackageManagerApiImpl(
        private val packagesForUid: (Int) -> Array<String>? = { uid -> arrayOf("base.uid.$uid") },
        private val nameForUid: (Int) -> String? = { uid -> "base.name.$uid" },
        private val launcherActivities: List<ResolveInfo> = emptyList()
    ) : FakePackageManagerApi {
        val calls = mutableListOf<String>()

        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo? {
            calls += "getPackageInfo:$packageName"
            return PackageInfo().apply { this.packageName = "base.$packageName" }
        }

        override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo? {
            calls += "getApplicationInfo:$packageName"
            return ApplicationInfo().apply { this.packageName = "base.$packageName" }
        }

        override fun checkSignatures(packageName1: String, packageName2: String): Int {
            calls += "checkSignatures:$packageName1:$packageName2"
            return PackageManager.SIGNATURE_NO_MATCH
        }

        override fun checkUidSignatures(uid1: Int, uid2: Int): Int {
            calls += "checkUidSignatures:$uid1:$uid2"
            return PackageManager.SIGNATURE_NO_MATCH
        }

        override fun hasSigningCertificate(packageName: String, certificate: ByteArray, type: Int): Boolean {
            calls += "hasSigningCertificate:$packageName"
            return false
        }

        override fun hasUidSigningCertificate(uid: Int, certificate: ByteArray, type: Int): Boolean {
            calls += "hasUidSigningCertificate:$uid"
            return false
        }

        override fun checkPermission(permissionName: String, packageName: String): Int {
            calls += "checkPermission:$permissionName:$packageName"
            return PackageManager.PERMISSION_DENIED
        }

        override fun getPackageUid(packageName: String, flags: Int): Int {
            calls += "getPackageUid:$packageName"
            return -1
        }

        override fun getPackagesForUid(uid: Int): Array<String>? {
            calls += "getPackagesForUid:$uid"
            return packagesForUid(uid)
        }

        override fun getNameForUid(uid: Int): String? {
            calls += "getNameForUid:$uid"
            return nameForUid(uid)
        }

        override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? {
            calls += "resolveActivity:${intent.action}"
            return null
        }

        override fun resolveIntent(intent: Intent, resolvedType: String?, flags: Long, userId: Int): ResolveInfo? {
            calls += "resolveIntent:${intent.action}:$resolvedType"
            return null
        }

        override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> {
            calls += "queryIntentActivities:${intent.action}"
            return launcherActivities
        }

        override fun queryIntentActivities(
            intent: Intent,
            resolvedType: String?,
            flags: Long,
            userId: Int
        ): List<ResolveInfo> {
            calls += "queryIntentActivities:${intent.action}:$resolvedType"
            return emptyList()
        }

        override fun queryIntentServices(intent: Intent, flags: Int): List<ResolveInfo> {
            calls += "queryIntentServices:${intent.action}"
            return emptyList()
        }

        override fun queryIntentServices(
            intent: Intent,
            resolvedType: String?,
            flags: Long,
            userId: Int
        ): List<ResolveInfo> {
            calls += "queryIntentServices:${intent.action}:$resolvedType"
            return emptyList()
        }

        override fun resolveService(intent: Intent, resolvedType: String?, flags: Long, userId: Int): ResolveInfo? {
            calls += "resolveService:${intent.action}:$resolvedType"
            return null
        }

        override fun queryIntentReceivers(intent: Intent, flags: Int): List<ResolveInfo> {
            calls += "queryIntentReceivers:${intent.action}"
            return emptyList()
        }

        override fun queryIntentReceivers(
            intent: Intent,
            resolvedType: String?,
            flags: Long,
            userId: Int
        ): List<ResolveInfo> {
            calls += "queryIntentReceivers:${intent.action}:$resolvedType"
            return emptyList()
        }

        override fun queryIntentContentProviders(intent: Intent, flags: Int): List<ResolveInfo> {
            calls += "queryIntentContentProviders:${intent.action}"
            return emptyList()
        }

        override fun queryIntentContentProviders(
            intent: Intent,
            resolvedType: String?,
            flags: Long,
            userId: Int
        ): List<ResolveInfo> {
            calls += "queryIntentContentProviders:${intent.action}:$resolvedType"
            return emptyList()
        }

        override fun getInstalledPackages(flags: Int): List<PackageInfo> {
            calls += "getInstalledPackages:$flags"
            return listOf(PackageInfo().apply { packageName = "base.package" })
        }

        override fun getInstalledApplications(flags: Int): List<ApplicationInfo> {
            calls += "getInstalledApplications:$flags"
            return listOf(ApplicationInfo().apply { packageName = "base.package" })
        }

        override fun getPackagesHoldingPermissions(permissions: Array<String>, flags: Int): List<PackageInfo> {
            calls += "getPackagesHoldingPermissions:${permissions.joinToString()}:$flags"
            return emptyList()
        }

        override fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> {
            calls += "queryContentProviders:$processName:$uid:$flags"
            return emptyList()
        }

        override fun getApplicationEnabledSetting(packageName: String): Int {
            calls += "getApplicationEnabledSetting:$packageName"
            return PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }

        override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) {
            calls += "setApplicationEnabledSetting:$packageName:$newState:$flags"
        }

        override fun getComponentEnabledSetting(componentName: android.content.ComponentName): Int {
            calls += "getComponentEnabledSetting:${componentName.packageName}/${componentName.className}"
            return PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }

        override fun setComponentEnabledSetting(
            componentName: android.content.ComponentName,
            newState: Int,
            flags: Int
        ) {
            calls += "setComponentEnabledSetting:${componentName.packageName}/${componentName.className}:$newState:$flags"
        }

        override fun originalOnly(packageName: String): String {
            calls += "originalOnly:$packageName"
            return "base:$packageName"
        }

        override fun throwsFor(packageName: String): String {
            calls += "throwsFor:$packageName"
            throw IllegalStateException("boom:$packageName")
        }
    }

    private interface FakePackageManagerSliceApi {
        fun getInstalledPackages(flags: Int): FakePackageInfoSlice
    }

    private class FakePackageManagerSliceApiImpl : FakePackageManagerSliceApi {
        val calls = mutableListOf<String>()

        override fun getInstalledPackages(flags: Int): FakePackageInfoSlice {
            calls += "getInstalledPackages:$flags"
            return FakePackageInfoSlice(listOf(PackageInfo().apply { packageName = "base.package" }))
        }
    }

    private class FakePackageInfoSlice(private var mList: List<PackageInfo>) {
        fun getList(): List<PackageInfo> = mList
    }

    private interface FakeResolveInfoSliceApi {
        fun queryIntentActivities(intent: Intent, flags: Int): FakeResolveInfoSlice
    }

    private class FakeResolveInfoSliceApiImpl : FakeResolveInfoSliceApi {
        val calls = mutableListOf<String>()

        override fun queryIntentActivities(intent: Intent, flags: Int): FakeResolveInfoSlice {
            calls += "queryIntentActivities:${intent.action}"
            return FakeResolveInfoSlice(
                listOf(
                    ResolveInfo().apply {
                        activityInfo = ActivityInfo().apply {
                            packageName = "com.test.minimal"
                            name = "com.test.minimal.RealInstalledActivity"
                        }
                    }
                )
            )
        }
    }

    private class FakeResolveInfoSlice(private var mList: List<ResolveInfo>) {
        fun getList(): List<ResolveInfo> = mList
    }

    private class FakeResolver(snapshots: List<VirtualPackageSnapshot>) : VirtualPackageManagerServiceResolver {
        private val services = snapshots.associate { snapshot ->
            snapshot.originPackageName to VirtualPackageService(snapshot, RUNTIME_UID)
        }

        override fun serviceForPackage(packageName: String?): VirtualPackageService? = services[packageName]

        override fun serviceForComponent(component: android.content.ComponentName?): VirtualPackageService? =
            serviceForPackage(component?.packageName)

        override fun serviceForAuthority(authority: String?): VirtualPackageService? = null

        override fun serviceForIntent(intent: Intent?): VirtualPackageService? =
            serviceForPackage(intent?.`package`) ?: serviceForComponent(intent?.component)
    }

    private fun apiMethod(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method =
        FakePackageManagerApi::class.java.getMethod(name, *parameterTypes)

    private fun launcherIntent() = mockk<Intent> {
        every { component } returns null
        every { `package` } returns null
        every { action } returns Intent.ACTION_MAIN
        every { categories } returns setOf(Intent.CATEGORY_LAUNCHER)
        every { scheme } returns null
    }

    private fun scopedLauncherIntent() = mockk<Intent> {
        every { component } returns null
        every { `package` } returns "com.test.minimal"
        every { action } returns Intent.ACTION_MAIN
        every { categories } returns setOf(Intent.CATEGORY_LAUNCHER)
        every { scheme } returns null
    }

    private fun actionIntent(action: String) = mockk<Intent> {
        every { component } returns null
        every { `package` } returns null
        every { this@mockk.action } returns action
        every { categories } returns emptySet()
        every { scheme } returns null
    }

    private fun component(
        className: String,
        packageName: String = "com.test.minimal"
    ) = mockk<android.content.ComponentName> {
        every { this@mockk.packageName } returns packageName
        every { this@mockk.className } returns className
    }

    private fun resolveInfo(packageName: String, className: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = className
        }
    }

    private fun permissionAwareService() = VirtualPackageService(
        snapshot = snapshot(),
        runtimeUid = RUNTIME_UID,
        permissionCheckDispatcher = VirtualPermissionCheckDispatcher { request ->
            VirtualPermissionCheckDispatchResult(
                handled = true,
                granted = request.permissionName == "android.permission.CAMERA",
                reason = "test_permission_state"
            )
        }
    )

    private fun snapshot(
        instanceId: String = "inst-001",
        originPackageName: String = "com.test.minimal",
        virtualPackageName: String = "com.multiapp.instance.abc",
        label: String = "MinimalTest"
    ) = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        applicationLabel = label,
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = "/data/inst",
        metaData = mapOf("scope" to "app"),
        launcherActivityName = "$originPackageName.MainActivity",
        activities = listOf(
            ResolvedComponent(
                name = "$originPackageName.MainActivity",
                exported = true,
                intentFilters = listOf(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER),
                metaData = mapOf("scope" to "activity")
            )
        ),
        services = listOf(
            ResolvedComponent(
                name = "$originPackageName.SyncService",
                exported = false,
                intentFilters = listOf("com.test.SYNC")
            )
        ),
        receivers = listOf(
            ResolvedComponent(
                name = "$originPackageName.BootReceiver",
                exported = false,
                intentFilters = listOf("com.test.BOOT")
            )
        ),
        providers = listOf(
            ResolvedComponent(
                name = "$originPackageName.ProbeProvider",
                exported = false,
                processName = "$originPackageName:probe",
                intentFilters = listOf("com.test.PROBE"),
                authorities = listOf("$originPackageName.probe")
            )
        ),
        permissions = listOf("android.permission.CAMERA")
    )

    private fun mimeSnapshot(): VirtualPackageSnapshot {
        val base = snapshot()
        fun filter() = listOf(
            ResolvedIntentFilter(
                actions = listOf("com.test.MIME"),
                dataMimeTypes = listOf("image/*")
            )
        )
        return base.copy(
            activities = base.activities + ResolvedComponent(
                name = "com.test.minimal.MimeActivity",
                exported = true,
                resolvedIntentFilters = filter()
            ),
            services = base.services + ResolvedComponent(
                name = "com.test.minimal.MimeService",
                exported = true,
                resolvedIntentFilters = filter()
            ),
            receivers = base.receivers + ResolvedComponent(
                name = "com.test.minimal.MimeReceiver",
                exported = true,
                resolvedIntentFilters = filter()
            ),
            providers = base.providers + ResolvedComponent(
                name = "com.test.minimal.MimeProvider",
                exported = true,
                authorities = listOf("com.test.minimal.mime"),
                resolvedIntentFilters = filter()
            )
        )
    }

    private fun signingInfo(certificate: ByteArray): VirtualPackageSigningInfo =
        VirtualPackageSigningInfo(
            legacySignatures = arrayOf(Signature(certificate)),
            signingInfo = null,
            signerSha256Digests = listOf(certificate.sha256Hex())
        )

    private fun ByteArray.sha256Hex(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val RUNTIME_UID = 42420
    }
}

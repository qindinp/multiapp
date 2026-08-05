package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class VirtualContentProviderIdentityProxyTest {

    @Test
    fun `provider proxy rewrites AttributionSource identity but preserves business strings`() {
        val provider = RecordingProvider()
        val proxy = VirtualContentProviderIdentityProxy.wrapProviderForInterface(
            provider = provider,
            providerInterface = FakeProvider::class.java,
            sourcePackages = GUEST_PACKAGES,
            hostPackageName = HOST_PACKAGE,
            runtimeUid = HOST_UID
        ) as FakeProvider
        val source = FakeAttributionSourceState().apply {
            uid = HOST_UID
            packageName = ORIGIN_PACKAGE
        }

        proxy.modernCall(source, ORIGIN_PACKAGE)

        assertNotSame(source, provider.lastSource)
        assertEquals(HOST_PACKAGE, provider.lastSource?.packageName)
        assertEquals(HOST_UID, provider.lastSource?.uid)
        assertEquals(ORIGIN_PACKAGE, provider.lastBusinessArg)
    }

    @Test
    fun `legacy provider proxy rewrites only the leading calling package`() {
        val provider = RecordingProvider()
        val proxy = VirtualContentProviderIdentityProxy.wrapProviderForInterface(
            provider = provider,
            providerInterface = FakeProvider::class.java,
            sourcePackages = GUEST_PACKAGES,
            hostPackageName = HOST_PACKAGE,
            runtimeUid = HOST_UID
        ) as FakeProvider

        proxy.legacyCall(ORIGIN_PACKAGE, "settings", ORIGIN_PACKAGE)

        assertEquals(HOST_PACKAGE, provider.lastCallingPackage)
        assertEquals("settings", provider.lastAuthority)
        assertEquals(ORIGIN_PACKAGE, provider.lastBusinessArg)
    }

    @Test
    fun `holder patch replaces the provider interface and is idempotent`() {
        val original = RecordingProvider()
        val holder = FakeHolder(original)

        val first = VirtualContentProviderIdentityProxy.patchHolderProviderForInterface(
            holder = holder,
            providerInterface = FakeProvider::class.java,
            sourcePackages = GUEST_PACKAGES,
            hostPackageName = HOST_PACKAGE,
            runtimeUid = HOST_UID
        )
        val installed = holder.provider
        val second = VirtualContentProviderIdentityProxy.patchHolderProviderForInterface(
            holder = holder,
            providerInterface = FakeProvider::class.java,
            sourcePackages = GUEST_PACKAGES,
            hostPackageName = HOST_PACKAGE,
            runtimeUid = HOST_UID
        )

        assertTrue(first.patched)
        assertTrue(second.patched)
        assertTrue(installed === holder.provider)
    }

    @Test
    fun `settings style holder cache is cleared before provider reacquisition`() {
        val holder = FakeSettingsContentProviderHolder(RecordingProvider())

        assertTrue(VirtualContentProviderIdentityProxy.clearCachedProviderHolder(holder))
        assertEquals(null, holder.mContentProvider)
    }

    @Test
    fun `getHistoricalProcessExitReasons returns empty list safe default`() {
        // 2026-08-05 Round 4 真机：微博 AqtsWrapper 后台线程调该方法，需 signature 级
        // DUMP 权限，虚拟 uid 无权限 → 系统 AMS 拒绝杀进程。容器侧拦截返回空列表。
        val safe = VirtualContentProviderIdentityProxy.amsPermissionGatedSafeDefault(
            "getHistoricalProcessExitReasons"
        )
        assertTrue(safe is List<*>)
        assertTrue((safe as List<*>).isEmpty())
    }

    @Test
    fun `non permission gated ams methods are not intercepted`() {
        // 只拦截确认无副作用且容器无法合法取得数据的权限门禁方法
        assertTrue(
            VirtualContentProviderIdentityProxy.amsPermissionGatedSafeDefault("getContentProvider") == null
        )
        assertTrue(
            VirtualContentProviderIdentityProxy.amsPermissionGatedSafeDefault("startActivity") == null
        )
    }

    private interface FakeProvider {
        fun modernCall(source: FakeAttributionSourceState, businessArg: String)
        fun legacyCall(callingPackage: String, authority: String, businessArg: String)
    }

    private class RecordingProvider : FakeProvider {
        var lastSource: FakeAttributionSourceState? = null
        var lastCallingPackage: String? = null
        var lastAuthority: String? = null
        var lastBusinessArg: String? = null

        override fun modernCall(source: FakeAttributionSourceState, businessArg: String) {
            lastSource = source
            lastBusinessArg = businessArg
        }

        override fun legacyCall(callingPackage: String, authority: String, businessArg: String) {
            lastCallingPackage = callingPackage
            lastAuthority = authority
            lastBusinessArg = businessArg
        }
    }

    private class FakeHolder(var provider: FakeProvider)

    private class FakeSettingsContentProviderHolder(var mContentProvider: FakeProvider?)

    private class FakeAttributionSourceState {
        var uid: Int = -1
        var packageName: String? = null
        var next: FakeAttributionSourceState? = null
    }

    private companion object {
        const val ORIGIN_PACKAGE = "li.songe.gkd"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val HOST_UID = 10466
        val GUEST_PACKAGES = setOf(ORIGIN_PACKAGE, "com.multiapp.instance.abc")
    }
}

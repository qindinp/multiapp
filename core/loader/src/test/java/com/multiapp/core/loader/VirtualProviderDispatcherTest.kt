package com.multiapp.core.loader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class VirtualProviderDispatcherTest {

    @Test
    fun `dispatch reports unbound runtime for known provider before hosted bootstrap`() {
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val dispatcher = VirtualProviderDispatcher(
            hostPackageName = "com.multiapp.app",
            packageRegistry = registry,
            processRuntime = VirtualProcessRuntime()
        )

        val result = dispatcher.dispatch("inst-001", "com.test.minimal.probe")

        val unbound = assertIs<VirtualProviderDispatchResult.RuntimeNotBound>(result)
        assertEquals("com.test.minimal.ProbeProvider", unbound.resolution.providerClassName)
        assertEquals(VirtualProviderEvidence.Operation.ACQUIRE_PROVIDER, unbound.evidence.operation)
        assertEquals(false, unbound.evidence.success)
        assertEquals("RUNTIME_NOT_BOUND", unbound.evidence.reason)
    }

    @Test
    fun `dispatch creates provider when process runtime is bound`() {
        val snapshot = snapshot()
        val registry = VirtualPackageRegistry().apply { register(snapshot) }
        val processRuntime = VirtualProcessRuntime()
        val provider = FakeProvider()
        val attached = mutableListOf<ProviderInfo>()
        val providerRuntime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> provider },
            providerAttacher = ProviderAttacher { _, _, info -> attached += info }
        )
        processRuntime.bindApplication("inst-001") {
            hostedResult(snapshot, success = true, guestClassLoader = ClassLoader.getSystemClassLoader())
        }
        val dispatcher = VirtualProviderDispatcher(
            hostPackageName = "com.multiapp.app",
            packageRegistry = registry,
            processRuntime = processRuntime,
            providerRuntime = providerRuntime,
            hostContext = mockk<Context>(relaxed = true)
        )

        val first = dispatcher.dispatch("inst-001", "com.test.minimal.probe")
        val second = dispatcher.dispatch("inst-001", "com.test.minimal.probe")

        val created = assertIs<VirtualProviderDispatchResult.ProviderReady>(first)
        val cached = assertIs<VirtualProviderDispatchResult.ProviderReady>(second)
        assertEquals(false, created.cached)
        assertEquals(true, cached.cached)
        assertSame(provider, created.provider)
        assertSame(provider, cached.provider)
        assertEquals(true, created.evidence.success)
        assertEquals("com.test.minimal.probe", created.evidence.guestAuthority)
        assertEquals("com.multiapp.app.multiapp.provider.stub", created.evidence.proxyAuthority)
        assertEquals(1, attached.size)
        assertEquals("com.test.minimal.probe", attached.single().authority)
    }

    @Test
    fun `dispatch reports missing instance`() {
        val dispatcher = VirtualProviderDispatcher(
            hostPackageName = "com.multiapp.app",
            packageRegistry = VirtualPackageRegistry()
        )

        val result = dispatcher.dispatch("missing", "com.test.minimal.probe")

        assertIs<VirtualProviderDispatchResult.InstanceNotFound>(result)
    }

    @Test
    fun `dispatch reports missing provider for known instance`() {
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val dispatcher = VirtualProviderDispatcher(
            hostPackageName = "com.multiapp.app",
            packageRegistry = registry
        )

        val result = dispatcher.dispatch("inst-001", "missing.authority")

        val missing = assertIs<VirtualProviderDispatchResult.ProviderNotFound>(result)
        assertEquals("inst-001", missing.instanceId)
        assertEquals("missing.authority", missing.guestAuthority)
        assertEquals(false, missing.evidence.success)
        assertEquals("PROVIDER_NOT_FOUND", missing.evidence.reason)
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
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                authorities = listOf("com.test.minimal.probe")
            )
        )
    )

    private fun hostedResult(
        snapshot: VirtualPackageSnapshot,
        success: Boolean,
        guestClassLoader: ClassLoader?
    ) = HostedBootstrapResult(
        instanceId = snapshot.instanceId,
        installId = snapshot.originPackageName,
        originPackageName = snapshot.originPackageName,
        virtualPackageName = snapshot.virtualPackageName,
        applicationLabel = snapshot.applicationLabel,
        originApkPath = snapshot.sourceDir,
        dataRoot = snapshot.dataDir,
        guestClassLoader = guestClassLoader,
        guestApplication = null,
        installRecord = null,
        packageSnapshot = snapshot,
        launcherActivityClassName = "com.test.minimal.MainActivity",
        stageResults = emptyList(),
        summary = emptyList<BootstrapResult>().toSummary(),
        success = success,
        diagnostics = null
    )

    private class FakeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }
}

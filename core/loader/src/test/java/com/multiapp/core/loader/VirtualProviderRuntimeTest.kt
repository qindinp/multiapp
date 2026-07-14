package com.multiapp.core.loader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class VirtualProviderRuntimeTest {

    @Test
    fun `getOrCreate creates attaches and caches provider`() {
        val provider = FakeProvider()
        val attached = mutableListOf<ProviderInfo>()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> provider },
            providerAttacher = ProviderAttacher { _, _, info -> attached += info }
        )
        val request = request()

        val first = runtime.getOrCreate(request)
        val second = runtime.getOrCreate(request)

        val created = assertIs<VirtualProviderRuntimeResult.Created>(first)
        val cached = assertIs<VirtualProviderRuntimeResult.Cached>(second)
        assertSame(provider, created.provider)
        assertSame(provider, cached.provider)
        assertEquals(1, attached.size)
        assertEquals("com.test.minimal.probe", attached.single().authority)
    }

    @Test
    fun `getOrCreate returns create failure without caching`() {
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> error("boom") },
            providerAttacher = ProviderAttacher { _, _, _ -> }
        )

        val result = runtime.getOrCreate(request())

        assertIs<VirtualProviderRuntimeResult.CreateFailed>(result)
        assertEquals(null, runtime.get(request().resolution))
    }

    @Test
    fun `getOrCreate returns attach failure without caching`() {
        val provider = FakeProvider()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> provider },
            providerAttacher = ProviderAttacher { _, _, _ -> error("attach failed") }
        )

        val result = runtime.getOrCreate(request())

        val failed = assertIs<VirtualProviderRuntimeResult.AttachFailed>(result)
        assertSame(provider, failed.provider)
        assertEquals(null, runtime.get(request().resolution))
    }

    @Test
    fun `same origin provider cache is scoped by instance id`() {
        val createdProviders = mutableListOf<FakeProvider>()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ ->
                FakeProvider().also { createdProviders += it }
            },
            providerAttacher = ProviderAttacher { _, _, _ -> }
        )
        val firstRequest = request(snapshot(instanceId = "inst-a", dataDir = "/data/inst-a"))
        val secondRequest = request(snapshot(instanceId = "inst-b", dataDir = "/data/inst-b"))

        val first = assertIs<VirtualProviderRuntimeResult.Created>(runtime.getOrCreate(firstRequest))
        val second = assertIs<VirtualProviderRuntimeResult.Created>(runtime.getOrCreate(secondRequest))
        val firstCached = assertIs<VirtualProviderRuntimeResult.Cached>(runtime.getOrCreate(firstRequest))
        val secondCached = assertIs<VirtualProviderRuntimeResult.Cached>(runtime.getOrCreate(secondRequest))

        assertEquals(2, createdProviders.size)
        assertNotSame(first.provider, second.provider)
        assertSame(first.provider, firstCached.provider)
        assertSame(second.provider, secondCached.provider)
        assertEquals("inst-a", first.resolution.instanceId)
        assertEquals("inst-b", second.resolution.instanceId)
        assertEquals("com.test.minimal.probe", first.resolution.guestAuthority)
        assertEquals("com.test.minimal.probe", second.resolution.guestAuthority)
    }

    private fun request(snapshot: VirtualPackageSnapshot = snapshot()): VirtualProviderCreateRequest {
        val resolution = VirtualProviderManager("com.multiapp.app", runtimeUidProvider = { RUNTIME_UID })
            .resolve(snapshot, "com.test.minimal.probe")!!
        val config = VirtualContextConfig(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = snapshot.nativeLibraryDir,
            classLoader = ClassLoader.getSystemClassLoader(),
            applicationLabel = snapshot.applicationLabel,
            packageSnapshot = snapshot
        )
        return VirtualProviderCreateRequest(
            resolution = resolution,
            guestContext = mockk<Context>(relaxed = true),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            config = config
        )
    }

    private fun snapshot(
        instanceId: String = "inst-001",
        dataDir: String = "/data/inst"
    ) = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.$instanceId",
        applicationLabel = "MinimalTest",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = dataDir,
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                authorities = listOf("com.test.minimal.probe")
            )
        )
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

    private companion object {
        const val RUNTIME_UID = 42420
    }
}

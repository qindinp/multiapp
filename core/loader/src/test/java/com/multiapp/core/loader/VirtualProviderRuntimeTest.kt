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

    private fun request(): VirtualProviderCreateRequest {
        val snapshot = snapshot()
        val resolution = VirtualProviderManager("com.multiapp.app")
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

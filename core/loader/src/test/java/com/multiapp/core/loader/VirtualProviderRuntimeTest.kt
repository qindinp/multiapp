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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `concurrent aliases share one provider creation and attach flight`() {
        val createEntered = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val createCount = AtomicInteger()
        val attachCount = AtomicInteger()
        val onCreateCount = AtomicInteger()
        val attachedAuthority = AtomicReference<String>()
        val provider = FakeProvider { onCreateCount.incrementAndGet() }
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ ->
                createCount.incrementAndGet()
                createEntered.countDown()
                check(releaseCreate.await(5, TimeUnit.SECONDS)) { "provider creation was not released" }
                provider
            },
            providerAttacher = ProviderAttacher { attachedProvider, _, info ->
                attachCount.incrementAndGet()
                attachedAuthority.set(info.authority)
                attachedProvider.onCreate()
            }
        )
        val snapshot = snapshot(
            authorities = listOf("com.test.minimal.probe", "com.test.minimal.probe.alias")
        )
        val primaryRequest = request(snapshot, "com.test.minimal.probe")
        val aliasRequest = request(snapshot, "com.test.minimal.probe.alias")
        val executor = Executors.newFixedThreadPool(2)

        try {
            val primaryFuture = executor.submit<VirtualProviderRuntimeResult> {
                runtime.getOrCreate(primaryRequest)
            }
            assertTrue(createEntered.await(5, TimeUnit.SECONDS))
            val aliasFuture = executor.submit<VirtualProviderRuntimeResult> {
                runtime.getOrCreate(aliasRequest)
            }

            releaseCreate.countDown()

            val created = assertIs<VirtualProviderRuntimeResult.Created>(
                primaryFuture.get(5, TimeUnit.SECONDS)
            )
            val cached = assertIs<VirtualProviderRuntimeResult.Cached>(
                aliasFuture.get(5, TimeUnit.SECONDS)
            )
            assertSame(provider, created.provider)
            assertSame(provider, cached.provider)
            assertSame(provider, runtime.get(aliasRequest.resolution))
            assertEquals("com.test.minimal.probe.alias", cached.resolution.guestAuthority)
            assertEquals(1, createCount.get())
            assertEquals(1, attachCount.get())
            assertEquals(1, onCreateCount.get())
            assertEquals(
                "com.test.minimal.probe;com.test.minimal.probe.alias",
                attachedAuthority.get()
            )
        } finally {
            releaseCreate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `same instance package refresh creates a new provider generation`() {
        val createdProviders = mutableListOf<FakeProvider>()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ ->
                FakeProvider().also { createdProviders += it }
            },
            providerAttacher = ProviderAttacher { _, _, _ -> }
        )
        val oldRequest = request(
            snapshot(
                sourceSha256 = "sha256-old",
                versionCode = 42
            )
        )
        val refreshedRequest = request(
            snapshot(
                sourceSha256 = "sha256-refreshed",
                versionCode = 43
            )
        )

        val oldProvider = assertIs<VirtualProviderRuntimeResult.Created>(
            runtime.getOrCreate(oldRequest)
        ).provider
        val refreshedProvider = assertIs<VirtualProviderRuntimeResult.Created>(
            runtime.getOrCreate(refreshedRequest)
        ).provider

        assertEquals(2, createdProviders.size)
        assertNotSame(oldProvider, refreshedProvider)
        assertSame(oldProvider, runtime.get(oldRequest))
        assertSame(refreshedProvider, runtime.get(refreshedRequest))
        assertIs<VirtualProviderRuntimeResult.Cached>(runtime.getOrCreate(oldRequest))
        assertIs<VirtualProviderRuntimeResult.Cached>(runtime.getOrCreate(refreshedRequest))
    }

    @Test
    fun `split path change creates a new provider generation without split digests`() {
        val createdProviders = mutableListOf<FakeProvider>()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ ->
                FakeProvider().also { createdProviders += it }
            },
            providerAttacher = ProviderAttacher { _, _, _ -> }
        )
        val oldRequest = request(
            snapshot(
                sourceSha256 = "same-base-digest",
                splitSourceDirs = listOf("/data/apks/feature-old.apk")
            )
        )
        val refreshedRequest = request(
            snapshot(
                sourceSha256 = "same-base-digest",
                splitSourceDirs = listOf("/data/apks/feature-new.apk")
            )
        )

        val oldProvider = assertIs<VirtualProviderRuntimeResult.Created>(
            runtime.getOrCreate(oldRequest)
        ).provider
        val refreshedProvider = assertIs<VirtualProviderRuntimeResult.Created>(
            runtime.getOrCreate(refreshedRequest)
        ).provider

        assertEquals(2, createdProviders.size)
        assertNotSame(oldProvider, refreshedProvider)
        assertSame(oldProvider, runtime.get(oldRequest))
        assertSame(refreshedProvider, runtime.get(refreshedRequest))
    }

    private fun request(
        snapshot: VirtualPackageSnapshot = snapshot(),
        authority: String = "com.test.minimal.probe"
    ): VirtualProviderCreateRequest {
        val resolution = VirtualProviderManager("com.multiapp.app", runtimeUidProvider = { RUNTIME_UID })
            .resolve(snapshot, authority)!!
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
        dataDir: String = "/data/inst",
        authorities: List<String> = listOf("com.test.minimal.probe"),
        sourceSha256: String? = null,
        versionCode: Long = 42,
        splitSourceDirs: List<String> = emptyList()
    ) = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.$instanceId",
        applicationLabel = "MinimalTest",
        versionCode = versionCode,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        sourceSha256 = sourceSha256,
        splitSourceDirs = splitSourceDirs,
        splitPublicSourceDirs = splitSourceDirs,
        dataDir = dataDir,
        providers = listOf(
            ResolvedComponent(
                name = "com.test.minimal.ProbeProvider",
                exported = false,
                authorities = authorities
            )
        )
    )

    private class FakeProvider(
        private val onCreateCallback: () -> Unit = {}
    ) : ContentProvider() {
        override fun onCreate(): Boolean {
            onCreateCallback()
            return true
        }
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

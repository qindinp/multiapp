package com.multiapp.core.loader

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderPreinstallStageTest {

    @Test
    fun `legacy null process installs main provider and skips custom provider`() {
        val createCount = AtomicInteger()
        val attachCount = AtomicInteger()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ ->
                createCount.incrementAndGet()
                FakeProvider()
            },
            providerAttacher = ProviderAttacher { _, _, _ -> attachCount.incrementAndGet() }
        )
        val snapshot = snapshot(
            processName = null,
            providers = listOf(
                provider("com.test.minimal.MainProvider", "com.test.minimal.main", processName = null),
                provider("com.test.minimal.RemoteProvider", "com.test.minimal.remote", processName = ":remote")
            )
        )

        val result = preinstaller(runtime).preinstall(request(snapshot))

        assertEquals(GuestProviderPreinstallStatus.PARTIAL, result.status)
        assertEquals(1, result.attemptedProviderCount)
        assertEquals(1, result.installedProviderCount)
        assertEquals(1, result.skippedProviderCount)
        assertEquals(listOf("com.test.minimal.main"), result.installedAuthorities)
        assertEquals(listOf("CUSTOM_PROCESS_NOT_BOUND"), result.skippedReasons)
        assertEquals(1, createCount.get())
        assertEquals(1, attachCount.get())

        val skipped = result.skippedProviders.single()
        assertEquals("com.test.minimal.RemoteProvider", skipped.providerClassName)
        assertEquals(":remote", skipped.declaredProcessName)
        assertEquals("com.test.minimal:remote", skipped.effectiveProcessName)
        assertEquals(GuestProviderPreinstallSkipReason.CUSTOM_PROCESS_NOT_BOUND, skipped.reason)
    }

    @Test
    fun `custom process provider is fail closed before runtime creation`() {
        val snapshot = snapshot(
            providers = listOf(
                provider(
                    "com.test.minimal.RemoteProvider",
                    "com.test.minimal.remote",
                    processName = "com.test.minimal:remote"
                )
            )
        )
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> error("custom process provider must not be created") },
            providerAttacher = ProviderAttacher { _, _, _ -> error("custom process provider must not attach") }
        )

        val result = preinstaller(runtime).preinstall(request(snapshot))

        assertEquals(GuestProviderPreinstallStatus.SKIPPED, result.status)
        assertEquals(0, result.attemptedProviderCount)
        assertEquals(0, result.installedProviderCount)
        assertEquals(1, result.skippedProviderCount)
        assertEquals(listOf("CUSTOM_PROCESS_NOT_BOUND"), result.skippedReasons)
        assertEquals(
            GuestProviderPreinstallSkipReason.CUSTOM_PROCESS_NOT_BOUND,
            result.skippedProviders.single().reason
        )
    }

    @Test
    fun `multi authority provider is installed once and evidence keeps every authority`() {
        val createCount = AtomicInteger()
        val runtime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ ->
                createCount.incrementAndGet()
                FakeProvider()
            },
            providerAttacher = ProviderAttacher { _, _, _ -> }
        )
        val snapshot = snapshot(
            providers = listOf(
                ResolvedComponent(
                    name = "com.test.minimal.MultiAuthorityProvider",
                    exported = false,
                    authorities = listOf(
                        "com.test.minimal.primary",
                        "com.test.minimal.alias"
                    )
                )
            )
        )

        val result = preinstaller(runtime).preinstall(request(snapshot))

        assertEquals(GuestProviderPreinstallStatus.PASS, result.status)
        assertEquals(1, result.totalProviderCount)
        assertEquals(1, result.attemptedProviderCount)
        assertEquals(1, result.installedProviderCount)
        assertEquals(0, result.cachedProviderCount)
        assertEquals(
            listOf("com.test.minimal.primary", "com.test.minimal.alias"),
            result.installedAuthorities
        )
        assertEquals(1, createCount.get())
    }

    private fun preinstaller(runtime: VirtualProviderRuntime) = GuestProviderPreinstaller(
        providerRuntime = runtime,
        providerManagerFactory = { hostPackageName, processSlot ->
            VirtualProviderManager(
                hostPackageName = hostPackageName,
                processSlot = processSlot,
                runtimeUidProvider = { RUNTIME_UID }
            )
        }
    )

    private fun request(snapshot: VirtualPackageSnapshot): GuestProviderPreinstallRequest {
        val classLoader = ClassLoader.getSystemClassLoader()
        return GuestProviderPreinstallRequest(
            hostPackageName = "com.multiapp.app",
            snapshot = snapshot,
            application = mockk<Application>(relaxed = true),
            guestClassLoader = classLoader,
            config = VirtualContextConfig(
                instanceId = snapshot.instanceId,
                originPackageName = snapshot.originPackageName,
                virtualPackageName = snapshot.virtualPackageName,
                dataDir = snapshot.dataDir,
                sourceDir = snapshot.sourceDir,
                nativeLibraryDir = snapshot.nativeLibraryDir,
                classLoader = classLoader,
                packageSnapshot = snapshot
            )
        )
    }

    private fun snapshot(
        processName: String? = null,
        providers: List<ResolvedComponent>
    ) = VirtualPackageSnapshot(
        instanceId = "inst-provider",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.provider",
        applicationLabel = "Provider Test",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/provider.apk",
        dataDir = "/data/instances/provider",
        processName = processName,
        providers = providers
    )

    private fun provider(
        name: String,
        authority: String,
        processName: String?
    ) = ResolvedComponent(
        name = name,
        exported = false,
        processName = processName,
        authorities = listOf(authority)
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

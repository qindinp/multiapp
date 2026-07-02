package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualPackageManagerGlobalInstallerTest {

    @Test
    fun `install patches global package manager and cached application package managers`() {
        val bridge = FakeActivityThreadPackageManagerBridge(
            currentPackageManager = Any(),
            applicationPackageManagerPatchResults = listOf(
                ActivityThreadPackageManagerPatchResult(
                    target = "hostContext.packageManager",
                    patched = true
                )
            )
        )
        val installer = VirtualPackageManagerGlobalInstaller(bridge = bridge)

        val result = installer.install(
            hostContext = null,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID
        )

        assertEquals(VirtualPackageManagerGlobalInstallStatus.INSTALLED, result.status)
        assertEquals(1, bridge.createProxyCalls)
        assertEquals(1, bridge.writeCalls)
        val evidence = result.toEvidence().associate { it.key to it.value }
        assertEquals("true", evidence["globalPmsProxyEnabled"])
        assertEquals("true", evidence["sPackageManagerRead"])
        assertEquals("true", evidence["sPackageManagerPatched"])
        assertEquals("1", evidence["applicationPackageManagerPatchedCount"])
        assertEquals(RUNTIME_UID.toString(), evidence["runtimeUid"])
        assertEquals("true", evidence["uidAggregateVirtualizationEnabled"])
        assertEquals("merge-packages-preserve-name", evidence["uidAggregateVirtualizationMode"])
    }

    @Test
    fun `install degrades without failing when global package manager is unavailable`() {
        val bridge = FakeActivityThreadPackageManagerBridge(currentPackageManager = null)
        val installer = VirtualPackageManagerGlobalInstaller(bridge = bridge)

        val result = installer.install(
            hostContext = null,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID
        )

        assertEquals(VirtualPackageManagerGlobalInstallStatus.DEGRADED, result.status)
        assertEquals(0, bridge.createProxyCalls)
        assertEquals(0, bridge.writeCalls)
        assertTrue(result.degradedReasons.any { it.contains("S_PACKAGE_MANAGER_NULL") })
        val evidence = result.toEvidence().associate { it.key to it.value }
        assertEquals("false", evidence["globalPmsProxyEnabled"])
        assertEquals("false", evidence["sPackageManagerRead"])
    }

    @Test
    fun `install reports degraded when cached package manager patch is partial`() {
        val bridge = FakeActivityThreadPackageManagerBridge(
            currentPackageManager = Any(),
            applicationPackageManagerPatchResults = listOf(
                ActivityThreadPackageManagerPatchResult(
                    target = "hostContext.packageManager",
                    patched = true
                ),
                ActivityThreadPackageManagerPatchResult(
                    target = "currentApplication.packageManager",
                    patched = false,
                    skippedReason = "MPM_FIELD_NOT_FOUND"
                )
            )
        )
        val installer = VirtualPackageManagerGlobalInstaller(bridge = bridge)

        val result = installer.install(
            hostContext = null,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID
        )

        assertEquals(VirtualPackageManagerGlobalInstallStatus.DEGRADED, result.status)
        assertTrue(result.degradedReasons.any { it.contains("currentApplication.packageManager:MPM_FIELD_NOT_FOUND") })
        val evidence = result.toEvidence().associate { it.key to it.value }
        assertEquals("1", evidence["applicationPackageManagerPatchedCount"])
        assertTrue(evidence.getValue("degradedReasons").contains("MPM_FIELD_NOT_FOUND"))
    }

    @Test
    fun `install replaces existing virtual proxy with current snapshot proxy`() {
        val bridge = FakeActivityThreadPackageManagerBridge(
            currentPackageManager = ExistingVirtualProxy(),
            applicationPackageManagerPatchResults = emptyList()
        )
        val installer = VirtualPackageManagerGlobalInstaller(bridge = bridge)

        val result = installer.install(
            hostContext = null,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID
        )

        assertEquals(VirtualPackageManagerGlobalInstallStatus.INSTALLED, result.status)
        assertEquals(1, bridge.createProxyCalls)
        assertEquals(1, bridge.writeCalls)
    }

    @Test
    fun `install degrades when cached package manager patch throws`() {
        val bridge = FakeActivityThreadPackageManagerBridge(
            currentPackageManager = Any(),
            shouldThrowOnApplicationPatch = true
        )
        val installer = VirtualPackageManagerGlobalInstaller(bridge = bridge)

        val result = installer.install(
            hostContext = null,
            snapshot = snapshot(),
            runtimeUid = RUNTIME_UID
        )

        assertEquals(VirtualPackageManagerGlobalInstallStatus.DEGRADED, result.status)
        assertTrue(result.degradedReasons.any { it.contains("APPLICATION_PM_PATCH_FAILED") })
    }

    private class FakeActivityThreadPackageManagerBridge(
        private var currentPackageManager: Any?,
        private val applicationPackageManagerPatchResults: List<ActivityThreadPackageManagerPatchResult> = emptyList(),
        private val shouldThrowOnApplicationPatch: Boolean = false
    ) : ActivityThreadPackageManagerBridge {
        val proxy = Any()
        var createProxyCalls = 0
        var writeCalls = 0

        override fun readCurrentPackageManager(): ActivityThreadPackageManagerReadResult =
            ActivityThreadPackageManagerReadResult(
                packageManager = currentPackageManager,
                interfaceName = "fake.IPackageManager",
                packageManagerClassName = currentPackageManager?.javaClass?.name,
                skippedReason = if (currentPackageManager == null) "S_PACKAGE_MANAGER_NULL" else null
            )

        override fun createProxy(
            originalPackageManager: Any,
            snapshot: VirtualPackageSnapshot,
            runtimeUid: Int
        ): Any {
            createProxyCalls += 1
            return proxy
        }

        override fun writePackageManager(proxy: Any): ActivityThreadPackageManagerPatchResult {
            writeCalls += 1
            currentPackageManager = proxy
            return ActivityThreadPackageManagerPatchResult(
                target = "ActivityThread.sPackageManager",
                patched = true
            )
        }

        override fun patchApplicationPackageManagers(
            hostContext: Context?,
            proxy: Any
        ): List<ActivityThreadPackageManagerPatchResult> {
            if (shouldThrowOnApplicationPatch) throw IllegalStateException("patch failed")
            return applicationPackageManagerPatchResults
        }
    }

    private class ExistingVirtualProxy : VirtualPackageManagerProxyMarker {
        override fun virtualPackageManagerOriginal(): Any = Any()
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
        dataDir = "/data/inst"
    )

    private companion object {
        const val RUNTIME_UID = 42420
    }
}

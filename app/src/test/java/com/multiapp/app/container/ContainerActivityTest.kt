package com.multiapp.app.container

import com.multiapp.core.loader.BootstrapEvidence
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.loader.toSummary
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pure JVM tests for [ContainerActivity].
 *
 * Only constant values and companion-object contracts are testable without
 * Robolectric or an instrumented runner. The [ContainerActivity.createIntent]
 * method produces an Android [android.content.Intent] which requires a real
 * or shadowed Android environment -- those tests belong in androidTest.
 */
class ContainerActivityTest {

    @Test
    @DisplayName("EXTRA_INSTANCE_ID constant has correct value")
    fun extraInstanceIdConstant() {
        assertEquals("multiapp.instanceId", ContainerActivity.EXTRA_INSTANCE_ID)
    }

    @Test
    @DisplayName("EXTRA_INSTALL_ORIGIN constant has correct value")
    fun extraInstallOriginConstant() {
        assertEquals("multiapp.installOrigin", ContainerActivity.EXTRA_INSTALL_ORIGIN)
    }

    @Test
    @DisplayName("EXTRA_ENABLE_PROVIDER_HOOK constant has correct value")
    fun extraEnableProviderHookConstant() {
        assertEquals("multiapp.profile.providerHookEnabled", ContainerActivity.EXTRA_ENABLE_PROVIDER_HOOK)
    }

    @Test
    @DisplayName("EXTRA_INSTANCE_ID key matches expected intent contract")
    fun extraInstanceIdKeyMatchesContract() {
        // Ensures the constant string used in Manifest extras matches what the
        // Activity reads in onCreate. If someone renames the key in one place
        // but not the other, this assertion holds them to the contract.
        val expectedKey = "multiapp.instanceId"
        assertEquals(expectedKey, ContainerActivity.EXTRA_INSTANCE_ID)
    }

    @Test
    @DisplayName("EXTRA_INSTALL_ORIGIN key matches expected intent contract")
    fun extraInstallOriginKeyMatchesContract() {
        val expectedKey = "multiapp.installOrigin"
        assertEquals(expectedKey, ContainerActivity.EXTRA_INSTALL_ORIGIN)
    }

    @Test
    @DisplayName("EXTRA_ENABLE_PROVIDER_HOOK key matches expected intent contract")
    fun extraEnableProviderHookKeyMatchesContract() {
        val expectedKey = "multiapp.profile.providerHookEnabled"
        assertEquals(expectedKey, ContainerActivity.EXTRA_ENABLE_PROVIDER_HOOK)
    }

    @Test
    @DisplayName("Container finishes when bootstrap fails")
    fun shouldFinishWhenBootstrapFails() {
        val failedStage = BootstrapResult.failed(
            stage = RuntimeStage.APPLICATION,
            message = "Application stage failed"
        )
        val result = bootstrapResult(
            success = false,
            guestClassLoader = null,
            stageResults = listOf(failedStage)
        )

        assertTrue(ContainerActivity.shouldFinishAfterBootstrap(result))
    }

    @Test
    @DisplayName("Container finishes when bootstrap has no class loader")
    fun shouldFinishWhenGuestClassLoaderMissing() {
        val result = bootstrapResult(
            success = true,
            guestClassLoader = null
        )

        assertTrue(ContainerActivity.shouldFinishAfterBootstrap(result))
    }

    @Test
    @DisplayName("Container continues when bootstrap succeeds with class loader")
    fun shouldContinueWhenBootstrapHasClassLoader() {
        val result = bootstrapResult(
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        assertFalse(ContainerActivity.shouldFinishAfterBootstrap(result))
    }

    @Test
    @DisplayName("Package manager proxy stage evidence is converted to runtime evidence fields")
    fun packageManagerProxyEvidenceFields() {
        val result = BootstrapResult.success(
            stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
            message = "Global PMS proxy installed",
            evidence = listOf(
                BootstrapEvidence("globalPmsProxyEnabled", "true"),
                BootstrapEvidence("sPackageManagerPatched", "true"),
                BootstrapEvidence("uidAggregateVirtualizationEnabled", "true")
            ),
            durationMs = 17L
        )

        val fields = ContainerActivity.packageManagerProxyEvidenceFields(result)

        assertEquals("PACKAGE_MANAGER_PROXY", fields["stage"])
        assertEquals("SUCCESS", fields["status"])
        assertEquals("Global PMS proxy installed", fields["message"])
        assertEquals("17", fields["durationMs"])
        assertEquals("true", fields["globalPmsProxyEnabled"])
        assertEquals("true", fields["sPackageManagerPatched"])
        assertEquals("true", fields["uidAggregateVirtualizationEnabled"])
    }

    @Test
    @DisplayName("Package manager proxy stage evidence lookup ignores unrelated stages")
    fun packageManagerProxyStageLookupIgnoresUnrelatedStages() {
        val result = bootstrapResult(
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            stageResults = listOf(
                BootstrapResult.success(RuntimeStage.RESOURCES),
                BootstrapResult.success(RuntimeStage.APPLICATION)
            )
        )

        assertNull(ContainerActivity.packageManagerProxyStageResult(result))
    }

    @Test
    @DisplayName("Virtual context config carries nativeLibraryDir when lib dir exists")
    fun buildVirtualContextConfigCarriesNativeLibraryDir(@TempDir tempDir: File) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }

        val config = ContainerActivity.buildVirtualContextConfig(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc123",
            originApkPath = File(tempDir, "base.apk").absolutePath,
            dataRoot = dataRoot.absolutePath,
            fallbackDataRoot = File(tempDir, "fallback"),
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        assertEquals(dataRoot.absolutePath, config.dataDir)
        assertEquals(libDir.absolutePath, config.nativeLibraryDir)
    }

    @Test
    @DisplayName("Virtual context config prefers package snapshot nativeLibraryDir")
    fun buildVirtualContextConfigPrefersSnapshotNativeLibraryDir(@TempDir tempDir: File) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        File(dataRoot, "lib").apply { mkdirs() }
        val abiLibDir = File(dataRoot, "lib/arm64-v8a").apply { mkdirs() }
        val apkPath = File(tempDir, "base.apk").absolutePath

        val config = ContainerActivity.buildVirtualContextConfig(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc123",
            originApkPath = apkPath,
            dataRoot = dataRoot.absolutePath,
            fallbackDataRoot = File(tempDir, "fallback"),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            packageSnapshot = VirtualPackageSnapshot(
                instanceId = "inst-001",
                originPackageName = "com.example.app",
                virtualPackageName = "com.multiapp.instance.abc123",
                applicationLabel = "Example",
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 35,
                minSdk = 28,
                sourceDir = apkPath,
                dataDir = dataRoot.absolutePath,
                nativeLibraryDir = abiLibDir.absolutePath
            )
        )

        assertEquals(abiLibDir.absolutePath, config.nativeLibraryDir)
    }

    @Test
    @DisplayName("Virtual context config leaves nativeLibraryDir null when lib dir is absent")
    fun buildVirtualContextConfigAllowsMissingNativeLibraryDir(@TempDir tempDir: File) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }

        val config = ContainerActivity.buildVirtualContextConfig(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc123",
            originApkPath = File(tempDir, "base.apk").absolutePath,
            dataRoot = dataRoot.absolutePath,
            fallbackDataRoot = File(tempDir, "fallback"),
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        assertNull(config.nativeLibraryDir)
    }

    private fun bootstrapResult(
        success: Boolean,
        guestClassLoader: ClassLoader?,
        stageResults: List<BootstrapResult> = emptyList()
    ) = HostedBootstrapResult(
        instanceId = "inst-001",
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/inst-001",
        guestClassLoader = guestClassLoader,
        guestApplication = null,
        installRecord = null,
        launcherActivityClassName = null,
        stageResults = stageResults,
        summary = stageResults.toSummary(),
        success = success,
        diagnostics = null
    )
}

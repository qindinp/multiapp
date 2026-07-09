package com.multiapp.app.container

import com.multiapp.core.loader.BootstrapEvidence
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.engine.EngineBootstrapStageResult
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.loader.toSummary
import com.multiapp.core.model.virtual.ResolvedComponent
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
    @DisplayName("Bootstrap completion chooses finish evidence for failed bootstrap")
    fun bootstrapCompletionActionFinishesFailedBootstrap() {
        val failedStage = BootstrapResult.failed(
            stage = RuntimeStage.APPLICATION,
            message = "Application stage failed"
        )
        val result = bootstrapResult(
            success = false,
            guestClassLoader = null,
            stageResults = listOf(failedStage)
        )

        val action = ContainerActivity.bootstrapCompletionAction(result)

        val finish = action as BootstrapCompletionAction.FinishWithEvidence
        assertEquals("FAIL", finish.status)
        assertEquals("BOOTSTRAP", finish.stage)
        assertEquals("Application stage failed", finish.detail)
    }

    @Test
    @DisplayName("Bootstrap completion chooses proxy launch for successful launcher")
    fun bootstrapCompletionActionLaunchesProxy() {
        val result = bootstrapResult(
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            launcherActivityClassName = "com.example.app.MainActivity",
            packageSnapshot = VirtualPackageSnapshot(
                instanceId = "inst-001",
                originPackageName = "com.example.app",
                virtualPackageName = "com.multiapp.instance.abc123",
                applicationLabel = "Example",
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 35,
                minSdk = 28,
                sourceDir = "/tmp/base.apk",
                dataDir = "/tmp/inst-001",
                activities = listOf(
                    ResolvedComponent(
                        name = "com.example.app.MainActivity",
                        launchMode = "singleTop",
                        taskAffinity = "com.example.app.reader"
                    )
                )
            )
        )

        val action = ContainerActivity.bootstrapCompletionAction(result)

        val launch = action as BootstrapCompletionAction.LaunchProxy
        assertEquals("com.example.app", launch.originPackageName)
        assertEquals("com.example.app.MainActivity", launch.guestActivityClassName)
        assertEquals("singleTop", launch.launchMode)
        assertEquals("com.example.app.reader:inst-001", launch.taskAffinity)
    }

    @Test
    @DisplayName("Launcher task affinity falls back to package affinity")
    fun launcherTaskAffinityFallsBackToPackageAffinity() {
        val snapshot = VirtualPackageSnapshot(
            instanceId = "inst-002",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.def456",
            applicationLabel = "Example",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "/tmp/base.apk",
            dataDir = "/tmp/inst-002",
            taskAffinity = "com.example.custom",
            activities = listOf(ResolvedComponent(name = "com.example.app.MainActivity"))
        )

        val affinity = ContainerActivity.launcherTaskAffinity(
            snapshot,
            "com.example.app.MainActivity"
        )

        assertEquals("com.example.custom:inst-002", affinity)
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

        val fields = ContainerActivity.packageManagerProxyEvidenceFields(
            EngineBootstrapStageResult.fromLoader(result)
        )

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
    @DisplayName("Launcher Activity stage evidence is exposed for hosted launch reports")
    fun launcherActivityStageEvidenceFields() {
        val launcherStage = BootstrapResult.success(
            stage = RuntimeStage.LAUNCHER_ACTIVITY,
            message = "Launcher Activity resolved: com.qq.reader.activity.launch.SplashActivity",
            evidence = listOf(
                BootstrapEvidence("launcherActivityClass", "com.qq.reader.activity.launch.SplashActivity"),
                BootstrapEvidence(
                    "requestedLauncherActivityClass",
                    "com.qq.reader.activity.launch.DefaultAliasSplashActivity"
                ),
                BootstrapEvidence("resolver", "VirtualPackageResolverClassNameFallback"),
                BootstrapEvidence("loadable", "true"),
                BootstrapEvidence(
                    "attemptedLauncherActivities",
                    "VirtualPackageResolver:com.qq.reader.activity.launch.DefaultAliasSplashActivity," +
                        "VirtualPackageResolverClassNameFallback:com.qq.reader.activity.launch.SplashActivity"
                )
            ),
            durationMs = 9L
        )
        val result = bootstrapResult(
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            stageResults = listOf(BootstrapResult.success(RuntimeStage.APPLICATION), launcherStage)
        )

        val fields = ContainerActivity.packageManagerProxyEvidenceFields(
            ContainerActivity.launcherActivityStageResult(result)!!
        )

        assertEquals("LAUNCHER_ACTIVITY", fields["stage"])
        assertEquals("SUCCESS", fields["status"])
        assertEquals("com.qq.reader.activity.launch.SplashActivity", fields["launcherActivityClass"])
        assertEquals(
            "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
            fields["requestedLauncherActivityClass"]
        )
        assertEquals("VirtualPackageResolverClassNameFallback", fields["resolver"])
        assertEquals("true", fields["loadable"])
        assertTrue(fields["attemptedLauncherActivities"].orEmpty().contains("SplashActivity"))
    }

    @Test
    @DisplayName("Launcher failure detail includes resolver evidence")
    fun launcherActivityFailureDetailIncludesResolverEvidence() {
        val launcherStage = BootstrapResult.failed(
            stage = RuntimeStage.LAUNCHER_ACTIVITY,
            message = "Launcher Activity class not loadable: com.example.Alias",
            evidence = listOf(
                BootstrapEvidence("resolver", "InstallRecordFallback"),
                BootstrapEvidence("candidateCount", "2"),
                BootstrapEvidence("candidateLauncherActivities", "InstallRecordFallback:com.example.Alias,InstallRecordFallback:java.lang.String")
            )
        )
        val result = bootstrapResult(
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            stageResults = listOf(launcherStage)
        )

        val detail = ContainerActivity.launcherActivityFailureDetail(result)

        assertTrue(detail.contains("Launcher Activity class not loadable: com.example.Alias"))
        assertTrue(detail.contains("resolver=InstallRecordFallback"))
        assertTrue(detail.contains("candidateCount=2"))
        assertTrue(detail.contains("java.lang.String"))
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
        stageResults: List<BootstrapResult> = emptyList(),
        packageSnapshot: VirtualPackageSnapshot? = null,
        launcherActivityClassName: String? = null
    ) = EngineHostedBootstrapResult.fromLoader(
        HostedBootstrapResult(
            instanceId = "inst-001",
            installId = "com.example.app",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc123",
            originApkPath = "/tmp/base.apk",
            dataRoot = "/tmp/inst-001",
            guestClassLoader = guestClassLoader,
            guestApplication = null,
            installRecord = null,
            packageSnapshot = packageSnapshot,
            launcherActivityClassName = launcherActivityClassName,
            stageResults = stageResults,
            summary = stageResults.toSummary(),
            success = success,
            diagnostics = null
        )
    )
}

package com.multiapp.core.loader

import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.InstallRecordStore
import java.io.File

/**
 * Result of a hosted runtime bootstrap attempt.
 *
 * @property instanceId       The instance ID that was bootstrapped.
 * @property installId        The install record package name (null if not loaded).
 * @property originPackageName The origin app package name (null if instance not found).
 * @property originApkPath    Resolved origin APK path (null if not resolved).
 * @property guestClassLoader ClassLoader for the guest app (null on failure).
 * @property guestApplication Guest Application instance (null in phase 1).
 * @property stageResults     Per-stage results collected during bootstrap.
 * @property summary          Aggregated summary of all stage results.
 * @property success          True if bootstrap completed all stages successfully.
 */
data class HostedBootstrapResult(
    val instanceId: String,
    val installId: String?,
    val originPackageName: String?,
    val originApkPath: String?,
    val guestClassLoader: ClassLoader?,
    val guestApplication: android.app.Application?,
    val stageResults: List<BootstrapResult>,
    val summary: BootstrapSummary,
    val success: Boolean
)

/**
 * Entry point for bootstrapping a virtual app runtime from an instance ID.
 *
 * Loads the [VirtualInstanceRecord][com.multiapp.core.model.instance.VirtualInstanceRecord]
 * and [InstallRecord][com.multiapp.core.model.installer.InstallRecord], resolves the
 * origin APK, creates a guest ClassLoader, and collects per-stage bootstrap results.
 *
 * Phase 1: Up to guest ClassLoader creation. Phase 2 will integrate with
 * [RuntimeBootstrap] orchestrator for full Application lifecycle.
 *
 * @param instanceManager    Provides access to virtual instance records.
 * @param installRecordStore Provides access to install records.
 * @param classLoaderFactory Factory to create a ClassLoader from APK path and native lib dir.
 * @param clock              Wall-clock supplier for duration measurement.
 */
class HostedRuntimeBootstrap(
    private val instanceManager: InstanceManager,
    private val installRecordStore: InstallRecordStore,
    private val classLoaderFactory: (apkPath: String, nativeLibDir: String?) -> ClassLoader = { apk, _ ->
        dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
    },
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Run the hosted bootstrap sequence for the given [instanceId].
     *
     * Returns a [HostedBootstrapResult] with per-stage results and overall status.
     * Stops at the first terminal failure.
     */
    fun run(instanceId: String): HostedBootstrapResult {
        val stageResults = mutableListOf<BootstrapResult>()

        // Stage 1: Load instance record
        val instance = runCatching {
            instanceManager.getInstance(instanceId)
        }.getOrNull()

        if (instance == null) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.CONFIG,
                message = "Instance not found: $instanceId"
            )
            stageResults.add(failedResult)
            return failedHostedResult(instanceId, stageResults)
        }

        stageResults.add(
            BootstrapResult.success(
                stage = RuntimeStage.CONFIG,
                message = "Instance loaded: ${instance.originPackageName}",
                evidence = listOf(
                    BootstrapEvidence("instanceId", instanceId),
                    BootstrapEvidence("originPackageName", instance.originPackageName)
                )
            )
        )

        // Stage 2: Load install record
        val installRecord = runCatching {
            installRecordStore.load(instance.originPackageName)
        }.getOrNull()

        if (installRecord == null) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.PACKAGE_METADATA,
                message = "Install record not found: ${instance.originPackageName}"
            )
            stageResults.add(failedResult)
            return failedHostedResult(instanceId, stageResults, originPackageName = instance.originPackageName)
        }

        stageResults.add(
            BootstrapResult.success(
                stage = RuntimeStage.PACKAGE_METADATA,
                message = "Install record loaded: ${installRecord.packageName}",
                evidence = listOf(
                    BootstrapEvidence("packageName", installRecord.packageName),
                    BootstrapEvidence("versionName", installRecord.versionName)
                )
            )
        )

        // Stage 3: Resolve origin APK
        val originApkPath = installRecord.originApkPath
        val apkExists = runCatching {
            File(originApkPath).exists()
        }.getOrDefault(false)

        if (!apkExists) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.ORIGIN_APK,
                message = "Origin APK not found: $originApkPath"
            )
            stageResults.add(failedResult)
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                installId = installRecord.packageName
            )
        }

        stageResults.add(
            BootstrapResult.success(
                stage = RuntimeStage.ORIGIN_APK,
                message = "Origin APK resolved: $originApkPath",
                evidence = listOf(
                    BootstrapEvidence("originApkPath", originApkPath)
                )
            )
        )

        // Stage 4: Create guest ClassLoader
        val guestClassLoader: ClassLoader
        try {
            guestClassLoader = classLoaderFactory(originApkPath, null)
        } catch (e: Throwable) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.CLASS_LOADER,
                message = "ClassLoader creation failed: ${e.message}",
                error = e
            )
            stageResults.add(failedResult)
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }

        stageResults.add(
            BootstrapResult.success(
                stage = RuntimeStage.CLASS_LOADER,
                message = "Guest ClassLoader created",
                evidence = listOf(
                    BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name)
                )
            )
        )

        // Stage 5: Guest Application (skeleton - not yet creating real Application)
        // TODO: Phase 2 - integrate with RuntimeBootstrap orchestrator
        stageResults.add(
            BootstrapResult.skipped(
                stage = RuntimeStage.APPLICATION,
                message = "Guest Application creation deferred to phase 2"
            )
        )

        val summary = stageResults.toSummary()
        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = installRecord.packageName,
            originPackageName = instance.originPackageName,
            originApkPath = originApkPath,
            guestClassLoader = guestClassLoader,
            guestApplication = null, // TODO: Phase 2
            stageResults = stageResults,
            summary = summary,
            success = true
        )
    }

    private fun failedHostedResult(
        instanceId: String,
        stageResults: List<BootstrapResult>,
        originPackageName: String? = null,
        originApkPath: String? = null,
        installId: String? = null
    ): HostedBootstrapResult {
        val summary = stageResults.toSummary()
        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = installId,
            originPackageName = originPackageName,
            originApkPath = originApkPath,
            guestClassLoader = null,
            guestApplication = null,
            stageResults = stageResults,
            summary = summary,
            success = false
        )
    }
}

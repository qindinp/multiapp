package com.multiapp.core.loader

import android.app.Application
import com.multiapp.core.hook.NativeDiagnosticsConfig
import com.multiapp.core.hook.NativeDiagnosticsEvidence
import com.multiapp.core.hook.NativeDiagnosticsProfile
import com.multiapp.core.hook.NativeDiagnosticsResult
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File

/**
 * Result of a hosted runtime bootstrap attempt.
 *
 * @property instanceId       The instance ID that was bootstrapped.
 * @property installId        The install record package name (null if not loaded).
 * @property originPackageName The origin app package name (null if instance not found).
 * @property originApkPath    Resolved origin APK path (null if not resolved).
 * @property guestClassLoader ClassLoader for the guest app (null on failure).
 * @property guestApplication Guest Application instance (null on failure or if no Application class).
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
    val success: Boolean,
    val diagnostics: NativeDiagnosticsResult? = null
)

/**
 * Entry point for bootstrapping a virtual app runtime from an instance ID.
 *
 * Loads the [VirtualInstanceRecord][com.multiapp.core.model.instance.VirtualInstanceRecord]
 * and [InstallRecord][com.multiapp.core.model.installer.InstallRecord], resolves the
 * origin APK, creates a guest ClassLoader, and (Phase 2) attempts to instantiate
 * the guest [Application] class with a [VirtualContextWrapper].
 *
 * @param instanceManager            Provides access to virtual instance records.
 * @param installRecordStore         Provides access to install records.
 * @param hostContext                Host Android Context (required for Application creation).
 * @param classLoaderFactory         Factory to create a ClassLoader from APK path and native lib dir.
 * @param applicationClassNameResolver Resolves the Application class name from (classLoader, apkPath).
 * @param clock                      Wall-clock supplier for duration measurement.
 */
class HostedRuntimeBootstrap(
    private val instanceManager: InstanceManager,
    private val installRecordStore: InstallRecordStore,
    private val hostContext: android.content.Context? = null,
    private val classLoaderFactory: (apkPath: String, nativeLibDir: String?) -> ClassLoader = { apk, _ ->
        dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
    },
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String? = { cl, path ->
        resolveApplicationClassNameFromManifest(hostContext, path)
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
        val overallStartMs = clock()

        // Stage 1: Load instance record
        val stage1StartMs = clock()
        val instance = runCatching {
            instanceManager.getInstance(instanceId)
        }.getOrNull()
        val stage1DurationMs = clock() - stage1StartMs

        if (instance == null) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.CONFIG,
                message = "Instance not found: $instanceId",
                durationMs = stage1DurationMs
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
                ),
                durationMs = stage1DurationMs
            )
        )

        // Stage 2: Load install record
        val stage2StartMs = clock()
        val installRecord = runCatching {
            installRecordStore.load(instance.originPackageName)
        }.getOrNull()
        val stage2DurationMs = clock() - stage2StartMs

        if (installRecord == null) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.PACKAGE_METADATA,
                message = "Install record not found: ${instance.originPackageName}",
                durationMs = stage2DurationMs
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
                ),
                durationMs = stage2DurationMs
            )
        )

        // Stage 3: Resolve origin APK
        val stage3StartMs = clock()
        val originApkPath = installRecord.originApkPath
        val apkExists = runCatching {
            File(originApkPath).exists()
        }.getOrDefault(false)
        val stage3DurationMs = clock() - stage3StartMs

        if (!apkExists) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.ORIGIN_APK,
                message = "Origin APK not found: $originApkPath",
                durationMs = stage3DurationMs
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
                ),
                durationMs = stage3DurationMs
            )
        )

        // Stage 4: Create guest ClassLoader
        val stage4StartMs = clock()
        val guestClassLoader: ClassLoader
        try {
            guestClassLoader = classLoaderFactory(originApkPath, null)
        } catch (e: Throwable) {
            val failedResult = BootstrapResult.failed(
                stage = RuntimeStage.CLASS_LOADER,
                message = "ClassLoader creation failed: ${e.message}",
                error = e,
                durationMs = clock() - stage4StartMs
            )
            stageResults.add(failedResult)
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val stage4DurationMs = clock() - stage4StartMs

        stageResults.add(
            BootstrapResult.success(
                stage = RuntimeStage.CLASS_LOADER,
                message = "Guest ClassLoader created",
                evidence = listOf(
                    BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name)
                ),
                durationMs = stage4DurationMs
            )
        )

        // Stage 5: Create guest Application
        val stage5StartMs = clock()
        val appClassName = applicationClassNameResolver(guestClassLoader, originApkPath)
        if (appClassName != null) {
            try {
                val appClass = guestClassLoader.loadClass(appClassName)
                val guestApplication = appClass.getDeclaredConstructor().newInstance() as Application

                // Create VirtualContextWrapper for the guest
                val ctx = hostContext
                    ?: throw IllegalStateException("hostContext is required for Application creation")
                val guestContext = VirtualContextWrapper(
                    base = ctx,
                    config = VirtualContextConfig(
                        instanceId = instanceId,
                        originPackageName = instance.originPackageName,
                        virtualPackageName = instance.virtualPackageName,
                        dataDir = instance.dataRoot,
                        sourceDir = originApkPath,
                        nativeLibraryDir = null,
                        classLoader = guestClassLoader
                    ),
                    guestClassLoader = guestClassLoader
                )

                // attachBaseContext (protected method, needs reflection)
                val attachMethod = Application::class.java
                    .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
                attachMethod.isAccessible = true
                attachMethod.invoke(guestApplication, guestContext)

                stageResults.add(
                    BootstrapResult.success(
                        stage = RuntimeStage.APPLICATION,
                        message = "Guest Application created: $appClassName",
                        evidence = listOf(
                            BootstrapEvidence("applicationClass", appClassName),
                            BootstrapEvidence("attached", "true")
                        ),
                        durationMs = clock() - stage5StartMs
                    )
                )

                return HostedBootstrapResult(
                    instanceId = instanceId,
                    installId = installRecord.packageName,
                    originPackageName = instance.originPackageName,
                    originApkPath = originApkPath,
                    guestClassLoader = guestClassLoader,
                    guestApplication = guestApplication,
                    stageResults = stageResults,
                    summary = stageResults.toSummary(),
                    success = true,
                    diagnostics = runDiagnosticsAnalysis(stageResults, originApkPath)
                )
            } catch (e: Throwable) {
                stageResults.add(
                    BootstrapResult.failed(
                        stage = RuntimeStage.APPLICATION,
                        message = "Guest Application creation failed: ${e.message}",
                        error = e,
                        durationMs = clock() - stage5StartMs
                    )
                )
            }
        } else {
            stageResults.add(
                BootstrapResult.skipped(
                    stage = RuntimeStage.APPLICATION,
                    message = "No Application class name resolved"
                )
            )
        }

        val summary = stageResults.toSummary()
        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = installRecord.packageName,
            originPackageName = instance.originPackageName,
            originApkPath = originApkPath,
            guestClassLoader = guestClassLoader,
            guestApplication = null,
            stageResults = stageResults,
            summary = summary,
            success = true,
            diagnostics = runDiagnosticsAnalysis(stageResults, originApkPath)
        )
    }

    /**
     * Resolve the guest Application class name from the APK manifest.
     *
     * Uses the injected [applicationClassNameResolver] to determine the
     * Application class declared in the APK's AndroidManifest.xml.
     *
     * @return the fully-qualified class name, or null if no custom Application is declared.
     */
    internal fun resolveApplicationClassName(classLoader: ClassLoader, apkPath: String?): String? {
        return applicationClassNameResolver(classLoader, apkPath)
    }

    private fun failedHostedResult(
        instanceId: String,
        stageResults: List<BootstrapResult>,
        originPackageName: String? = null,
        originApkPath: String? = null,
        installId: String? = null
    ): HostedBootstrapResult {
        val summary = stageResults.toSummary()
        val diagnostics = runDiagnosticsAnalysis(stageResults, originApkPath)
        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = installId,
            originPackageName = originPackageName,
            originApkPath = originApkPath,
            guestClassLoader = null,
            guestApplication = null,
            stageResults = stageResults,
            summary = summary,
            success = false,
            diagnostics = diagnostics
        )
    }

    /**
     * Build diagnostics evidence from stage results and run NativeDiagnosticsProfile analysis.
     */
    private fun runDiagnosticsAnalysis(
        stageResults: List<BootstrapResult>,
        originApkPath: String?
    ): NativeDiagnosticsResult {
        val evidence = buildDiagnosticsEvidence(stageResults, originApkPath)
        val config = NativeDiagnosticsConfig()
        return NativeDiagnosticsProfile.analyze(config, evidence)
    }

    /**
     * Extract [NativeDiagnosticsEvidence] from bootstrap stage results.
     *
     * Maps stage outcomes to evidence keys understood by [NativeDiagnosticsProfile]:
     * - `classloader_created` -- whether the ClassLoader stage succeeded
     * - `application_created` -- whether the Application stage succeeded
     * - `interface20_error` -- error message if Application failure mentions "interface20"
     * - `origin_apk_path` -- resolved APK path for downstream native analysis
     */
    private fun buildDiagnosticsEvidence(
        stageResults: List<BootstrapResult>,
        originApkPath: String?
    ): List<NativeDiagnosticsEvidence> {
        val evidence = mutableListOf<NativeDiagnosticsEvidence>()

        val classLoaderStage = stageResults.firstOrNull { it.stage == RuntimeStage.CLASS_LOADER }
        if (classLoaderStage != null) {
            evidence.add(
                NativeDiagnosticsEvidence(
                    key = "classloader_created",
                    value = classLoaderStage.isSuccessful.toString(),
                    source = "HostedRuntimeBootstrap"
                )
            )
        }

        val applicationStage = stageResults.firstOrNull { it.stage == RuntimeStage.APPLICATION }
        if (applicationStage != null) {
            evidence.add(
                NativeDiagnosticsEvidence(
                    key = "application_created",
                    value = applicationStage.isSuccessful.toString(),
                    source = "HostedRuntimeBootstrap"
                )
            )
            if (applicationStage.errorMessage?.contains("interface20", ignoreCase = true) == true) {
                evidence.add(
                    NativeDiagnosticsEvidence(
                        key = "interface20_error",
                        value = applicationStage.errorMessage,
                        source = "HostedRuntimeBootstrap"
                    )
                )
            }
        }

        if (originApkPath != null) {
            evidence.add(
                NativeDiagnosticsEvidence(
                    key = "origin_apk_path",
                    value = originApkPath,
                    source = "HostedRuntimeBootstrap"
                )
            )
        }

        return evidence
    }

    companion object {
        /**
         * Resolve Application class name from an APK's manifest using PackageManager.
         *
         * Falls back to null (default Application) on any error.
         */
        private fun resolveApplicationClassNameFromManifest(
            context: android.content.Context?,
            apkPath: String?
        ): String? {
            if (context == null || apkPath == null) return null
            return try {
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(
                    apkPath,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                info?.applicationInfo?.className
            } catch (_: Throwable) {
                null
            }
        }
    }
}

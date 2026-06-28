package com.multiapp.core.loader

import android.app.Application
import com.multiapp.core.hook.NativeDiagnosticsConfig
import com.multiapp.core.hook.NativeDiagnosticsEvidence
import com.multiapp.core.hook.NativeDiagnosticsProfile
import com.multiapp.core.hook.NativeDiagnosticsResult
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualPackageResolver
import java.io.File

/**
 * Result of a hosted runtime bootstrap attempt.
 *
 * @property instanceId       The instance ID that was bootstrapped.
 * @property installId        The install record package name (null if not loaded).
 * @property originPackageName The origin app package name (null if instance not found).
 * @property virtualPackageName The virtual package name for the guest instance.
 * @property originApkPath    Resolved origin APK path (null if not resolved).
 * @property dataRoot         Instance data root directory path.
 * @property guestClassLoader ClassLoader for the guest app (null on failure).
 * @property guestApplication Guest Application instance (null on failure or if no Application class).
 * @property installRecord    The loaded InstallRecord (null if not loaded).
 * @property launcherActivityClassName Resolved launcher Activity class name (null if not resolved).
 * @property stageResults     Per-stage results collected during bootstrap.
 * @property summary          Aggregated summary of all stage results.
 * @property success          True if bootstrap completed all stages successfully.
 */
data class HostedBootstrapResult(
    val instanceId: String,
    val installId: String?,
    val originPackageName: String?,
    val virtualPackageName: String? = null,
    val applicationLabel: String? = null,
    val originApkPath: String?,
    val dataRoot: String? = null,
    val guestClassLoader: ClassLoader?,
    val guestApplication: android.app.Application?,
    val installRecord: InstallRecord? = null,
    val packageSnapshot: VirtualPackageSnapshot? = null,
    val launcherActivityClassName: String? = null,
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
 * @param packageResolver           Resolves APK manifest metadata for hosted runtime launch.
 * @param launcherActivityResolver   Legacy fallback resolver from an InstallRecord.
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
    private val packageResolver: VirtualPackageResolver? = hostContext?.let { ManifestVirtualPackageResolver(it) },
    private val launcherActivityResolver: (InstallRecord) -> String? = { record ->
        resolveLauncherFromActivities(record.activities)
    },
    private val providerHookInstallEnabled: Boolean = false,
    private val providerHookInstaller: VirtualProviderHookInstaller = VirtualProviderHookInstaller(),
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

        val nativeLibraryDir = resolveNativeLibraryDir(instance.dataRoot)
        val resolvedPackage = resolvePackageMetadata(originApkPath)
        val packageSnapshot = VirtualPackageSnapshotFactory.create(
            instance = instance,
            installRecord = installRecord,
            resolvedPackage = resolvedPackage,
            nativeLibraryDir = nativeLibraryDir
        )
        VirtualPackageRegistry.global.register(packageSnapshot)
        val providerRoutingPlan = VirtualProviderRoutingPlanFactory().create(
            snapshot = packageSnapshot,
            hostPackageName = hostContext?.packageName
        )
        val providerHookInstallResult = if (providerHookInstallEnabled) {
            providerHookInstaller.install(providerRoutingPlan)
        } else {
            VirtualProviderHookInstallResult.Skipped(providerRoutingPlan, "PROFILE_DISABLED")
        }

        // Stage 4: Create guest ClassLoader
        val stage4StartMs = clock()
        val guestClassLoader: ClassLoader
        try {
            guestClassLoader = classLoaderFactory(originApkPath, nativeLibraryDir)
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
                    BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name),
                    BootstrapEvidence("nativeLibraryDir", nativeLibraryDir ?: "")
                ) + providerRoutingPlan.toEvidence() + providerHookInstallResult.toEvidence(),
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
                        nativeLibraryDir = nativeLibraryDir,
                        classLoader = guestClassLoader,
                        applicationLabel = packageSnapshot.applicationLabel,
                        packageSnapshot = packageSnapshot
                    ),
                    guestClassLoader = guestClassLoader
                )

                // attachBaseContext (protected method, needs reflection). Resolve from the
                // concrete class first so test Applications can override the framework stub.
                val attachMethod = findAttachBaseContextMethod(guestApplication.javaClass)
                attachMethod.isAccessible = true
                attachMethod.invoke(guestApplication, guestContext)

                // P0-3 fix: call Application.onCreate() — guest lifecycle must be
                // fully initialized; previously only attachBaseContext was called.
                guestApplication.onCreate()

                stageResults.add(
                    BootstrapResult.success(
                        stage = RuntimeStage.APPLICATION,
                        message = "Guest Application created: $appClassName",
                        evidence = listOf(
                            BootstrapEvidence("applicationClass", appClassName),
                            BootstrapEvidence("attached", "true"),
                            BootstrapEvidence("onCreate", "true")
                        ),
                        durationMs = clock() - stage5StartMs
                    )
                )

                // Stage 6: Resolve launcher Activity
                val launcherClassName = resolveLauncherActivityStage(
                    installRecord, guestClassLoader, instanceId,
                    instance, originApkPath, guestApplication, stageResults, resolvedPackage
                )

                return HostedBootstrapResult(
                    instanceId = instanceId,
                    installId = installRecord.packageName,
                    originPackageName = instance.originPackageName,
                    virtualPackageName = instance.virtualPackageName,
                    applicationLabel = resolvedPackage?.applicationLabel,
                    originApkPath = originApkPath,
                    dataRoot = instance.dataRoot,
                    guestClassLoader = guestClassLoader,
                    guestApplication = guestApplication,
                    installRecord = installRecord,
                    packageSnapshot = packageSnapshot,
                    launcherActivityClassName = launcherClassName,
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

        // P0-3 fix: Application stage failure must cause overall failure.
        // Previously this path always returned success=true, silently swallowing failures.
        val hasApplicationFailure = stageResults.any {
            it.stage == RuntimeStage.APPLICATION && it.status == BootstrapStatus.FAILED
        }

        // Stage 6: Resolve launcher Activity (even if Application stage failed/skipped)
        val launcherClassName = resolveLauncherActivityStage(
            installRecord, guestClassLoader, instanceId,
            instance, originApkPath, null, stageResults, resolvedPackage
        )
        val summary = stageResults.toSummary()

        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = installRecord.packageName,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            applicationLabel = resolvedPackage?.applicationLabel,
            originApkPath = originApkPath,
            dataRoot = instance.dataRoot,
            guestClassLoader = guestClassLoader,
            guestApplication = null,
            installRecord = installRecord,
            packageSnapshot = packageSnapshot,
            launcherActivityClassName = launcherClassName,
            stageResults = stageResults,
            summary = summary,
            success = !hasApplicationFailure,
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

    internal fun resolveNativeLibraryDir(dataRoot: String?): String? {
        if (dataRoot.isNullOrBlank()) return null
        val libDir = File(dataRoot, "lib")
        return libDir.takeIf { it.isDirectory }?.absolutePath
    }

    internal fun resolvePackageMetadata(originApkPath: String): ResolvedPackage? = runCatching {
        packageResolver?.resolve(originApkPath)
    }.getOrNull()

    private fun findAttachBaseContextMethod(startClass: Class<*>): java.lang.reflect.Method {
        var clazz: Class<*>? = startClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(
                    "attachBaseContext",
                    android.content.Context::class.java
                )
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException("attachBaseContext(Context) not found in Application hierarchy")
    }

    /**
     * Stage 6: Resolve the launcher Activity class name from the InstallRecord.
     *
     * Adds a LAUNCHER_ACTIVITY stage result and returns the resolved class name (or null).
     * This is non-terminal: failure only adds a FAILED stage but does not abort the run.
     */
    private fun resolveLauncherActivityStage(
        installRecord: InstallRecord,
        guestClassLoader: ClassLoader,
        instanceId: String,
        instance: com.multiapp.core.model.instance.VirtualInstanceRecord,
        originApkPath: String,
        guestApplication: Application?,
        stageResults: MutableList<BootstrapResult>,
        resolvedPackage: ResolvedPackage? = null
    ): String? {
        val stage6StartMs = clock()
        val launcherResolution = resolveLauncherActivity(installRecord, originApkPath, resolvedPackage)
        val launcherClassName = launcherResolution.className

        if (launcherClassName != null) {
            val loadable = runCatching {
                guestClassLoader.loadClass(launcherClassName)
            }.isSuccess

            if (loadable) {
                stageResults.add(
                    BootstrapResult.success(
                        stage = RuntimeStage.LAUNCHER_ACTIVITY,
                        message = "Launcher Activity resolved: $launcherClassName",
                        evidence = listOf(
                            BootstrapEvidence("launcherActivityClass", launcherClassName),
                            BootstrapEvidence("resolver", launcherResolution.source),
                            BootstrapEvidence("loadable", "true")
                        ),
                        durationMs = clock() - stage6StartMs
                    )
                )
            } else {
                stageResults.add(
                    BootstrapResult.failed(
                        stage = RuntimeStage.LAUNCHER_ACTIVITY,
                        message = "Launcher Activity class not loadable: $launcherClassName",
                        evidence = listOf(
                            BootstrapEvidence("launcherActivityClass", launcherClassName),
                            BootstrapEvidence("resolver", launcherResolution.source),
                            BootstrapEvidence("loadable", "false")
                        ),
                        durationMs = clock() - stage6StartMs
                    )
                )
                return null
            }
        } else {
            stageResults.add(
                BootstrapResult.skipped(
                    stage = RuntimeStage.LAUNCHER_ACTIVITY,
                    message = "No launcher Activity resolved from manifest or InstallRecord"
                )
            )
        }
        return launcherClassName
    }

    private fun resolveLauncherActivity(
        installRecord: InstallRecord,
        originApkPath: String,
        resolvedPackage: ResolvedPackage? = null
    ): LauncherResolution {
        val resolvedFromManifest = resolvedPackage?.launcherActivityName ?: runCatching {
            packageResolver?.resolve(originApkPath)?.launcherActivityName
        }.getOrNull()
        if (!resolvedFromManifest.isNullOrBlank()) {
            return LauncherResolution(resolvedFromManifest, "VirtualPackageResolver")
        }

        val resolvedFromInstallRecord = runCatching {
            launcherActivityResolver(installRecord)
        }.getOrNull()
        return LauncherResolution(resolvedFromInstallRecord, "InstallRecord")
    }

    private data class LauncherResolution(
        val className: String?,
        val source: String
    )
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
            installRecord = null,
            launcherActivityClassName = null,
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
        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

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

        /**
         * Resolve launcher Activity from a list of [ComponentInfo].
         *
         * Priority:
         * 1. First activity with MAIN+LAUNCHER intent filters (not supported in ComponentInfo,
         *    falls through to next)
         * 2. First exported activity
         * 3. First activity in the list
         *
         * Note: [ComponentInfo] does not carry intent filter data, so we fall back to
         * exported-then-first heuristic. For full accuracy, use [VirtualPackageResolver].
         */
        internal fun resolveLauncherFromActivities(activities: List<ComponentInfo>): String? {
            if (activities.isEmpty()) return null
            // Prefer exported activities (likely the launcher)
            val exported = activities.firstOrNull { it.exported }
            return exported?.name ?: activities.first().name
        }
    }
}

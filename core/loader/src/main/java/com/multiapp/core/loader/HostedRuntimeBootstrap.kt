package com.multiapp.core.loader

import android.app.Application
import dalvik.system.PathClassLoader
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
    private val classLoaderFactory: (apkPath: String, nativeLibDir: String?) -> ClassLoader =
        ::createDefaultGuestClassLoader,
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String? = { cl, path ->
        resolveApplicationClassNameFromManifest(hostContext, path)
    },
    private val packageResolver: VirtualPackageResolver? = hostContext?.let { ManifestVirtualPackageResolver(it) },
    private val launcherActivityResolver: (InstallRecord) -> String? = { record ->
        resolveLauncherFromActivities(record.activities)
    },
    private val providerHookInstallEnabled: Boolean = false,
    private val providerHookInstaller: VirtualProviderHookInstaller = VirtualProviderHookInstaller(),
    private val packageManagerProxyInstaller: VirtualPackageManagerGlobalInstallAction = VirtualPackageManagerGlobalInstaller(),
    private val runtimeUidProvider: () -> Int = { runCatching { android.os.Process.myUid() }.getOrDefault(0) },
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

        if (instanceId.isBlank()) {
            val configStartMs = clock()
            stageResults.add(
                BootstrapResult.failed(
                    stage = RuntimeStage.CONFIG,
                    message = "Instance not found: $instanceId",
                    durationMs = clock() - configStartMs
                )
            )
            return failedHostedResult(instanceId, stageResults)
        }

        val configOutput = ConfigStage(instanceManager, clock)
            .execute(BootstrapStageInput(instanceId = instanceId))
        stageResults.add(configOutput.result)
        if (configOutput.isTerminalFailure) {
            return failedHostedResult(instanceId, stageResults)
        }

        val installRecordOutput = InstallRecordStage(installRecordStore, clock)
            .execute(configOutput.context)
        stageResults.add(installRecordOutput.result)
        if (installRecordOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId = instanceId,
                stageResults = stageResults,
                originPackageName = configOutput.context.instance?.originPackageName
            )
        }

        val originApkOutput = OriginApkStage(clock = clock)
            .execute(installRecordOutput.context)
        stageResults.add(originApkOutput.result)
        val instance = requireNotNull(originApkOutput.context.instance) {
            "Config stage must provide instance before resolving origin APK"
        }
        val installRecord = requireNotNull(originApkOutput.context.installRecord) {
            "Install record stage must provide install record before resolving origin APK"
        }
        if (originApkOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                installId = installRecord.packageName
            )
        }
        val originApkPath = requireNotNull(originApkOutput.context.originApkPath) {
            "Origin APK stage must provide origin APK path after success"
        }

        val nativeLibrariesOutput = NativeLibrariesStage(
            clock = clock
        ).execute(originApkOutput.context)
        stageResults.add(nativeLibrariesOutput.result)
        if (nativeLibrariesOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val nativeLibraryDir = nativeLibrariesOutput.context.nativeLibraryDir

        val packageSnapshotOutput = PackageSnapshotStage(
            packageMetadataResolver = { apkPath -> resolvePackageMetadata(apkPath) },
            packageRegistry = VirtualPackageRegistry.global,
            clock = clock
        ).execute(nativeLibrariesOutput.context)
        stageResults.add(packageSnapshotOutput.result)
        if (packageSnapshotOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val resolvedPackage = packageSnapshotOutput.context.resolvedPackage
        val packageSnapshot = requireNotNull(packageSnapshotOutput.context.packageSnapshot) {
            "Package snapshot stage must provide package snapshot after success"
        }

        val packageManagerProxyOutput = VirtualPackageManagerProxyStage(
            hostContext = hostContext,
            installer = packageManagerProxyInstaller,
            runtimeUidProvider = runtimeUidProvider,
            clock = clock
        ).execute(packageSnapshotOutput.context)
        stageResults.add(packageManagerProxyOutput.result)
        if (packageManagerProxyOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }

        val providerRoutingOutput = ProviderRoutingStage(
            hostPackageName = hostContext?.packageName,
            providerHookInstallEnabled = providerHookInstallEnabled,
            providerHookInstaller = providerHookInstaller,
            clock = clock
        ).execute(packageManagerProxyOutput.context)
        stageResults.add(providerRoutingOutput.result)
        if (providerRoutingOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val classLoaderOutput = ClassLoaderStage(
            classLoaderFactory = classLoaderFactory,
            clock = clock
        ).execute(
            input = providerRoutingOutput.context,
            additionalEvidence = providerRoutingOutput.result.evidence
        )
        stageResults.add(classLoaderOutput.result)
        if (classLoaderOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val guestClassLoader = requireNotNull(classLoaderOutput.context.guestClassLoader) {
            "ClassLoader stage must provide guest ClassLoader after success"
        }

        val applicationOutput = ApplicationStage(
            hostContext = hostContext,
            applicationClassNameResolver = applicationClassNameResolver,
            clock = clock
        ).execute(classLoaderOutput.context)
        stageResults.add(applicationOutput.result)

        val launcherActivityOutput = LauncherActivityStage(
            packageResolver = packageResolver,
            launcherActivityResolver = launcherActivityResolver,
            clock = clock
        ).execute(applicationOutput.context)
        stageResults.add(launcherActivityOutput.result)

        val hasApplicationFailure = stageResults.any {
            it.stage == RuntimeStage.APPLICATION && it.status == BootstrapStatus.FAILED
        }
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
            guestApplication = applicationOutput.context.guestApplication,
            installRecord = installRecord,
            packageSnapshot = packageSnapshot,
            launcherActivityClassName = launcherActivityOutput.context.launcherActivityClassName,
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
        return NativeLibraryPaths.resolveAndExtract(
            originApkPath = null,
            dataRoot = dataRoot
        ).nativeLibraryDir
    }

    internal fun resolvePackageMetadata(originApkPath: String): ResolvedPackage? = runCatching {
        packageResolver?.resolve(originApkPath)
    }.getOrNull()

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

        internal fun createDefaultGuestClassLoader(
            apkPath: String,
            nativeLibDir: String?
        ): ClassLoader {
            val librarySearchPath = NativeLibraryPaths.buildClassLoaderSearchPath(apkPath, nativeLibDir)
            val classLoader = if (librarySearchPath.isNullOrBlank()) {
                PathClassLoader(apkPath, ClassLoader.getSystemClassLoader())
            } else {
                PathClassLoader(apkPath, librarySearchPath, ClassLoader.getSystemClassLoader())
            }
            initializeSharedLibraryFields(classLoader)
            if (!librarySearchPath.isNullOrBlank()) {
                createClassloaderNamespace(classLoader, apkPath, librarySearchPath)
            }
            return classLoader
        }

        private fun initializeSharedLibraryFields(classLoader: ClassLoader) {
            runCatching {
                val baseDexClassLoaderClass = Class.forName("dalvik.system.BaseDexClassLoader")
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply {
                    isAccessible = true
                }
                val unsafe = theUnsafeField.get(null)
                val objectFieldOffset = unsafeClass.getDeclaredMethod(
                    "objectFieldOffset",
                    java.lang.reflect.Field::class.java
                )
                val getObject = unsafeClass.getDeclaredMethod(
                    "getObject",
                    Any::class.java,
                    Long::class.javaPrimitiveType
                )
                val putObject = unsafeClass.getDeclaredMethod(
                    "putObject",
                    Any::class.java,
                    Long::class.javaPrimitiveType,
                    Any::class.java
                )
                val emptyClassLoaderArray = emptyArray<ClassLoader>()
                listOf("sharedLibraries", "sharedLibrariesLoadedAfterApp").forEach { fieldName ->
                    runCatching {
                        val field = baseDexClassLoaderClass.getDeclaredField(fieldName)
                        val offset = objectFieldOffset.invoke(unsafe, field) as Long
                        if (getObject.invoke(unsafe, classLoader, offset) == null) {
                            putObject.invoke(unsafe, classLoader, offset, emptyClassLoaderArray)
                        }
                    }
                }
            }
        }

        private fun createClassloaderNamespace(
            classLoader: ClassLoader,
            apkPath: String,
            librarySearchPath: String
        ) {
            runCatching {
                val factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val createMethod = factoryClass.getDeclaredMethod(
                    "createClassloaderNamespace",
                    ClassLoader::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java
                ).apply {
                    isAccessible = true
                }
                createMethod.invoke(
                    null,
                    classLoader,
                    android.os.Build.VERSION.SDK_INT,
                    librarySearchPath,
                    "/data:/mnt/expand",
                    false,
                    apkPath,
                    ""
                )
            }
        }

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

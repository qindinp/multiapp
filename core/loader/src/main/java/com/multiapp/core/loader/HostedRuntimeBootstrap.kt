package com.multiapp.core.loader

import android.annotation.SuppressLint
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
import java.io.File
import java.lang.reflect.InvocationTargetException

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
    val processSlot: String? = null,
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
 * Prepared hosted runtime state before guest Application/Activity attachment.
 *
 * [prepare] may run away from the UI thread because it resolves records,
 * extracts native libraries, installs package/provider routing, and creates
 * the guest ClassLoader. [attachAndLaunch] creates the guest Application and
 * resolves the Activity handoff; callers that run it off the UI thread must
 * provide a prepared Looper for guest code that creates default Handlers.
 */
data class HostedBootstrapPreparation(
    val instanceId: String,
    val context: BootstrapStageInput?,
    val stageResults: List<BootstrapResult>,
    val terminalResult: HostedBootstrapResult? = null
) {
    val isTerminal: Boolean
        get() = terminalResult != null
}

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
    private val classLoaderFactory: ((apkPath: String, nativeLibDir: String?) -> ClassLoader)? = null,
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String? = { cl, path ->
        resolveApplicationClassNameFromManifest(hostContext, path)
    },
    private val guestApplicationCreator: GuestApplicationCreator = LoadedApkGuestApplicationCreator(),
    private val packageResolver: VirtualPackageResolver? = hostContext?.let { ManifestVirtualPackageResolver(it) },
    private val launcherActivityResolver: (InstallRecord) -> String? = { record ->
        resolveLauncherFromActivities(record.activities)
    },
    private val providerHookInstallEnabled: Boolean = false,
    private val packerRuntimeEnabled: Boolean = false,
    private val providerHookInstaller: VirtualProviderHookInstaller = VirtualProviderHookInstaller(),
    private val packageManagerProxyInstaller: VirtualPackageManagerGlobalInstallAction = VirtualPackageManagerGlobalInstaller(),
    private val runtimeUidProvider: () -> Int = {
        RuntimeUidCompat.resolve(
            runCatching { hostContext?.applicationInfo?.uid }.getOrNull()
        )
    },
    private val applicationThreadRunner: ApplicationThreadRunner = DirectApplicationThreadRunner,
    private val applicationOnCreateInvoker: (Application) -> Unit = Application::onCreate,
    private val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    private val runtimePublisher: (String, HostedBootstrapResult) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val effectiveGuestProcessName: String? = null
) {

    /**
     * Run the hosted bootstrap sequence for the given [instanceId].
     *
     * Returns a [HostedBootstrapResult] with per-stage results and overall status.
     * Stops at the first terminal failure.
     */
    fun run(instanceId: String, processSlot: String? = null): HostedBootstrapResult =
        attachAndLaunch(prepare(instanceId, processSlot))

    /**
     * Prepare the hosted runtime up to and including ClassLoader creation.
     *
     * This is the heavy half of bootstrap and is safe to run off the UI thread
     * only when the caller accepts that PMS/provider routing is also prepared
     * there. Foreground Activity launches should prefer doing the whole bind in
     * a prewarm thread, then consume the cached result on the UI thread.
     * It intentionally does not create the guest Application.
     */
    fun prepare(instanceId: String, processSlot: String? = null): HostedBootstrapPreparation =
        createClassLoader(prepareBeforeClassLoader(instanceId, processSlot))

    /**
     * Prepare all pre-ClassLoader state. Callers may keep this on the main
     * thread for conservative diagnostics, or move it to a prewarm thread when
     * foreground launch responsiveness is the priority.
     */
    fun prepareBeforeClassLoader(instanceId: String, processSlot: String? = null): HostedBootstrapPreparation {
        val stageResults = mutableListOf<BootstrapResult>()

        if (instanceId.isBlank()) {
            val configStartMs = clock()
            stageResults.add(
                BootstrapResult.failed(
                    stage = RuntimeStage.CONFIG,
                    message = "Instance not found: $instanceId",
                    durationMs = clock() - configStartMs
                )
            )
            return terminalPreparation(
                instanceId = instanceId,
                context = null,
                stageResults = stageResults,
                result = failedHostedResult(instanceId, stageResults)
            )
        }

        val configOutput = ConfigStage(instanceManager, clock)
            .execute(BootstrapStageInput(instanceId = instanceId, processSlot = processSlot))
        stageResults.add(configOutput.result)
        if (configOutput.isTerminalFailure) {
            return terminalPreparation(
                instanceId = instanceId,
                context = configOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(instanceId, stageResults)
            )
        }

        val installRecordOutput = InstallRecordStage(installRecordStore, clock)
            .execute(configOutput.context)
        stageResults.add(installRecordOutput.result)
        if (installRecordOutput.isTerminalFailure) {
            return terminalPreparation(
                instanceId = instanceId,
                context = installRecordOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    instanceId = instanceId,
                    stageResults = stageResults,
                    originPackageName = configOutput.context.instance?.originPackageName
                )
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
            return terminalPreparation(
                instanceId = instanceId,
                context = originApkOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    instanceId, stageResults,
                    originPackageName = instance.originPackageName,
                    installId = installRecord.packageName
                )
            )
        }
        val originApkPath = requireNotNull(originApkOutput.context.originApkPath) {
            "Origin APK stage must provide origin APK path after success"
        }

        val nativeLibrariesOutput = NativeLibrariesStage(
            hostContext = hostContext,
            clock = clock
        ).execute(originApkOutput.context)
        stageResults.add(nativeLibrariesOutput.result)
        if (nativeLibrariesOutput.isTerminalFailure) {
            return terminalPreparation(
                instanceId = instanceId,
                context = nativeLibrariesOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    instanceId, stageResults,
                    originPackageName = instance.originPackageName,
                    originApkPath = originApkPath,
                    installId = installRecord.packageName
                )
            )
        }

        val packageSnapshotOutput = PackageSnapshotStage(
            packageMetadataResolver = { apkPath -> resolvePackageMetadata(apkPath) },
            packageRegistry = VirtualPackageRegistry.global,
            clock = clock
        ).execute(nativeLibrariesOutput.context)
        stageResults.add(packageSnapshotOutput.result)
        if (packageSnapshotOutput.isTerminalFailure) {
            return terminalPreparation(
                instanceId = instanceId,
                context = packageSnapshotOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    instanceId, stageResults,
                    originPackageName = instance.originPackageName,
                    originApkPath = originApkPath,
                    installId = installRecord.packageName
                )
            )
        }

        val packageManagerProxyOutput = VirtualPackageManagerProxyStage(
            hostContext = hostContext,
            installer = packageManagerProxyInstaller,
            runtimeUidProvider = runtimeUidProvider,
            clock = clock
        ).execute(packageSnapshotOutput.context)
        stageResults.add(packageManagerProxyOutput.result)
        if (packageManagerProxyOutput.isTerminalFailure) {
            return terminalPreparation(
                instanceId = instanceId,
                context = packageManagerProxyOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    instanceId, stageResults,
                    originPackageName = instance.originPackageName,
                    originApkPath = originApkPath,
                    installId = installRecord.packageName
                )
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
            return terminalPreparation(
                instanceId = instanceId,
                context = providerRoutingOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    instanceId, stageResults,
                    originPackageName = instance.originPackageName,
                    originApkPath = originApkPath,
                    installId = installRecord.packageName
                )
            )
        }
        return HostedBootstrapPreparation(
            instanceId = instanceId,
            context = providerRoutingOutput.context,
            stageResults = stageResults.toList()
        )
    }

    /**
     * Create the guest ClassLoader for a prepared runtime. This is the only
     * bootstrap segment ContainerActivity runs on its background thread.
     */
    fun createClassLoader(preparation: HostedBootstrapPreparation): HostedBootstrapPreparation {
        preparation.terminalResult?.let { return preparation }
        val preparedContext = requireNotNull(preparation.context) {
            "Prepared bootstrap context is required before ClassLoader creation"
        }
        val stageResults = preparation.stageResults.toMutableList()
        val instance = requireNotNull(preparedContext.instance) {
            "Prepared bootstrap must include instance before ClassLoader creation"
        }
        val installRecord = requireNotNull(preparedContext.installRecord) {
            "Prepared bootstrap must include install record before ClassLoader creation"
        }
        val originApkPath = requireNotNull(preparedContext.originApkPath) {
            "Prepared bootstrap must include origin APK path before ClassLoader creation"
        }
        val providerRoutingEvidence = stageResults
            .lastOrNull { it.stage == RuntimeStage.GUEST_CONTEXT }
            ?.evidence
            .orEmpty()
        val classLoaderStage = classLoaderFactory?.let { legacyFactory ->
            ClassLoaderStage(classLoaderFactory = legacyFactory, clock = clock)
        } ?: ClassLoaderStage(
            structuredClassLoaderFactory = GuestClassLoaderFactory(::createDefaultGuestClassLoader),
            clock = clock
        )
        val classLoaderOutput = classLoaderStage.execute(
            input = preparedContext,
            additionalEvidence = providerRoutingEvidence
        )
        stageResults.add(classLoaderOutput.result)
        if (classLoaderOutput.isTerminalFailure) {
            return terminalPreparation(
                instanceId = preparation.instanceId,
                context = classLoaderOutput.context,
                stageResults = stageResults,
                result = failedHostedResult(
                    preparation.instanceId, stageResults,
                    originPackageName = instance.originPackageName,
                    originApkPath = originApkPath,
                    installId = installRecord.packageName
                )
            )
        }

        return HostedBootstrapPreparation(
            instanceId = preparation.instanceId,
            context = classLoaderOutput.context,
            stageResults = stageResults.toList()
        )
    }

    /**
     * Complete prepared bootstrap by creating the guest Application and
     * resolving launcher Activity on the caller thread.
     */
    fun attachAndLaunch(preparation: HostedBootstrapPreparation): HostedBootstrapResult {
        preparation.terminalResult?.let { return it }
        val preparedContext = requireNotNull(preparation.context) {
            "Prepared bootstrap context is required when there is no terminal result"
        }
        val stageResults = preparation.stageResults.toMutableList()
        val instance = requireNotNull(preparedContext.instance) {
            "Prepared bootstrap must include instance"
        }
        val installRecord = requireNotNull(preparedContext.installRecord) {
            "Prepared bootstrap must include install record"
        }
        val originApkPath = requireNotNull(preparedContext.originApkPath) {
            "Prepared bootstrap must include origin APK path"
        }
        val guestClassLoader = requireNotNull(preparedContext.guestClassLoader) {
            "Prepared bootstrap must include guest ClassLoader"
        }
        val resolvedPackage = preparedContext.resolvedPackage
        val packageSnapshot = requireNotNull(preparedContext.packageSnapshot) {
            "Prepared bootstrap must include package snapshot"
        }

        val packerOutput = PackerRuntimeStage(
            packerEnabled = packerRuntimeEnabled,
            hostContextProvider = { hostContext }
        ).execute(preparedContext)
        stageResults.add(packerOutput.result)

        val applicationInput = packerOutput.context
        val applicationOutput = ApplicationStage(
            hostContext = hostContext,
            applicationClassNameResolver = applicationClassNameResolver,
            guestApplicationCreator = guestApplicationCreator,
            applicationThreadRunner = applicationThreadRunner,
            applicationOnCreateInvoker = applicationOnCreateInvoker,
            processRuntime = processRuntime,
            activityRecordManager = activityRecordManager,
            runtimePublisher = runtimePublisher,
            clock = clock,
            effectiveGuestProcessName = effectiveGuestProcessName
        ).execute(applicationInput)
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
            instanceId = preparation.instanceId,
            installId = installRecord.packageName,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            applicationLabel = resolvedPackage?.applicationLabel,
            processSlot = preparedContext.processSlot,
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
        installId: String? = null,
        processSlot: String? = null
    ): HostedBootstrapResult {
        val summary = stageResults.toSummary()
        val diagnostics = runDiagnosticsAnalysis(stageResults, originApkPath)
        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = installId,
            originPackageName = originPackageName,
            processSlot = processSlot,
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

    private fun terminalPreparation(
        instanceId: String,
        context: BootstrapStageInput?,
        stageResults: List<BootstrapResult>,
        result: HostedBootstrapResult
    ): HostedBootstrapPreparation = HostedBootstrapPreparation(
        instanceId = instanceId,
        context = context,
        stageResults = stageResults.toList(),
        terminalResult = result
    )

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

        val packerStage = stageResults.firstOrNull { it.stage == RuntimeStage.PACKER_RUNTIME }
        if (packerStage != null) {
            val packerEvidence = packerStage.evidence.associate { it.key to it.value }
            evidence.add(
                NativeDiagnosticsEvidence(
                    key = "packer_stage_status",
                    value = packerStage.status.name,
                    source = "HostedRuntimeBootstrap"
                )
            )
            packerEvidence["packerName"]?.let {
                evidence.add(NativeDiagnosticsEvidence("packer_name", it, "HostedRuntimeBootstrap"))
            }
            packerEvidence["packerSkipReason"]?.let {
                evidence.add(NativeDiagnosticsEvidence("packer_skip_reason", it, "HostedRuntimeBootstrap"))
            }
            packerEvidence["jiaguLoaded"]?.let {
                evidence.add(NativeDiagnosticsEvidence("packer_jiagu_loaded", it, "HostedRuntimeBootstrap"))
            }
            packerEvidence["stubNativesVerified"]?.let {
                evidence.add(NativeDiagnosticsEvidence("packer_stub_verified", it, "HostedRuntimeBootstrap"))
            }
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
            spec: GuestClassLoaderSpec
        ): GuestClassLoaderCreation {
            val created = if (isAndroidRuntime()) {
                createWithPlatformClassLoaderFactory(spec)
                    ?: createWithVerifiedNamespaceFallback(spec)
            } else {
                createJvmTestClassLoader(spec)
            }
            return created
        }

        private fun isAndroidRuntime(): Boolean {
            val vmName = System.getProperty("java.vm.name").orEmpty()
            return vmName.contains("Dalvik", ignoreCase = true) ||
                vmName.contains("ART", ignoreCase = true)
        }

        private fun createJvmTestClassLoader(spec: GuestClassLoaderSpec): GuestClassLoaderCreation {
            val classLoader = if (spec.librarySearchPath.isNullOrBlank()) {
                PathClassLoader(spec.dexPath, ClassLoader.getSystemClassLoader())
            } else {
                PathClassLoader(
                    spec.dexPath,
                    spec.librarySearchPath,
                    ClassLoader.getSystemClassLoader()
                )
            }
            initializeSharedLibraryFields(classLoader)
            return GuestClassLoaderCreation(
                classLoader = classLoader,
                namespaceVerdict = GuestClassLoaderNamespaceVerdict.NOT_APPLICABLE,
                creationMethod = "JVM_TEST_FACTORY",
                namespaceDetail = "linker namespace is unavailable outside Dalvik/ART"
            )
        }

        private fun createWithPlatformClassLoaderFactory(
            spec: GuestClassLoaderSpec
        ): GuestClassLoaderCreation? {
            val factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val candidates = factoryClass.declaredMethods
                .filter { method ->
                    method.name == "createClassLoader" &&
                        method.parameterTypes.take(7).toTypedArray().contentEquals(
                            arrayOf(
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                ClassLoader::class.java,
                                Int::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                                String::class.java
                            )
                        ) &&
                        method.parameterCount in 8..10
                }
                .sortedByDescending { it.parameterCount }
            val method = candidates.firstOrNull() ?: return null
            val parentClassLoader = platformGuestClassLoaderParent()
            val args = platformClassLoaderFactoryArguments(
                spec = spec,
                parentClassLoader = parentClassLoader,
                parameterCount = method.parameterCount
            )
            val classLoader = invokeReflective(method) {
                method.isAccessible = true
                method.invoke(null, *args) as ClassLoader
            }
            return GuestClassLoaderCreation(
                classLoader = classLoader,
                namespaceVerdict = GuestClassLoaderNamespaceVerdict.PASS,
                creationMethod = "AOSP_CLASS_LOADER_FACTORY_${method.parameterCount}",
                namespaceDetail = "platform factory returned a ClassLoader with an initialized namespace"
            )
        }

        private fun createWithVerifiedNamespaceFallback(
            spec: GuestClassLoaderSpec
        ): GuestClassLoaderCreation {
            val parentClassLoader = platformGuestClassLoaderParent()
            val classLoader = if (spec.librarySearchPath.isNullOrBlank()) {
                PathClassLoader(spec.dexPath, parentClassLoader)
            } else {
                PathClassLoader(
                    spec.dexPath,
                    spec.librarySearchPath,
                    parentClassLoader
                )
            }
            initializeSharedLibraryFields(classLoader)
            createClassloaderNamespace(classLoader, spec)
            return GuestClassLoaderCreation(
                classLoader = classLoader,
                namespaceVerdict = GuestClassLoaderNamespaceVerdict.PASS,
                creationMethod = "VERIFIED_NAMESPACE_FALLBACK",
                namespaceDetail = "private namespace API returned no error"
            )
        }

        internal fun platformGuestClassLoaderParent(): ClassLoader =
            ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()

        internal fun platformClassLoaderFactoryArguments(
            spec: GuestClassLoaderSpec,
            parentClassLoader: ClassLoader,
            parameterCount: Int
        ): Array<Any?> {
            require(parameterCount in 8..10) {
                "Unsupported ClassLoaderFactory parameter count: $parameterCount"
            }
            return buildList<Any?> {
                add(spec.dexPath)
                add(spec.librarySearchPath)
                add(spec.libraryPermittedPath)
                add(parentClassLoader)
                add(spec.targetSdkVersion)
                add(false)
                add(null)
                add(emptyList<ClassLoader>())
                if (parameterCount >= 9) add(emptyList<String>())
                if (parameterCount >= 10) add(emptyList<ClassLoader>())
            }.toTypedArray()
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

        // API 37 may block this hidden method. Failure is surfaced by the
        // bootstrap stage and cannot be promoted to a supported capability.
        @SuppressLint("SoonBlockedPrivateApi")
        private fun createClassloaderNamespace(
            classLoader: ClassLoader,
            spec: GuestClassLoaderSpec
        ) {
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
            val errorMessage = invokeReflective(createMethod) {
                createMethod.invoke(
                    null,
                    classLoader,
                    spec.targetSdkVersion,
                    spec.librarySearchPath,
                    spec.libraryPermittedPath,
                    false,
                    spec.dexPath,
                    ""
                ) as? String
            }
            if (errorMessage != null) {
                throw UnsatisfiedLinkError(
                    "Unable to create namespace for guest ClassLoader: $errorMessage"
                )
            }
        }

        private inline fun <T> invokeReflective(
            method: java.lang.reflect.Method,
            block: () -> T
        ): T = try {
            block()
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Guest ClassLoader reflection failed at ${method.declaringClass.name}.${method.name}",
                error
            )
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

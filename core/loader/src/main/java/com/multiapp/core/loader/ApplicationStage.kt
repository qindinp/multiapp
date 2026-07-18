package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import android.os.Looper
import com.multiapp.core.model.virtual.VirtualContextConfig

class ApplicationStage(
    private val hostContext: Context?,
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String?,
    private val guestApplicationCreator: GuestApplicationCreator = LoadedApkGuestApplicationCreator(),
    private val providerPreinstaller: GuestProviderPreinstaller = GuestProviderPreinstaller(),
    private val applicationThreadRunner: ApplicationThreadRunner = DirectApplicationThreadRunner,
    private val applicationOnCreateInvoker: (Application) -> Unit = Application::onCreate,
    private val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    private val runtimePublisher: (String, HostedBootstrapResult) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val effectiveGuestProcessName: String? = null,
    private val finalApplicationBindingInspector: (
        application: Application,
        expectedLoadedApk: Any
    ) -> LoadedApkApplicationContextBinding = LoadedApkBridge::inspectApplicationContextBinding,
    private val loadedApkClassLoaderInspector: (Any) -> ClassLoader? = LoadedApkBridge::classLoader,
    private val applicationClassLoaderInspector: (Application) -> ClassLoader? = { application ->
        application.javaClass.classLoader
    },
    private val applicationContextClassLoaderInspector: (Application) -> ClassLoader? = { application ->
        runCatching { application.classLoader }.getOrNull()
    },
    private val frameworkContextClassLoaderReplacementInspector: (
        candidate: ClassLoader?,
        guestClassLoader: ClassLoader
    ) -> Boolean = ::isAospWarningContextClassLoader
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val guestClassLoader = input.guestClassLoader ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Guest ClassLoader is required before Application creation"
        )
        val originApkPath = input.originApkPath ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before Application creation"
        )
        val instance = input.instance ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Instance is required before Application creation"
        )
        val packageSnapshot = input.packageSnapshot ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Package snapshot is required before Application creation"
        )
        val currentGuestProcessName = resolveGuestProcessName(
            requestedProcessName = effectiveGuestProcessName,
            applicationProcessName = packageSnapshot.processName,
            originPackageName = packageSnapshot.originPackageName
        )

        val resolvedAppClassName = applicationClassNameResolver(guestClassLoader, originApkPath)
        val appClassName = resolvedAppClassName ?: Application::class.java.name
        val appClassSource = if (resolvedAppClassName == null) "DEFAULT_APPLICATION" else "MANIFEST"
        if (hostContext == null && resolvedAppClassName == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.APPLICATION,
                    message = "Default Application creation skipped because hostContext is missing",
                    evidence = listOf(
                        BootstrapEvidence("applicationClass", appClassName),
                        BootstrapEvidence("applicationClassSource", appClassSource),
                        BootstrapEvidence("applicationCreator", "SKIPPED"),
                        BootstrapEvidence("applicationCreatorSkippedReason", "HOST_CONTEXT_MISSING")
                    )
                ).copy(durationMs = clock() - startMs),
                terminalFailure = false
            )
        }

        var applicationThread: ApplicationThreadEvidence? = null
        var creationForRollback: GuestApplicationCreateResult? = null
        var runtimePublishedBeforeOnCreate = false
        var providerPreinstallEvidence = emptyList<BootstrapEvidence>()
        var applicationClassLoaderEvidence = emptyList<BootstrapEvidence>()
        fun progress(status: String, detail: String, extra: Map<String, String> = emptyMap()) {
            HostedRuntimeProgressEvidenceWriter.write(
                context = hostContext,
                instanceId = input.instanceId,
                component = "application-progress",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to "APPLICATION",
                    "detail" to detail,
                    "applicationClass" to appClassName,
                    "applicationClassSource" to appClassSource,
                    "originPackageName" to instance.originPackageName,
                    "virtualPackageName" to instance.virtualPackageName,
                    "effectiveGuestProcessName" to currentGuestProcessName,
                    "processSlot" to input.processSlot.orEmpty(),
                    "threadContextClassLoaderIdentity" to
                        System.identityHashCode(Thread.currentThread().contextClassLoader).toString(),
                    "guestClassLoaderIdentity" to System.identityHashCode(guestClassLoader).toString(),
                    "threadName" to Thread.currentThread().name,
                    "elapsedMs" to (System.currentTimeMillis() - startMs).toString()
                ) + applicationClassLoaderEvidence.associate { it.key to it.value } + extra
            )
        }

        return runCatching {
            applicationThreadRunner.run {
                val currentThread = Thread.currentThread()
                val previousContextClassLoader = currentThread.contextClassLoader
                var guestContextClassLoaderCommitted = false
                try {
                    currentThread.contextClassLoader = guestClassLoader
                    check(currentThread.contextClassLoader === guestClassLoader) {
                        "Guest Application thread context ClassLoader mismatch"
                    }
                    applicationThread = currentApplicationThreadEvidence()
                    val context = hostContext
                        ?: throw IllegalStateException("hostContext is required for Application creation")
                    progress("STARTED", "guest Application creation started")
                    val virtualContextConfig = VirtualContextConfig(
                        instanceId = input.instanceId,
                        originPackageName = instance.originPackageName,
                        virtualPackageName = instance.virtualPackageName,
                        dataDir = instance.dataRoot,
                        sourceDir = originApkPath,
                        nativeLibraryDir = input.nativeLibraryDir,
                        classLoader = guestClassLoader,
                        applicationLabel = packageSnapshot.applicationLabel,
                        packageSnapshot = packageSnapshot,
                        splitSourceDirs = packageSnapshot.splitSourceDirs,
                        splitPublicSourceDirs = packageSnapshot.splitPublicSourceDirs,
                        splitNames = packageSnapshot.splitNames,
                        isolatedSplits = packageSnapshot.isolatedSplits,
                        processSlot = input.processSlot,
                        effectiveGuestProcessName = currentGuestProcessName
                    )
                    val creation = guestApplicationCreator.create(
                        GuestApplicationCreateRequest(
                            applicationClassName = appClassName,
                            applicationClassSource = appClassSource,
                            hostContext = context,
                            virtualContextConfig = virtualContextConfig,
                            guestClassLoader = guestClassLoader,
                            processRuntime = processRuntime,
                            activityRecordManager = activityRecordManager,
                            progress = { status, detail, extra -> progress(status, detail, extra) }
                        )
                    )
                    creationForRollback = creation
                    normalizeThreadContextClassLoaderAfterApplicationAttach(
                        currentThread = currentThread,
                        creation = creation,
                        guestClassLoader = guestClassLoader,
                        requestedApplicationClassName = appClassName,
                        progress = ::progress,
                        evidenceSink = { applicationClassLoaderEvidence = it }
                    )
                    progress(
                        "APPLICATION_ATTACHED",
                        "guest Application attachBaseContext returned",
                        mapOf("attachedContextPackageName" to creation.attachedContextPackageName.orEmpty())
                    )
                    publishRuntimeBeforeOnCreate(input, creation.application)
                    runtimePublishedBeforeOnCreate = true
                    progress("RUNTIME_PUBLISHED", "runtime published before Application.onCreate")
                    verifyClassLoaderOwnershipAtCheckpoint(
                        currentThread = currentThread,
                        creation = creation,
                        guestClassLoader = guestClassLoader,
                        requestedApplicationClassName = appClassName,
                        evidencePrefix = "providerPreinstall",
                        checkpointLabel = "before Provider preinstall",
                        progress = ::progress,
                        evidenceSink = { evidence ->
                            applicationClassLoaderEvidence = applicationClassLoaderEvidence + evidence
                        }
                    )
                    val providerPreinstallResult = providerPreinstaller.preinstall(
                        GuestProviderPreinstallRequest(
                            hostPackageName = runCatching { context.packageName }.getOrNull().orEmpty(),
                            snapshot = packageSnapshot,
                            application = creation.application,
                            guestClassLoader = guestClassLoader,
                            config = virtualContextConfig
                        )
                    )
                    providerPreinstallEvidence = providerPreinstallResult.toEvidence()
                    verifyClassLoaderOwnershipAtCheckpoint(
                        currentThread = currentThread,
                        creation = creation,
                        guestClassLoader = guestClassLoader,
                        requestedApplicationClassName = appClassName,
                        evidencePrefix = "providerPostinstall",
                        checkpointLabel = "after Provider preinstall",
                        progress = ::progress,
                        evidenceSink = { evidence ->
                            applicationClassLoaderEvidence = applicationClassLoaderEvidence + evidence
                        }
                    )
                    progress(
                        "PROVIDER_PREINSTALL_FINISHED",
                        "current guest process provider preinstall finished before Application.onCreate",
                        providerPreinstallEvidence.associate { it.key to it.value }
                    )
                    progress("ON_CREATE_STARTED", "guest Application.onCreate started")
                    applicationOnCreateInvoker(creation.application)
                    progress("ON_CREATE_FINISHED", "guest Application.onCreate returned")
                    finalizeApplicationAfterOnCreate(creation).also { finalized ->
                        verifyClassLoaderOwnershipAtCheckpoint(
                            currentThread = currentThread,
                            creation = finalized,
                            guestClassLoader = guestClassLoader,
                            requestedApplicationClassName = appClassName,
                            evidencePrefix = "applicationOnCreate",
                            checkpointLabel = "after Application.onCreate",
                            progress = ::progress,
                            evidenceSink = { evidence ->
                                applicationClassLoaderEvidence = applicationClassLoaderEvidence + evidence
                            }
                        )
                        progress(
                            "APPLICATION_FINALIZED",
                            "resolved final guest Application from LoadedApk",
                            finalized.evidence.takeLastWhile {
                                it.key.startsWith("loadedApkFinalApplication")
                            }.associate { it.key to it.value }
                        )
                        guestContextClassLoaderCommitted = true
                    }
                } finally {
                    if (!guestContextClassLoaderCommitted) {
                        currentThread.contextClassLoader = previousContextClassLoader
                    }
                }
            }
        }.fold(
            onSuccess = { creation ->
                BootstrapStageOutput(
                    context = input.copy(guestApplication = creation.application),
                    result = BootstrapResult.success(
                        stage = RuntimeStage.APPLICATION,
                        message = "Guest Application created: $appClassName",
                        evidence = listOf(
                            BootstrapEvidence("applicationClass", appClassName),
                            BootstrapEvidence("applicationClassSource", appClassSource),
                            BootstrapEvidence("applicationThreadName", applicationThread?.name.orEmpty()),
                            BootstrapEvidence("applicationThreadHasLooper", applicationThread?.hasLooper.toString()),
                            BootstrapEvidence("applicationThreadIsMain", applicationThread?.isMain.toString()),
                            BootstrapEvidence(
                                "applicationThreadLooperProbeSkippedReason",
                                applicationThread?.looperProbeSkippedReason.orEmpty()
                            ),
                            BootstrapEvidence("attached", "true"),
                            BootstrapEvidence("onCreate", "true"),
                            BootstrapEvidence(
                                "runtimePublishedBeforeOnCreate",
                                runtimePublishedBeforeOnCreate.toString()
                            ),
                            BootstrapEvidence("contextPackageName", creation.attachedContextPackageName.orEmpty()),
                            BootstrapEvidence("originPackageName", instance.originPackageName),
                            BootstrapEvidence("virtualPackageName", instance.virtualPackageName),
                            BootstrapEvidence("effectiveGuestProcessName", currentGuestProcessName),
                            BootstrapEvidence("processSlot", input.processSlot.orEmpty())
                        ) + creation.evidence + applicationClassLoaderEvidence + providerPreinstallEvidence,
                        durationMs = clock() - startMs
                    ),
                    terminalFailure = false
                )
            },
            onFailure = { error ->
                val rollbackResult = creationForRollback?.rollbackHandle?.rollback()
                    ?: (error as? LoadedApkApplicationCreationException)?.rollbackResult
                val creationEvidence = creationForRollback?.evidence.orEmpty()
                val creatorAttemptStatus = creationEvidence.lastOrNull {
                    it.key == "loadedApkApplicationCreatorStatus"
                }?.value
                val failureEvidence = creationEvidence.filterNot {
                    it.key == "loadedApkApplicationCreatorStatus"
                } + applicationClassLoaderEvidence + listOfNotNull(
                    creatorAttemptStatus?.let {
                        BootstrapEvidence("loadedApkApplicationCreatorAttemptStatus", it)
                    },
                    BootstrapEvidence("loadedApkApplicationCreatorStatus", "FAIL"),
                    BootstrapEvidence("reflectiveApplicationFallbackEnabled", "false"),
                    BootstrapEvidence("applicationThreadContextClassLoaderRollback", "PASS"),
                    rollbackResult?.let {
                        BootstrapEvidence(
                            "applicationRuntimeRollbackStatus",
                            if (it.success) "PASS" else "PARTIAL"
                        )
                    },
                    rollbackResult?.let {
                        BootstrapEvidence("applicationRuntimeRollbackFields", it.restoredFields.joinToString(","))
                    },
                    rollbackResult?.let {
                        BootstrapEvidence("applicationRuntimeRollbackFailures", it.failureReasons.joinToString(","))
                    }
                )
                nonTerminalFailure(
                    input = input,
                    startMs = startMs,
                    message = "Guest Application creation failed: ${error.message}",
                    error = error,
                    evidence = failureEvidence,
                    rollbackNote = rollbackResult?.let {
                        if (it.success) "Guest LoadedApk/ActivityThread binding rolled back" else {
                            "Guest runtime rollback incomplete: ${it.failureReasons.joinToString(",")}"
                        }
                    }
                )
            }
        )
    }

    private fun normalizeThreadContextClassLoaderAfterApplicationAttach(
        currentThread: Thread,
        creation: GuestApplicationCreateResult,
        guestClassLoader: ClassLoader,
        requestedApplicationClassName: String,
        progress: (status: String, detail: String, extra: Map<String, String>) -> Unit,
        evidenceSink: (List<BootstrapEvidence>) -> Unit
    ) {
        val loadedApk = creation.loadedApk
        val loadedApkClassLoader = loadedApk?.let(loadedApkClassLoaderInspector)
        val applicationClassLoader = applicationClassLoaderInspector(creation.application)
        val threadContextClassLoaderBefore = currentThread.contextClassLoader
        val loadedApkOwnershipMatches = loadedApk == null || loadedApkClassLoader === guestClassLoader
        val applicationOwnershipMatches = loadedApk == null || applicationClassLoaderMatches(
            application = creation.application,
            requestedApplicationClassName = requestedApplicationClassName,
            actualClassLoader = applicationClassLoader,
            guestClassLoader = guestClassLoader
        )
        val contextClassLoaderUnchanged = threadContextClassLoaderBefore === guestClassLoader
        val frameworkReplacementRecognized = !contextClassLoaderUnchanged && loadedApk != null &&
            frameworkContextClassLoaderReplacementInspector(
                threadContextClassLoaderBefore,
                guestClassLoader
            )
        val inspectedEvidence = buildList {
            add(BootstrapEvidence("loadedApkClassLoaderOwnershipStatus", status(loadedApk, loadedApkOwnershipMatches)))
            add(BootstrapEvidence("applicationClassLoaderOwnershipStatus", status(loadedApk, applicationOwnershipMatches)))
            add(
                BootstrapEvidence(
                    "threadContextClassLoaderReplacementStatus",
                    when {
                        contextClassLoaderUnchanged -> "UNCHANGED"
                        frameworkReplacementRecognized -> "AOSP_WARNING_CONTEXT_CLASS_LOADER"
                        else -> "UNRECOGNIZED"
                    }
                )
            )
            addAll(classLoaderEvidence("guestClassLoader", guestClassLoader))
            addAll(classLoaderEvidence("loadedApkClassLoader", loadedApkClassLoader))
            addAll(classLoaderEvidence("applicationClassLoader", applicationClassLoader))
            addAll(classLoaderEvidence("threadContextClassLoaderBefore", threadContextClassLoaderBefore))
        }
        evidenceSink(inspectedEvidence)
        progress(
            "CLASS_LOADER_OWNERSHIP_INSPECTED",
            "LoadedApk, Application, and thread ClassLoader ownership inspected",
            inspectedEvidence.associate { it.key to it.value }
        )

        if (loadedApk == null) {
            check(threadContextClassLoaderBefore === guestClassLoader) {
                "Guest Application creator changed the guest thread context ClassLoader without LoadedApk ownership proof"
            }
        } else {
            check(loadedApkOwnershipMatches) {
                "LoadedApk.mClassLoader does not match the guest ClassLoader"
            }
            check(applicationOwnershipMatches) {
                "Guest Application class is not owned by the guest ClassLoader"
            }
            check(contextClassLoaderUnchanged || frameworkReplacementRecognized) {
                "LoadedApk.makeApplication installed an unrecognized thread context ClassLoader"
            }
            // LoadedApk.initializeJavaContextClassLoader() may install a framework wrapper.
            // Normalize only the known AOSP wrapper after durable ownership checks pass.
            if (frameworkReplacementRecognized) {
                currentThread.contextClassLoader = guestClassLoader
            }
        }
        check(currentThread.contextClassLoader === guestClassLoader) {
            "Guest Application thread context ClassLoader normalization failed"
        }

        val normalizationStatus = if (threadContextClassLoaderBefore === guestClassLoader) {
            "NOT_REQUIRED"
        } else {
            "PASS"
        }
        val finalEvidence = inspectedEvidence +
            BootstrapEvidence("threadContextClassLoaderNormalizationStatus", normalizationStatus) +
            classLoaderEvidence("threadContextClassLoaderAfter", currentThread.contextClassLoader)
        evidenceSink(finalEvidence)
        progress(
            if (normalizationStatus == "PASS") {
                "TCCL_NORMALIZED_AFTER_APPLICATION_ATTACH"
            } else {
                "TCCL_VERIFIED_AFTER_APPLICATION_ATTACH"
            },
            if (normalizationStatus == "PASS") {
                "framework thread context ClassLoader normalized after guest ownership verification"
            } else {
                "guest thread context ClassLoader remained stable after LoadedApk.makeApplication"
            },
            finalEvidence.associate { it.key to it.value }
        )
    }

    private fun verifyClassLoaderOwnershipAtCheckpoint(
        currentThread: Thread,
        creation: GuestApplicationCreateResult,
        guestClassLoader: ClassLoader,
        requestedApplicationClassName: String,
        evidencePrefix: String,
        checkpointLabel: String,
        progress: (status: String, detail: String, extra: Map<String, String>) -> Unit,
        evidenceSink: (List<BootstrapEvidence>) -> Unit
    ) {
        val loadedApk = creation.loadedApk
        val loadedApkClassLoader = loadedApk?.let(loadedApkClassLoaderInspector)
        val applicationClassLoader = applicationClassLoaderInspector(creation.application)
        val loadedApkOwnershipMatches = loadedApk == null || loadedApkClassLoader === guestClassLoader
        val applicationOwnershipMatches = loadedApk == null || applicationClassLoaderMatches(
            application = creation.application,
            requestedApplicationClassName = requestedApplicationClassName,
            actualClassLoader = applicationClassLoader,
            guestClassLoader = guestClassLoader
        )
        val threadOwnershipMatches = currentThread.contextClassLoader === guestClassLoader
        val prerequisitesMatch = loadedApkOwnershipMatches &&
            applicationOwnershipMatches &&
            threadOwnershipMatches
        val applicationBinding = if (loadedApk != null && prerequisitesMatch) {
            finalApplicationBindingInspector(creation.application, loadedApk)
        } else {
            null
        }
        val applicationContextClassLoader = if (applicationBinding?.matches == true) {
            applicationContextClassLoaderInspector(creation.application)
        } else {
            null
        }
        val applicationContextClassLoaderMatches = loadedApk == null ||
            applicationContextClassLoader === guestClassLoader
        val applicationBindingMatches = loadedApk == null || applicationBinding?.matches == true
        val evidence = buildList {
            add(
                BootstrapEvidence(
                    "${evidencePrefix}LoadedApkClassLoaderStatus",
                    status(loadedApk, loadedApkOwnershipMatches)
                )
            )
            add(
                BootstrapEvidence(
                    "${evidencePrefix}ApplicationClassLoaderStatus",
                    status(loadedApk, applicationOwnershipMatches)
                )
            )
            add(
                BootstrapEvidence(
                    "${evidencePrefix}ThreadContextClassLoaderStatus",
                    if (threadOwnershipMatches) "PASS" else "FAIL"
                )
            )
            add(
                BootstrapEvidence(
                    "${evidencePrefix}ApplicationContextBindingStatus",
                    status(loadedApk, applicationBindingMatches)
                )
            )
            add(
                BootstrapEvidence(
                    "${evidencePrefix}ApplicationContextBindingReason",
                    applicationBinding?.reason ?: if (loadedApk == null) {
                        "LOADED_APK_REFERENCE_UNAVAILABLE"
                    } else {
                        "CLASS_LOADER_PREREQUISITE_FAILED"
                    }
                )
            )
            add(
                BootstrapEvidence(
                    "${evidencePrefix}ApplicationContextClassLoaderStatus",
                    status(loadedApk, applicationContextClassLoaderMatches)
                )
            )
            addAll(classLoaderEvidence("${evidencePrefix}ApplicationContextClassLoader", applicationContextClassLoader))
            addAll(classLoaderEvidence("${evidencePrefix}ThreadContextClassLoader", currentThread.contextClassLoader))
        }
        evidenceSink(evidence)
        val ownershipVerified = prerequisitesMatch &&
            applicationBindingMatches &&
            applicationContextClassLoaderMatches
        val progressCheckpoint = checkpointLabel
            .uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
        progress(
            if (ownershipVerified) {
                "CLASS_LOADER_OWNERSHIP_VERIFIED_$progressCheckpoint"
            } else {
                "CLASS_LOADER_OWNERSHIP_FAILED_$progressCheckpoint"
            },
            if (ownershipVerified) {
                "guest ClassLoader ownership verified $checkpointLabel"
            } else {
                "guest ClassLoader ownership check failed $checkpointLabel"
            },
            evidence.associate { it.key to it.value }
        )
        check(loadedApkOwnershipMatches) {
            "LoadedApk.mClassLoader changed $checkpointLabel"
        }
        check(applicationOwnershipMatches) {
            "Guest Application ClassLoader changed $checkpointLabel"
        }
        check(threadOwnershipMatches) {
            "Guest thread context ClassLoader changed $checkpointLabel"
        }
        check(applicationBindingMatches) {
            "Guest Application Context is not bound to the guest LoadedApk $checkpointLabel: " +
                applicationBinding?.reason.orEmpty()
        }
        check(applicationContextClassLoaderMatches) {
            "Guest Application Context ClassLoader changed $checkpointLabel"
        }
    }

    private fun applicationClassLoaderMatches(
        application: Application,
        requestedApplicationClassName: String,
        actualClassLoader: ClassLoader?,
        guestClassLoader: ClassLoader
    ): Boolean {
        if (actualClassLoader === guestClassLoader) return true
        return requestedApplicationClassName == Application::class.java.name &&
            application.javaClass === Application::class.java &&
            actualClassLoader === Application::class.java.classLoader
    }

    private fun status(loadedApk: Any?, matches: Boolean): String = when {
        loadedApk == null -> "SKIPPED_NO_LOADED_APK"
        matches -> "PASS"
        else -> "FAIL"
    }

    private fun classLoaderEvidence(prefix: String, classLoader: ClassLoader?): List<BootstrapEvidence> {
        val parent = classLoader?.let { runCatching { it.parent }.getOrNull() }
        return listOf(
            BootstrapEvidence("${prefix}Class", classLoader?.javaClass?.name ?: "BOOT_CLASS_LOADER"),
            BootstrapEvidence("${prefix}Identity", classLoader.identity()),
            BootstrapEvidence("${prefix}ParentClass", parent?.javaClass?.name ?: "BOOT_CLASS_LOADER"),
            BootstrapEvidence("${prefix}ParentIdentity", parent.identity())
        )
    }

    private fun ClassLoader?.identity(): String = this?.let {
        System.identityHashCode(it).toString()
    } ?: "BOOT"

    private fun resolveGuestProcessName(
        requestedProcessName: String?,
        applicationProcessName: String?,
        originPackageName: String
    ): String {
        val normalized = requestedProcessName?.trim()?.takeIf(String::isNotEmpty)
            ?: applicationProcessName?.trim()?.takeIf(String::isNotEmpty)
            ?: originPackageName
        return if (normalized.startsWith(':')) originPackageName + normalized else normalized
    }

    private fun currentApplicationThreadEvidence(): ApplicationThreadEvidence {
        val threadName = Thread.currentThread().name
        return runCatching {
            val currentLooper = Looper.myLooper()
            val mainLooper = Looper.getMainLooper()
            ApplicationThreadEvidence(
                name = threadName,
                hasLooper = currentLooper != null,
                isMain = currentLooper != null && currentLooper == mainLooper,
                looperProbeSkippedReason = null
            )
        }.getOrElse { error ->
            ApplicationThreadEvidence(
                name = threadName,
                hasLooper = false,
                isMain = false,
                looperProbeSkippedReason = error.message ?: error.javaClass.name
            )
        }
    }

    private fun nonTerminalFailure(
        input: BootstrapStageInput,
        startMs: Long,
        message: String,
        error: Throwable? = null,
        evidence: List<BootstrapEvidence> = emptyList(),
        rollbackNote: String? = null
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.APPLICATION,
            message = message,
            error = error,
            evidence = evidence,
            rollbackNote = rollbackNote,
            durationMs = clock() - startMs
        ),
        terminalFailure = false
    )

    private fun publishRuntimeBeforeOnCreate(input: BootstrapStageInput, guestApplication: Application) {
        val snapshot = requireNotNull(input.packageSnapshot) {
            "Package snapshot is required before publishing Application runtime"
        }
        val instance = requireNotNull(input.instance) {
            "Instance is required before publishing Application runtime"
        }
        val guestClassLoader = requireNotNull(input.guestClassLoader) {
            "Guest ClassLoader is required before publishing Application runtime"
        }
        runtimePublisher(
            input.instanceId,
            HostedBootstrapResult(
                instanceId = input.instanceId,
                installId = input.installRecord?.packageName,
                originPackageName = instance.originPackageName,
                virtualPackageName = instance.virtualPackageName,
                applicationLabel = snapshot.applicationLabel,
                processSlot = input.processSlot,
                originApkPath = input.originApkPath,
                dataRoot = instance.dataRoot,
                guestClassLoader = guestClassLoader,
                guestApplication = guestApplication,
                installRecord = input.installRecord,
                packageSnapshot = snapshot,
                launcherActivityClassName = input.launcherActivityClassName,
                stageResults = emptyList(),
                summary = emptyList<BootstrapResult>().toSummary(),
                success = true,
                diagnostics = null
            )
        )
    }

    private fun finalizeApplicationAfterOnCreate(
        creation: GuestApplicationCreateResult
    ): GuestApplicationCreateResult {
        val loadedApk = creation.loadedApk ?: return creation.copy(
            evidence = creation.evidence + listOf(
                BootstrapEvidence("loadedApkFinalApplicationStatus", "SKIPPED"),
                BootstrapEvidence("loadedApkFinalApplicationSource", "CREATOR"),
                BootstrapEvidence("loadedApkFinalApplicationReason", "LOADED_APK_REFERENCE_UNAVAILABLE")
            )
        )
        val finalApplication = LoadedApkBridge.application(loadedApk)
            ?: throw IllegalStateException("LoadedApk.mApplication is null after Application.onCreate")
        val source = if (finalApplication === creation.application) "ORIGINAL" else "DELEGATE"
        val binding = finalApplicationBindingInspector(finalApplication, loadedApk)
        check(binding.matches) {
            "Final Application is not bound to the guest LoadedApk: ${binding.reason}"
        }
        return creation.copy(
            application = finalApplication,
            attachedContextPackageName = runCatching { finalApplication.packageName }.getOrNull()
                ?: creation.attachedContextPackageName,
            evidence = creation.evidence + listOf(
                BootstrapEvidence("loadedApkFinalApplicationStatus", "PASS"),
                BootstrapEvidence("loadedApkFinalApplicationSource", source),
                BootstrapEvidence("loadedApkFinalApplicationClass", finalApplication.javaClass.name),
                BootstrapEvidence("loadedApkFinalApplicationContextClass", binding.contextClassName.orEmpty()),
                BootstrapEvidence("loadedApkFinalApplicationContextWrapperDepth", binding.wrapperDepth.toString()),
                BootstrapEvidence("loadedApkFinalApplicationReason", binding.reason)
            )
        )
    }

    private data class ApplicationThreadEvidence(
        val name: String,
        val hasLooper: Boolean,
        val isMain: Boolean,
        val looperProbeSkippedReason: String?
    )
}

private fun isAospWarningContextClassLoader(
    candidate: ClassLoader?,
    guestClassLoader: ClassLoader
): Boolean = candidate !== guestClassLoader &&
    candidate?.javaClass?.name == "android.app.LoadedApk\$WarningContextClassLoader"

class LoadedApkGuestApplicationCreator(
    private val activityThreadProvider: () -> Any = { ActivityThreadCompat.currentActivityThread() },
    private val loadedApkInstaller: (
        activityThread: Any,
        state: LoadedApkRuntimeState,
        packageAliases: Collection<String>
    ) -> ActivityThreadLoadedApkInstallResult = { activityThread, state, aliases ->
        ActivityThreadLoadedApkInstaller.installGuestSandbox(
            activityThread = activityThread,
            state = state,
            packageAliases = aliases
        )
    },
    private val resourceBundleProvider: (Context, VirtualContextConfig) -> VirtualResourceBundle =
        { context, config -> VirtualResourcesManager(context).create(config) },
    private val makeApplicationInvoker: (Any, android.app.Instrumentation?) -> Application =
        { loadedApk, instrumentation -> invokeMakeApplication(loadedApk, instrumentation) },
    private val applicationBinder: (
        activityThread: Any,
        installResult: ActivityThreadLoadedApkInstallResult,
        state: LoadedApkRuntimeState,
        application: Application
    ) -> ActivityThreadApplicationBindResult = { activityThread, installResult, state, application ->
        ActivityThreadLoadedApkInstaller.bindApplication(
            activityThread = activityThread,
            installResult = installResult,
            state = state,
            application = application
        )
    }
) : GuestApplicationCreator {
    override fun create(request: GuestApplicationCreateRequest): GuestApplicationCreateResult {
        return runCatching { createWithLoadedApk(request) }.getOrElse { error ->
            val creationError = error as? LoadedApkApplicationCreationException
                ?: LoadedApkApplicationCreationException(
                    message = "LoadedApk Application creation failed: ${error.message ?: error.javaClass.name}",
                    cause = error
                )
            request.progress(
                "LOADED_APK_CREATE_FAILED",
                "LoadedApk makeApplication failed; reflective fallback is disabled",
                mapOf(
                    "loadedApkApplicationCreatorStatus" to "FAIL",
                    "reflectiveApplicationFallbackEnabled" to "false",
                    "loadedApkCreateErrorClass" to creationError.javaClass.name,
                    "loadedApkCreateErrorMessage" to creationError.message.orEmpty(),
                    "loadedApkRollbackStatus" to creationError.rollbackResult?.let {
                        if (it.success) "PASS" else "PARTIAL"
                    }.orEmpty()
                )
            )
            throw creationError
        }
    }

    private fun createWithLoadedApk(request: GuestApplicationCreateRequest): GuestApplicationCreateResult {
        val snapshot = request.virtualContextConfig.packageSnapshot
            ?: throw IllegalStateException("packageSnapshot is required for LoadedApk Application creation")
        request.progress("LOADED_APK_CREATE_STARTED", "creating guest LoadedApk sandbox", emptyMap())
        val activityThread = activityThreadProvider()
        val resourceBundle = resourceBundleProvider(request.hostContext, request.virtualContextConfig)
        val applicationInfo = VirtualPackageInfoFactory.applicationInfo(
            snapshot,
            RuntimeUidCompat.resolve(resourceBundle.applicationInfo.uid),
            VirtualPackageQueryFlags.INTERNAL_FULL
        ).apply {
            className = request.applicationClassName.takeUnless { it == Application::class.java.name }
            name = className
            processName = request.virtualContextConfig.effectiveGuestProcessName
        }
        val state = LoadedApkRuntimeState(
            packageName = snapshot.originPackageName,
            applicationInfo = applicationInfo,
            resources = resourceBundle.resources,
            classLoader = request.guestClassLoader,
            application = null,
            binderPackageName = resolveSystemHostPackageName(
                guestPackages = setOf(snapshot.originPackageName, snapshot.virtualPackageName),
                processSlot = request.virtualContextConfig.processSlot,
                processName = runCatching { Application.getProcessName() }.getOrNull(),
                baseOpPackageName = runCatching { request.hostContext.opPackageName }.getOrNull(),
                basePackageName = runCatching { request.hostContext.packageName }.getOrNull()
            )
        )
        val aliases = listOf(snapshot.originPackageName, snapshot.virtualPackageName)
        val installResult = loadedApkInstaller(activityThread, state, aliases)
        val loadedApk = installResult.loadedApk
            ?: throw LoadedApkApplicationCreationException(
                message = "LoadedApk unavailable: ${installResult.skippedReason ?: "UNKNOWN"}",
                rollbackResult = installResult.rollbackResult
            )
        request.progress(
            "LOADED_APK_CREATE_FINISHED",
            "guest LoadedApk sandbox installed",
            mapOf(
                "loadedApkSource" to installResult.source.name,
                "loadedApkAliasCount" to installResult.installedAliasCount.toString()
            )
        )
        request.progress(
            "MAKE_APPLICATION_STARTED",
            "calling LoadedApk.makeApplication without Application.onCreate",
            mapOf("instrumentationArgument" to "null")
        )
        val application = runCatching { makeApplicationInvoker(loadedApk, null) }
            .getOrElse { error ->
                throw LoadedApkApplicationCreationException(
                    message = "LoadedApk.makeApplication failed: ${error.message ?: error.javaClass.name}",
                    cause = error,
                    rollbackResult = installResult.rollbackHandle?.rollback()
                )
            }
        request.progress(
            "MAKE_APPLICATION_FINISHED",
            "LoadedApk.makeApplication returned",
            mapOf("actualClass" to application.javaClass.name)
        )
        val bindResult = runCatching {
            applicationBinder(activityThread, installResult, state, application)
        }.getOrElse { error ->
            throw LoadedApkApplicationCreationException(
                message = "ActivityThread Application binding failed: ${error.message ?: error.javaClass.name}",
                cause = error,
                rollbackResult = ActivityThreadLoadedApkInstaller.rollbackUnboundApplication(
                    activityThread = activityThread,
                    installResult = installResult,
                    application = application
                )
            )
        }
        if (!bindResult.successful) {
            throw LoadedApkApplicationCreationException(
                message = "ActivityThread Application binding failed: ${bindResult.failureReasons.joinToString("|")}",
                rollbackResult = bindResult.rollbackResult
                    ?: ActivityThreadLoadedApkInstaller.rollbackUnboundApplication(
                        activityThread = activityThread,
                        installResult = installResult,
                        application = application
                )
            )
        }
        if (bindResult.rollbackHandle == null) {
            throw LoadedApkApplicationCreationException(
                message = "ActivityThread Application binding failed: ROLLBACK_HANDLE_MISSING",
                rollbackResult = ActivityThreadLoadedApkInstaller.rollbackUnboundApplication(
                    activityThread = activityThread,
                    installResult = installResult,
                    application = application
                )
            )
        }
        val contextPackageName = runCatching { application.packageName }.getOrNull()
        return GuestApplicationCreateResult(
            application = application,
            attachedContextPackageName = contextPackageName,
            evidence = listOf(
                BootstrapEvidence("applicationCreator", "LOADED_APK_MAKE_APPLICATION"),
                BootstrapEvidence("applicationRequestedClassSource", request.applicationClassSource),
                BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"),
                BootstrapEvidence("loadedApkApplicationCreatorSource", installResult.source.name),
                BootstrapEvidence("loadedApkApplicationCreatorTargetClass", installResult.targetClassName),
                BootstrapEvidence("loadedApkApplicationCreatorPatchedFields", installResult.patchResult.patchedFields.joinToString(",")),
                BootstrapEvidence("loadedApkApplicationCreatorSkippedFields", installResult.patchResult.skippedFieldReasons.joinToString(",")),
                BootstrapEvidence("loadedApkApplicationCreatorInstalledAliasCount", installResult.installedAliasCount.toString()),
                BootstrapEvidence("loadedApkBinderPackageName", state.binderPackageName),
                BootstrapEvidence(
                    "loadedApkApplicationInfoProcessName",
                    request.virtualContextConfig.effectiveGuestProcessName
                ),
                BootstrapEvidence(
                    "loadedApkApplicationCreatorBindPatchedFields",
                    bindResult.loadedApkPatchResult.patchedFields.joinToString(",")
                ),
                BootstrapEvidence("loadedApkApplicationCreatorBindSkippedFields", bindResult.activityThreadSkippedFields.joinToString(",")),
                BootstrapEvidence("activityThreadApplicationBindingStatus", bindResult.status.name),
                BootstrapEvidence("activityThreadApplicationBindingPatchedFields", bindResult.activityThreadPatchedFields.joinToString(",")),
                BootstrapEvidence("loadedApkApplicationOnCreateDeferred", "true")
            ),
            rollbackHandle = bindResult.rollbackHandle,
            loadedApk = loadedApk
        )
    }

    companion object {
        private fun invokeMakeApplication(
            loadedApk: Any,
            instrumentation: android.app.Instrumentation?
        ): Application {
            val method = findMakeApplicationMethod(loadedApk.javaClass)
                ?: throw NoSuchMethodException("LoadedApk.makeApplication(boolean, Instrumentation)")
            method.isAccessible = true
            return method.invoke(loadedApk, false, instrumentation) as? Application
                ?: throw IllegalStateException("LoadedApk.makeApplication returned null")
        }

        private fun findMakeApplicationMethod(type: Class<*>): java.lang.reflect.Method? {
            var current: Class<*>? = type
            while (current != null) {
                current.declaredMethods.firstOrNull { method ->
                    method.name == "makeApplication" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == java.lang.Boolean.TYPE &&
                        android.app.Instrumentation::class.java.isAssignableFrom(method.parameterTypes[1])
                }?.let { return it }
                current = current.superclass
            }
            return null
        }
    }
}

fun interface GuestApplicationCreator {
    fun create(request: GuestApplicationCreateRequest): GuestApplicationCreateResult
}

data class GuestApplicationCreateRequest(
    val applicationClassName: String,
    val applicationClassSource: String,
    val hostContext: Context,
    val virtualContextConfig: VirtualContextConfig,
    val guestClassLoader: ClassLoader,
    val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    val progress: (status: String, detail: String, extra: Map<String, String>) -> Unit = { _, _, _ -> }
)

data class GuestApplicationCreateResult(
    val application: Application,
    val attachedContextPackageName: String?,
    val evidence: List<BootstrapEvidence> = emptyList(),
    val rollbackHandle: ActivityThreadLoadedApkRollbackHandle? = null,
    val loadedApk: Any? = null
)

class LoadedApkApplicationCreationException(
    message: String,
    cause: Throwable? = null,
    val rollbackResult: ActivityThreadLoadedApkRollbackResult? = null
) : IllegalStateException(message, cause)

class ReflectiveGuestApplicationCreator : GuestApplicationCreator {
    override fun create(request: GuestApplicationCreateRequest): GuestApplicationCreateResult {
        request.progress("LOAD_CLASS_STARTED", "loading guest Application class", emptyMap())
        val appClass = request.guestClassLoader.loadClass(request.applicationClassName)
        request.progress("LOAD_CLASS_FINISHED", "guest Application class loaded", mapOf("loadedClass" to appClass.name))
        request.progress("CONSTRUCTOR_STARTED", "constructing guest Application", emptyMap())
        val guestApplication = appClass.getDeclaredConstructor().newInstance() as Application
        request.progress("CONSTRUCTOR_FINISHED", "guest Application constructed", mapOf("actualClass" to guestApplication.javaClass.name))
        request.progress("CONTEXT_CREATE_STARTED", "creating virtual Application context", emptyMap())
        val guestContext = VirtualContextWrappers.create(
            base = request.hostContext,
            config = request.virtualContextConfig,
            guestClassLoader = request.guestClassLoader,
            activityRecordManager = request.activityRecordManager,
            processRuntime = request.processRuntime
        )
        request.progress("CONTEXT_CREATE_FINISHED", "virtual Application context created", mapOf("contextPackageName" to guestContext.packageName))
        request.progress("ATTACH_STARTED", "calling Application.attachBaseContext", mapOf("contextPackageName" to guestContext.packageName))
        val attachMethod = findAttachBaseContextMethod(guestApplication.javaClass)
        attachMethod.isAccessible = true
        attachMethod.invoke(guestApplication, guestContext)
        return GuestApplicationCreateResult(
            application = guestApplication,
            attachedContextPackageName = guestContext.packageName,
            evidence = listOf(
                BootstrapEvidence("applicationCreator", "REFLECTIVE_ATTACH"),
                BootstrapEvidence("applicationRequestedClassSource", request.applicationClassSource)
            )
        )
    }

    private fun findAttachBaseContextMethod(startClass: Class<*>): java.lang.reflect.Method {
        var clazz: Class<*>? = startClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(
                    "attachBaseContext",
                    Context::class.java
                )
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException("attachBaseContext(Context) not found in Application hierarchy")
    }
}

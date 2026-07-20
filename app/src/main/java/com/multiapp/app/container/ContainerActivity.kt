package com.multiapp.app.container

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.multiapp.core.engine.EngineActivityTaskController
import com.multiapp.core.engine.EngineActivityTaskControllers
import com.multiapp.core.engine.EngineBootstrapStage
import com.multiapp.core.engine.EngineBootstrapStageResult
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineProxyActivityRecords
import com.multiapp.core.engine.EngineProxyActivitySlots
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.engine.HostedRuntimeBindOutcome
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineLaunchIntentContract
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal sealed class BootstrapCompletionAction {
    data class FinishWithEvidence(
        val status: String,
        val stage: String,
        val detail: String
    ) : BootstrapCompletionAction()

    data class LaunchProxy(
        val originPackageName: String,
        val guestActivityClassName: String,
        val launchMode: String?,
        val taskAffinity: String?
    ) : BootstrapCompletionAction()
}

/**
 * Fixed host entry point for v2 hosted containers.
 *
 * Launched internally by MultiApp with an [EXTRA_INSTANCE_ID] to identify
 * which app-instance to host. Does NOT generate stub APKs, does NOT run a
 * full Virtual AMS, and does NOT touch QQ-Reader hooks or LSPlant/Xposed.
 *
 * On launch, bootstraps the hosted runtime via [HostedRuntimeEngine]
 * which creates a guest Context wrapper and attempts to instantiate the guest
 * [android.app.Application] class.
 *
 * After successful bootstrap, resolves the guest launcher Activity and maps it
 * to a real host ProxyActivity slot. This starts the move toward
 * ProxyActivity + Instrumentation/ActivityThread virtualization.
 */
open class ContainerActivity : Activity() {

    private val hostedRuntimeEngine: HostedRuntimeEngine by lazy(LazyThreadSafetyMode.NONE) {
        hostedRuntimeEngineFrom(this)
    }

    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    companion object {
        private const val TAG = "ContainerActivity"
        private const val DETAILED_EVIDENCE_DELAY_MS = 2_000L
        private val diagnosticsExecutor = ScheduledThreadPoolExecutor(
            1
        ) { runnable ->
            Thread(runnable, "multiapp-runtime-evidence").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(10L, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        /** Key for the hosted-instance identifier in intent extras. */
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"

        /** Optional key for tracking the install origin. */
        const val EXTRA_INSTALL_ORIGIN = "multiapp.installOrigin"

        /**
         * Provider authority route switch.
         *
         * Default hosted launches enable this VirtualApp/DroidPlugin-style
         * Provider rewrite path. Diagnostic callers may still disable it to
         * compare hook-free behavior.
         */
        const val EXTRA_ENABLE_PROVIDER_HOOK = "multiapp.profile.providerHookEnabled"

        /**
         * Build a launch [Intent] for [ContainerActivity].
         *
         * @param context  used to create the explicit intent
         * @param instanceId  required identifier for the hosted instance
         * @param installOrigin  optional origin tag for analytics / tracking
         */
        fun createIntent(
            context: Context,
            instanceId: String,
            installOrigin: String? = null,
            providerHookEnabled: Boolean = true
        ): Intent {
            return Intent(context, ContainerActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_INSTALL_ORIGIN, installOrigin)
                .putExtra(EXTRA_ENABLE_PROVIDER_HOOK, providerHookEnabled)
        }

        internal fun shouldFinishAfterBootstrap(result: EngineHostedBootstrapResult): Boolean =
            !result.success || result.guestClassLoader == null

        internal fun resolveEvidenceMode(
            profileName: String?,
            evidenceModeName: String?
        ): EngineEvidenceMode {
            val requestedMode = evidenceModeName
                ?.let { value -> runCatching { EngineEvidenceMode.valueOf(value) }.getOrNull() }
                ?: EngineEvidenceMode.DEFAULT
            if (requestedMode != EngineEvidenceMode.DEFAULT) return requestedMode
            val profile = profileName
                ?.let { value -> runCatching { EngineProfile.valueOf(value) }.getOrNull() }
                ?: EngineProfile.BASELINE
            return if (profile == EngineProfile.DIAGNOSTICS_ONLY) {
                EngineEvidenceMode.FULL
            } else {
                EngineEvidenceMode.MINIMAL
            }
        }

        internal fun buildVirtualContextConfig(
            instanceId: String,
            originPackageName: String,
            virtualPackageName: String,
            originApkPath: String,
            dataRoot: String?,
            fallbackDataRoot: File,
            guestClassLoader: ClassLoader,
            applicationLabel: String? = null,
            packageSnapshot: VirtualPackageSnapshot? = null,
            processSlot: String? = null
        ): VirtualContextConfig {
            val resolvedDataRoot = dataRoot ?: fallbackDataRoot.absolutePath
            return VirtualContextConfig(
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                dataDir = resolvedDataRoot,
                sourceDir = originApkPath,
                nativeLibraryDir = packageSnapshot?.nativeLibraryDir ?: resolveNativeLibraryDir(resolvedDataRoot),
                classLoader = guestClassLoader,
                applicationLabel = packageSnapshot?.applicationLabel ?: applicationLabel,
                packageSnapshot = packageSnapshot,
                splitSourceDirs = packageSnapshot?.splitSourceDirs.orEmpty(),
                splitPublicSourceDirs = packageSnapshot?.splitPublicSourceDirs.orEmpty(),
                splitNames = packageSnapshot?.splitNames.orEmpty(),
                isolatedSplits = packageSnapshot?.isolatedSplits ?: false,
                processSlot = processSlot
            )
        }

        internal fun packageManagerProxyStageResult(result: EngineHostedBootstrapResult): EngineBootstrapStageResult? =
            result.firstStageResult(EngineBootstrapStage.PACKAGE_MANAGER_PROXY)

        internal fun launcherActivityStageResult(result: EngineHostedBootstrapResult): EngineBootstrapStageResult? =
            result.lastStageResult(EngineBootstrapStage.LAUNCHER_ACTIVITY)

        internal fun packageManagerProxyEvidenceFields(result: EngineBootstrapStageResult): Map<String, String> =
            result.toEvidenceFields()

        internal fun launcherActivityFailureDetail(result: EngineHostedBootstrapResult): String {
            val launcherStage = result.lastStageResult(EngineBootstrapStage.LAUNCHER_ACTIVITY)
                ?: return "no launcher Activity"
            val evidence = launcherStage.evidence
            return buildList {
                add(launcherStage.message.ifBlank { "no launcher Activity" })
                evidence["resolver"]?.takeIf { it.isNotBlank() }?.let { add("resolver=$it") }
                evidence["candidateCount"]?.takeIf { it.isNotBlank() }?.let { add("candidateCount=$it") }
                evidence["resolvedPackageActivityCount"]?.takeIf { it.isNotBlank() }?.let {
                    add("resolvedPackageActivityCount=$it")
                }
                evidence["packageSnapshotActivityCount"]?.takeIf { it.isNotBlank() }?.let {
                    add("packageSnapshotActivityCount=$it")
                }
                evidence["installRecordActivityCount"]?.takeIf { it.isNotBlank() }?.let {
                    add("installRecordActivityCount=$it")
                }
                evidence["cloneStubDetected"]?.takeIf { it == "true" }?.let {
                    add("cloneStubDetected=true")
                }
                evidence["unsupportedReason"]?.takeIf { it.isNotBlank() }?.let {
                    add("unsupportedReason=$it")
                }
                evidence["candidateLauncherActivities"]?.takeIf { it.isNotBlank() }?.let {
                    add("candidates=$it")
                }
            }.joinToString("; ")
        }

        internal fun bootstrapCompletionAction(result: EngineHostedBootstrapResult): BootstrapCompletionAction {
            if (!result.success) {
                return BootstrapCompletionAction.FinishWithEvidence(
                    status = "FAIL",
                    stage = "BOOTSTRAP",
                    detail = result.summary.failureReason ?: "unknown"
                )
            }
            if (result.guestClassLoader == null) {
                return BootstrapCompletionAction.FinishWithEvidence(
                    status = "FAIL",
                    stage = "CLASS_LOADER",
                    detail = "guestClassLoader is null"
                )
            }
            val launcherClassName = result.launcherActivityClassName
            if (launcherClassName == null) {
                return BootstrapCompletionAction.FinishWithEvidence(
                    status = "FAIL",
                    stage = "LAUNCHER_ACTIVITY",
                    detail = launcherActivityFailureDetail(result)
                )
            }
            return BootstrapCompletionAction.LaunchProxy(
                originPackageName = result.originPackageName ?: result.virtualPackageName ?: "",
                guestActivityClassName = launcherClassName,
                launchMode = result.packageSnapshot
                    ?.activities
                    ?.firstOrNull { it.name == launcherClassName || it.targetActivityName == launcherClassName }
                    ?.launchMode,
                taskAffinity = launcherTaskAffinity(
                    result.packageSnapshot,
                    launcherClassName
                )
            )
        }

        internal fun launcherTaskAffinity(
            snapshot: VirtualPackageSnapshot?,
            launcherClassName: String
        ): String? {
            val packageSnapshot = snapshot ?: return null
            val component = packageSnapshot
                .activities
                ?.firstOrNull { it.name == launcherClassName || it.targetActivityName == launcherClassName }
            val guestAffinity = component?.taskAffinity?.takeIf { it.isNotBlank() }
                ?: packageSnapshot.taskAffinity?.takeIf { it.isNotBlank() }
                ?: packageSnapshot.originPackageName.takeIf { it.isNotBlank() }
                ?: return null
            return "$guestAffinity:${packageSnapshot.instanceId}"
        }

        internal fun resolveNativeLibraryDir(dataRoot: String?): String? {
            if (dataRoot.isNullOrBlank()) return null
            return ContainerRuntimePaths.nativeLibraryDirOrNull(dataRoot)
        }
    }

    private val proxyActivityClassNames by lazy(LazyThreadSafetyMode.NONE) {
        EngineProxyActivitySlots.classNames(packageName)
    }

    private val proxyLaunchModeByClassName by lazy(LazyThreadSafetyMode.NONE) {
        EngineProxyActivitySlots.launchModeByClassName(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceId.isNullOrBlank()) {
            Log.e(TAG, "No instanceId in intent extras")
            finish()
            return
        }

        val installOrigin = intent.getStringExtra(EXTRA_INSTALL_ORIGIN)
        val providerHookEnabled = intent.getBooleanExtra(EXTRA_ENABLE_PROVIDER_HOOK, true)
        val engineProcessSlot = intent.getStringExtra(EngineLaunchIntentContract.EXTRA_ENGINE_PROCESS_SLOT)
        val engineProxySlot = intent.getStringExtra(EngineLaunchIntentContract.EXTRA_ENGINE_PROXY_SLOT)
        val evidenceMode = resolveEvidenceMode(
            profileName = intent.getStringExtra(EngineLaunchIntentContract.EXTRA_ENGINE_PROFILE),
            evidenceModeName = intent.getStringExtra(EngineLaunchIntentContract.EXTRA_ENGINE_EVIDENCE_MODE)
        )
        if (!verifyProcessSlotBinding(instanceId, engineProcessSlot, engineProxySlot)) {
            finish()
            return
        }
        Log.i(
            TAG,
            "Container launch started: instanceId=$instanceId, " +
                "installOrigin=$installOrigin, providerHookEnabled=$providerHookEnabled, " +
                "engineProcessSlot=$engineProcessSlot, engineProxySlot=$engineProxySlot"
        )

        startBootstrap(instanceId, providerHookEnabled, engineProcessSlot, engineProxySlot, evidenceMode)
    }

    private fun verifyProcessSlotBinding(
        instanceId: String,
        engineProcessSlot: String?,
        engineProxySlot: String?
    ): Boolean {
        val actualProcessName = currentProcessName().orEmpty()
        if (engineProcessSlot.isNullOrBlank()) {
            writeProcessSlotEvidence(
                instanceId = instanceId,
                status = "SKIPPED",
                detail = "engine process slot missing",
                engineProcessSlot = "",
                engineProxySlot = engineProxySlot,
                actualProcessName = actualProcessName
            )
            return true
        }
        val matches = actualProcessName == engineProcessSlot
        writeProcessSlotEvidence(
            instanceId = instanceId,
            status = if (matches) "PASS" else "FAIL",
            detail = if (matches) "process slot bound to Android process" else "process slot mismatch",
            engineProcessSlot = engineProcessSlot,
            engineProxySlot = engineProxySlot,
            actualProcessName = actualProcessName
        )
        if (!matches) {
            Log.e(
                TAG,
                "Process slot mismatch: instanceId=$instanceId, expected=$engineProcessSlot, actual=$actualProcessName"
            )
        }
        return matches
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            File("/proc/self/cmdline").readText()
                .substringBefore('\u0000')
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun startBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean,
        engineProcessSlot: String?,
        engineProxySlot: String?,
        evidenceMode: EngineEvidenceMode
    ) {
        val hostContext = applicationContext ?: this
        writeBootstrapProgressEvidence(
            context = hostContext,
            instanceId = instanceId,
            status = "STARTED",
            stage = "bootstrap-start",
            detail = "background bootstrap scheduled",
            engineProcessSlot = engineProcessSlot,
            engineProxySlot = engineProxySlot
        )
        Thread(
            {
                val startedAtMs = System.currentTimeMillis()
                val outcome = runCatching {
                    hostedRuntimeEngine.reusableResult(instanceId)?.let { cached ->
                        writeBootstrapProgressEvidence(
                            context = hostContext,
                            instanceId = instanceId,
                            status = "CACHE_HIT",
                            stage = "bootstrap-cache",
                            detail = "reusing hosted runtime",
                            engineProcessSlot = engineProcessSlot,
                            engineProxySlot = engineProxySlot,
                            elapsedMs = System.currentTimeMillis() - startedAtMs
                        )
                        writeBootstrapEvidence(hostContext, instanceId, cached, evidenceMode)
                        return@runCatching HostedRuntimeBindOutcome(
                            result = cached,
                            ranBootstrapOnThisThread = false
                        )
                    }
                    writeBootstrapProgressEvidence(
                        context = hostContext,
                        instanceId = instanceId,
                        status = "BIND_STARTED",
                        stage = "bootstrap-bind",
                        detail = "binding guest Application",
                        engineProcessSlot = engineProcessSlot,
                        engineProxySlot = engineProxySlot,
                        elapsedMs = System.currentTimeMillis() - startedAtMs
                    )
                    hostedRuntimeEngine.bindApplication(
                        instanceId = instanceId,
                        providerHookEnabled = providerHookEnabled,
                        processSlot = engineProcessSlot
                    ).also { bindOutcome ->
                        writeBootstrapProgressEvidence(
                            context = hostContext,
                            instanceId = instanceId,
                            status = "BIND_FINISHED",
                            stage = "bootstrap-bind",
                            detail = "bindApplication returned",
                            engineProcessSlot = engineProcessSlot,
                            engineProxySlot = engineProxySlot,
                            elapsedMs = System.currentTimeMillis() - startedAtMs
                        )
                        writeBootstrapEvidence(hostContext, instanceId, bindOutcome.result, evidenceMode)
                    }
                }.onFailure { error ->
                    writeBootstrapProgressEvidence(
                        context = hostContext,
                        instanceId = instanceId,
                        status = "FAIL",
                        stage = "bootstrap-exception",
                        detail = error.message ?: error.javaClass.name,
                        engineProcessSlot = engineProcessSlot,
                        engineProxySlot = engineProxySlot,
                        elapsedMs = System.currentTimeMillis() - startedAtMs
                    )
                }
                val resultOutcome = outcome.map { it.result }
                mainHandler.post {
                    handleBootstrapOutcome(instanceId, resultOutcome, engineProxySlot)
                }
            },
            "multiapp-prewarm-${instanceId.take(8)}"
        ).start()
    }

    private fun writeBootstrapEvidence(
        hostContext: Context,
        instanceId: String,
        result: EngineHostedBootstrapResult,
        evidenceMode: EngineEvidenceMode
    ) {
        writePackageManagerProxyEvidence(hostContext, instanceId, result)
        writeApplicationEvidence(hostContext, instanceId, result)
        writeLauncherActivityEvidence(hostContext, instanceId, result)

        if (
            evidenceMode == EngineEvidenceMode.FULL &&
            result.success &&
            result.guestClassLoader != null
        ) {
            scheduleDetailedBootstrapEvidence(hostContext, result)
        }

        val guestApp = result.guestApplication
        if (guestApp != null) {
            Log.i(TAG, "Guest Application created: ${guestApp.javaClass.name}")
        } else {
            Log.w(TAG, "No guest Application created for instanceId=$instanceId")
        }
    }

    private fun handleBootstrapOutcome(
        instanceId: String,
        outcome: Result<EngineHostedBootstrapResult>,
        engineProxySlot: String?
    ) {
        val evidenceContext = applicationContext ?: this
        val result = outcome.getOrElse { error ->
            Log.e(TAG, "Bootstrap crashed for instanceId=$instanceId", error)
            writeLaunchEvidence(
                evidenceContext,
                instanceId,
                "FAIL",
                "BOOTSTRAP_EXCEPTION",
                error.message ?: error.javaClass.name
            )
            finish()
            return
        }

        when (val action = bootstrapCompletionAction(result)) {
            is BootstrapCompletionAction.FinishWithEvidence -> {
                if (action.stage == "BOOTSTRAP") {
                    Log.e(TAG, "Bootstrap failed for instanceId=$instanceId: ${action.detail}")
                } else if (action.stage == "CLASS_LOADER") {
                    Log.e(TAG, "Bootstrap succeeded but guestClassLoader is null for instanceId=$instanceId")
                } else {
                    Log.w(TAG, "No launcher Activity to launch for instanceId=$instanceId")
                }
                writeLaunchEvidence(evidenceContext, instanceId, action.status, action.stage, action.detail)
                finish()
            }
            is BootstrapCompletionAction.LaunchProxy -> {
                val proxyLaunchResult = launchProxyActivity(
                    instanceId = instanceId,
                    originPackageName = action.originPackageName,
                    guestActivityClassName = action.guestActivityClassName,
                    launchMode = action.launchMode,
                    taskAffinity = action.taskAffinity,
                    engineProxySlot = engineProxySlot
                )
                if (proxyLaunchResult.isFailure) {
                    writeLaunchEvidence(
                        evidenceContext,
                        instanceId,
                        "FAIL",
                        "ACTIVITY_PROXY",
                        proxyLaunchResult.exceptionOrNull()?.message ?: "proxy launch failed"
                    )
                    finish()
                    return
                }
                val proxyRecord = proxyLaunchResult.getOrThrow()
                writeLaunchEvidence(
                    evidenceContext,
                    instanceId,
                    "PROXY_LAUNCHED",
                    "ACTIVITY_PROXY",
                    "${proxyRecord.proxyActivityClassName}|${proxyRecord.guestActivityClassName}"
                )
                finish()
            }
        }
    }

    private fun launchProxyActivity(
        instanceId: String,
        originPackageName: String,
        guestActivityClassName: String,
        launchMode: String?,
        taskAffinity: String?,
        engineProxySlot: String?
    ): Result<com.multiapp.core.model.virtual.VirtualActivityRecord> {
        val detail = "legacy ContainerActivity direct launch has no engine Activity capability; " +
            "use VirtualizationEngine.launchInstance"
        Log.e(TAG, "$detail: instanceId=$instanceId, guest=$guestActivityClassName")
        writeEngineProxySlotEvidence(
            instanceId = instanceId,
            status = "FAIL",
            detail = detail,
            engineProxySlot = engineProxySlot.orEmpty(),
            launchMode = launchMode
        )
        return Result.failure(SecurityException(detail))
    }

    private fun proxyActivityCandidatesFromEngineSlot(
        instanceId: String,
        engineProxySlot: String?,
        launchMode: String?
    ): Result<List<String>> {
        if (engineProxySlot.isNullOrBlank()) {
            val detail = "engine proxy slot missing"
            writeEngineProxySlotEvidence(
                instanceId = instanceId,
                status = "FAIL",
                detail = detail,
                engineProxySlot = "",
                launchMode = launchMode
            )
            return Result.failure(IllegalStateException(detail))
        }
        if (engineProxySlot !in proxyActivityClassNames) {
            val detail = "unknown engine proxy slot: $engineProxySlot"
            Log.w(TAG, detail)
            writeEngineProxySlotEvidence(
                instanceId = instanceId,
                status = "FAIL",
                detail = detail,
                engineProxySlot = engineProxySlot,
                launchMode = launchMode
            )
            return Result.failure(IllegalStateException(detail))
        }
        val normalizedLaunchMode = EngineProxyActivitySlots.normalizeLaunchMode(launchMode)
        val proxyLaunchMode = EngineProxyActivitySlots.normalizeLaunchMode(proxyLaunchModeByClassName[engineProxySlot])
        if (proxyLaunchMode != normalizedLaunchMode) {
            val detail = "engine proxy slot launchMode mismatch: slot=$engineProxySlot, " +
                "slotMode=${proxyLaunchMode ?: "standard"}, requested=${normalizedLaunchMode ?: "standard"}"
            Log.w(TAG, detail)
            writeEngineProxySlotEvidence(
                instanceId = instanceId,
                status = "FAIL",
                detail = detail,
                engineProxySlot = engineProxySlot,
                launchMode = launchMode
            )
            return Result.failure(IllegalStateException(detail))
        }
        writeEngineProxySlotEvidence(
            instanceId = instanceId,
            status = "PASS",
            detail = "engine proxy slot accepted",
            engineProxySlot = engineProxySlot,
            launchMode = launchMode
        )
        return Result.success(listOf(engineProxySlot))
    }

    private fun pruneProxyActivityRuntimeRecords(instanceId: String) {
        val knownProxyClasses = proxyActivityClassNames.toSet()
        val liveProxyClasses = liveProxyActivityClassNames(knownProxyClasses)
        val removedRuntimeRecords = EngineProxyActivityRecords().pruneStaleProxyRecords(
            knownProxyActivityClassNames = knownProxyClasses,
            liveProxyActivityClassNames = liveProxyClasses
        )

        writeProxySlotPruneEvidence(
            instanceId = instanceId,
            removedRuntimeRecords = removedRuntimeRecords,
            liveProxyClasses = liveProxyClasses,
            knownProxyCount = knownProxyClasses.size
        )
        persistActivityTaskState(instanceId, "after-runtime-record-prune")
    }

    @Suppress("DEPRECATION")
    private fun liveProxyActivityClassNames(knownProxyClasses: Set<String>): Set<String> {
        val activityManager = getSystemService(ActivityManager::class.java) ?: return emptySet()
        return runCatching {
            activityManager.appTasks
                .flatMap { task ->
                    val taskInfo = task.taskInfo ?: return@flatMap emptyList()
                    listOfNotNull(
                        taskInfo.baseActivity?.className,
                        taskInfo.topActivity?.className
                    )
                }
                .filterTo(linkedSetOf()) { it in knownProxyClasses }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read app tasks while pruning proxy Activity slots", error)
            emptySet()
        }
    }

    /** Directory for [JsonInstallRecordStore] persistence. */
    private fun getInstallStoreDir(): File =
        ContainerRuntimePaths.installStoreDir(this)

    /** Base directory for instance data roots. */
    private fun getDataRootDir(): File =
        ContainerRuntimePaths.instanceDataRootBase(this)

    private fun writePackageManagerProxyEvidence(
        context: Context,
        instanceId: String,
        result: EngineHostedBootstrapResult
    ) {
        val stageResult = packageManagerProxyStageResult(result) ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = instanceId,
                component = "package-manager-proxy",
                fields = packageManagerProxyEvidenceFields(stageResult)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write package-manager-proxy evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeApplicationEvidence(
        context: Context,
        instanceId: String,
        result: EngineHostedBootstrapResult
    ) {
        val stageResult = result.firstStageResult(EngineBootstrapStage.APPLICATION) ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = instanceId,
                component = "application",
                fields = packageManagerProxyEvidenceFields(stageResult)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write application evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeLauncherActivityEvidence(
        context: Context,
        instanceId: String,
        result: EngineHostedBootstrapResult
    ) {
        val stageResult = launcherActivityStageResult(result) ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = instanceId,
                component = "launcher-activity",
                fields = packageManagerProxyEvidenceFields(stageResult)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write launcher activity evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeLaunchEvidence(
        context: Context,
        instanceId: String,
        status: String,
        stage: String,
        detail: String
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = instanceId,
                component = "launch",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to stage,
                    "detail" to detail
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write launch evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeBootstrapProgressEvidence(
        context: Context,
        instanceId: String,
        status: String,
        stage: String,
        detail: String,
        engineProcessSlot: String?,
        engineProxySlot: String?,
        elapsedMs: Long? = null
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = context,
                instanceId = instanceId,
                component = "bootstrap-progress",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to stage,
                    "detail" to detail,
                    "engineProcessSlot" to engineProcessSlot.orEmpty(),
                    "engineProxySlot" to engineProxySlot.orEmpty(),
                    "threadName" to Thread.currentThread().name,
                    "elapsedMs" to (elapsedMs?.toString() ?: "")
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write bootstrap progress evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeProcessSlotEvidence(
        instanceId: String,
        status: String,
        detail: String,
        engineProcessSlot: String,
        engineProxySlot: String?,
        actualProcessName: String
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = applicationContext ?: this,
                instanceId = instanceId,
                component = "activity-process-slot",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to "ACTIVITY_PROCESS_SLOT",
                    "detail" to detail,
                    "engineProcessSlot" to engineProcessSlot,
                    "engineProxySlot" to engineProxySlot.orEmpty(),
                    "actualProcessName" to actualProcessName,
                    "containerActivityClassName" to javaClass.name
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write process slot evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeEngineProxySlotEvidence(
        instanceId: String,
        status: String,
        detail: String,
        engineProxySlot: String,
        launchMode: String?
    ) {
        val normalizedLaunchMode = EngineProxyActivitySlots.normalizeLaunchMode(launchMode)
        val slotLaunchMode = EngineProxyActivitySlots.normalizeLaunchMode(proxyLaunchModeByClassName[engineProxySlot])
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = applicationContext ?: this,
                instanceId = instanceId,
                component = "activity-engine-proxy-slot",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to "ACTIVITY_PROXY_SLOT",
                    "detail" to detail,
                    "engineProxySlot" to engineProxySlot,
                    "requestedLaunchMode" to (normalizedLaunchMode ?: "standard"),
                    "engineProxySlotLaunchMode" to (slotLaunchMode ?: "standard")
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write engine proxy slot evidence for instanceId=$instanceId", error)
        }
    }

    private fun activityTaskController(): EngineActivityTaskController =
        EngineActivityTaskControllers.fileBacked(this)

    private fun restoreActivityTaskState(instanceId: String, stage: String) {
        runCatching {
            val result = activityTaskController().restorePersisted(instanceId)
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                status = result.status,
                stage = stage,
                activityCount = result.activityCount,
                taskCount = result.taskCount,
                detail = result.detail
            )
        }.onFailure { error ->
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                status = "FAIL",
                stage = stage,
                activityCount = 0,
                taskCount = null,
                detail = error.message ?: error.javaClass.name
            )
        }
    }

    private fun persistActivityTaskState(instanceId: String, stage: String) {
        runCatching {
            val result = activityTaskController().persist(instanceId)
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                status = result.status,
                stage = stage,
                activityCount = result.activityCount,
                taskCount = result.taskCount,
                detail = result.detail
            )
        }.onFailure { error ->
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                status = "FAIL",
                stage = stage,
                activityCount = 0,
                taskCount = null,
                detail = error.message ?: error.javaClass.name
            )
        }
    }

    private fun writeActivityTaskStateEvidence(
        instanceId: String,
        status: String,
        stage: String,
        activityCount: Int,
        taskCount: Int?,
        detail: String = ""
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = applicationContext ?: this,
                instanceId = instanceId,
                component = "activity-task-state",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to stage,
                    "detail" to detail,
                    "activityCount" to activityCount.toString(),
                    "taskCount" to (taskCount?.toString() ?: "")
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write activity task-state evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeProxySlotPruneEvidence(
        instanceId: String,
        removedRuntimeRecords: Int,
        liveProxyClasses: Set<String>,
        knownProxyCount: Int
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = applicationContext ?: this,
                instanceId = instanceId,
                component = "activity-runtime-record-prune",
                fields = linkedMapOf(
                    "status" to "PASS",
                    "stage" to "activity-runtime-record-prune",
                    "assignmentAuthority" to "engine-server",
                    "removedRuntimeRecords" to removedRuntimeRecords.toString(),
                    "liveProxyActivityCount" to liveProxyClasses.size.toString(),
                    "liveProxyActivityClasses" to liveProxyClasses.joinToString(","),
                    "knownProxyActivityCount" to knownProxyCount.toString()
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write proxy Activity runtime prune evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeStorageDiagnosticsEvidence(context: Context, result: EngineHostedBootstrapResult) {
        runCatching {
            ContainerStorageDiagnosticsEvidence.write(context, result)
        }.onFailure { error ->
            Log.w(TAG, "Unable to write PR-10 storage diagnostics for instanceId=${result.instanceId}", error)
        }
    }

    private fun scheduleDetailedBootstrapEvidence(
        context: Context,
        result: EngineHostedBootstrapResult
    ) {
        diagnosticsExecutor.schedule(
            {
                writeStorageDiagnosticsEvidence(context, result)
                writeProviderOperationEvidence(context, result)
            },
            DETAILED_EVIDENCE_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun writeProviderOperationEvidence(context: Context, result: EngineHostedBootstrapResult) {
        runCatching {
            ContainerProviderOperationEvidence.writeUnsupportedOperations(context, result)
        }.onFailure { error ->
            Log.w(TAG, "Unable to write provider operation diagnostics for instanceId=${result.instanceId}", error)
        }
    }
}

class ContainerActivityV0 : ContainerActivity()
class ContainerActivityV1 : ContainerActivity()
class ContainerActivityV2 : ContainerActivity()
class ContainerActivityV3 : ContainerActivity()
class ContainerActivityV4 : ContainerActivity()
class ContainerActivityV5 : ContainerActivity()
class ContainerActivityV6 : ContainerActivity()
class ContainerActivityV7 : ContainerActivity()
class ContainerActivityV8 : ContainerActivity()
class ContainerActivityV9 : ContainerActivity()
class ContainerActivityV10 : ContainerActivity()
class ContainerActivityV11 : ContainerActivity()
class ContainerActivityV12 : ContainerActivity()
class ContainerActivityV13 : ContainerActivity()
class ContainerActivityV14 : ContainerActivity()
class ContainerActivityV15 : ContainerActivity()
class ContainerActivityV16 : ContainerActivity()
class ContainerActivityV17 : ContainerActivity()
class ContainerActivityV18 : ContainerActivity()
class ContainerActivityV19 : ContainerActivity()
class ContainerActivityV20 : ContainerActivity()
class ContainerActivityV21 : ContainerActivity()
class ContainerActivityV22 : ContainerActivity()
class ContainerActivityV23 : ContainerActivity()

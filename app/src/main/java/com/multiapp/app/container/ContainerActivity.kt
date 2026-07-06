package com.multiapp.app.container

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File

/**
 * Fixed host entry point for v2 hosted containers.
 *
 * Launched internally by MultiApp with an [EXTRA_INSTANCE_ID] to identify
 * which app-instance to host. Does NOT generate stub APKs, does NOT run a
 * full Virtual AMS, and does NOT touch QQ-Reader hooks or LSPlant/Xposed.
 *
 * On launch, bootstraps the hosted runtime via [HostedRuntimeBootstrap]
 * which creates a [VirtualContextWrapper][com.multiapp.core.loader.VirtualContextWrapper]
 * for the guest app and attempts to instantiate the guest
 * [android.app.Application] class.
 *
 * After successful bootstrap, resolves the guest launcher Activity and maps it
 * to a real host ProxyActivity slot. This starts the move toward
 * ProxyActivity + Instrumentation/ActivityThread virtualization.
 */
class ContainerActivity : Activity() {

    companion object {
        private const val TAG = "ContainerActivity"

        /** Key for the hosted-instance identifier in intent extras. */
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"

        /** Optional key for tracking the install origin. */
        const val EXTRA_INSTALL_ORIGIN = "multiapp.installOrigin"

        /**
         * Experimental Provider authority hook profile switch.
         *
         * Default launches must leave this false so protected-app and normal
         * baseline runs stay hook-free. Debug/diagnostic callers may enable it
         * to validate the VirtualApp/DroidPlugin-style Provider rewrite path.
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
            providerHookEnabled: Boolean = false
        ): Intent {
            return Intent(context, ContainerActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_INSTALL_ORIGIN, installOrigin)
                .putExtra(EXTRA_ENABLE_PROVIDER_HOOK, providerHookEnabled)
        }

        internal fun shouldFinishAfterBootstrap(result: HostedBootstrapResult): Boolean =
            !result.success || result.guestClassLoader == null

        internal fun buildVirtualContextConfig(
            instanceId: String,
            originPackageName: String,
            virtualPackageName: String,
            originApkPath: String,
            dataRoot: String?,
            fallbackDataRoot: File,
            guestClassLoader: ClassLoader,
            applicationLabel: String? = null,
            packageSnapshot: VirtualPackageSnapshot? = null
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
                packageSnapshot = packageSnapshot
            )
        }

        internal fun packageManagerProxyStageResult(result: HostedBootstrapResult): BootstrapResult? =
            result.stageResults.firstOrNull { it.stage == RuntimeStage.PACKAGE_MANAGER_PROXY }

        internal fun packageManagerProxyEvidenceFields(result: BootstrapResult): Map<String, String> {
            return buildMap {
                put("stage", result.stage.name)
                put("status", result.status.name)
                put("message", result.message)
                put("durationMs", result.durationMs.toString())
                result.errorClass?.let { put("errorClass", it) }
                result.errorMessage?.let { put("errorMessage", it) }
                result.rollbackNote?.let { put("rollbackNote", it) }
                result.evidence.forEach { evidence -> put(evidence.key, evidence.value) }
            }
        }

        internal fun resolveNativeLibraryDir(dataRoot: String?): String? {
            if (dataRoot.isNullOrBlank()) return null
            return ContainerRuntimePaths.nativeLibraryDirOrNull(dataRoot)
        }
    }

    private val proxyActivityClassNames by lazy(LazyThreadSafetyMode.NONE) {
        ProxyActivitySlots.classNames(packageName)
    }

    private val proxyLaunchModeByClassName by lazy(LazyThreadSafetyMode.NONE) {
        ProxyActivitySlots.launchModeByClassName(packageName)
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
        val providerHookEnabled = intent.getBooleanExtra(EXTRA_ENABLE_PROVIDER_HOOK, false)
        Log.i(
            TAG,
            "Container launch started: instanceId=$instanceId, " +
                "installOrigin=$installOrigin, providerHookEnabled=$providerHookEnabled"
        )

        // 1. Create persistence dependencies
        val instanceStore = JsonInstanceRecordStore(getInstanceStoreDir())
        val installStore = JsonInstallRecordStore(getInstallStoreDir())
        val instanceManager: InstanceManager = DefaultInstanceManager(
            store = instanceStore,
            dataRootBase = getDataRootDir(),
            installRecordStore = installStore
        )

        // 2. Run bootstrap (Phase 2: includes guest Application creation)
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = installStore,
            hostContext = this,
            providerHookInstallEnabled = providerHookEnabled
        )
        val result = VirtualProcessRuntime.global.bindApplication(instanceId) {
            bootstrap.run(instanceId)
        }
        writePackageManagerProxyEvidence(instanceId, result)

        if (!result.success) {
            Log.e(TAG, "Bootstrap failed for instanceId=$instanceId: ${result.summary.failureReason}")
            writeLaunchEvidence(instanceId, "FAIL", "BOOTSTRAP", result.summary.failureReason ?: "unknown")
            finish()
            return
        }

        val guestClassLoader = result.guestClassLoader
        if (guestClassLoader == null) {
            Log.e(TAG, "Bootstrap succeeded but guestClassLoader is null for instanceId=$instanceId")
            writeLaunchEvidence(instanceId, "FAIL", "CLASS_LOADER", "guestClassLoader is null")
            finish()
            return
        }
        writeStorageDiagnosticsEvidence(result)

        val guestApp = result.guestApplication
        if (guestApp != null) {
            Log.i(TAG, "Guest Application created: ${guestApp.javaClass.name}")
        } else {
            Log.w(TAG, "No guest Application created for instanceId=$instanceId")
        }

        // 3. Launch a real ProxyActivity slot. Instrumentation substitution is the next phase.
        val launcherClassName = result.launcherActivityClassName
        if (launcherClassName != null) {
            val proxyLaunchResult = launchProxyActivity(
                instanceId = instanceId,
                originPackageName = result.originPackageName ?: result.virtualPackageName ?: "",
                guestActivityClassName = launcherClassName,
                launchMode = result.packageSnapshot
                    ?.activities
                    ?.firstOrNull { it.name == launcherClassName || it.targetActivityName == launcherClassName }
                    ?.launchMode
            )
            if (proxyLaunchResult.isFailure) {
                writeLaunchEvidence(
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
                instanceId,
                "PROXY_LAUNCHED",
                "ACTIVITY_PROXY",
                "${proxyRecord.proxyActivityClassName}|${proxyRecord.guestActivityClassName}"
            )
            finish()
            return
        } else {
            Log.w(TAG, "No launcher Activity to launch for instanceId=$instanceId")
            writeLaunchEvidence(instanceId, "FAIL", "LAUNCHER_ACTIVITY", "no launcher Activity")
            finish()
            return
        }

        Log.i(TAG, "Container launch complete: instanceId=$instanceId, success=${result.success}")
        writeLaunchEvidence(instanceId, "SUCCESS", "COMPLETE", result.launcherActivityClassName ?: "")
    }

    private fun launchProxyActivity(
        instanceId: String,
        originPackageName: String,
        guestActivityClassName: String,
        launchMode: String?
    ): Result<com.multiapp.core.model.virtual.VirtualActivityRecord> {
        Log.i(TAG, "Launching proxy Activity for guest: $guestActivityClassName")
        val manager = VirtualActivityManager(
            context = this,
            proxyActivityRegistry = ProxyActivityRegistry(proxyActivityClassNames, proxyLaunchModeByClassName)
        )
        return manager.launchGuestLauncher(
            instanceId = instanceId,
            originPackageName = originPackageName,
            guestActivityClassName = guestActivityClassName,
            launchMode = launchMode
        )
    }

    /** Directory for [JsonInstanceRecordStore] persistence. */
    private fun getInstanceStoreDir(): File =
        ContainerRuntimePaths.instanceStoreDir(this)

    /** Directory for [JsonInstallRecordStore] persistence. */
    private fun getInstallStoreDir(): File =
        ContainerRuntimePaths.installStoreDir(this)

    /** Base directory for instance data roots. */
    private fun getDataRootDir(): File =
        ContainerRuntimePaths.instanceDataRootBase(this)

    private fun writePackageManagerProxyEvidence(
        instanceId: String,
        result: HostedBootstrapResult
    ) {
        val stageResult = packageManagerProxyStageResult(result) ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
                instanceId = instanceId,
                component = "package-manager-proxy",
                fields = packageManagerProxyEvidenceFields(stageResult)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write package-manager-proxy evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeLaunchEvidence(
        instanceId: String,
        status: String,
        stage: String,
        detail: String
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
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

    private fun writeStorageDiagnosticsEvidence(result: HostedBootstrapResult) {
        runCatching {
            ContainerStorageDiagnosticsEvidence.write(this, result)
        }.onFailure { error ->
            Log.w(TAG, "Unable to write PR-10 storage diagnostics for instanceId=${result.instanceId}", error)
        }
    }
}

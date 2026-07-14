package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.loader.ActivityThreadCompat
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.HostedRuntimeBindingFingerprint
import com.multiapp.core.loader.MainLooperApplicationThreadRunner
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.loader.toSummary
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceDataRoot
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.virtual.ResolvedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface HostedRuntimeEngine {
    fun reusableResult(instanceId: String): EngineHostedBootstrapResult?
    fun reusableResult(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): EngineHostedBootstrapResult? = reusableResult(instanceId)
    fun reusableResult(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String? = null
    ): EngineHostedBootstrapResult? = reusableResult(instanceId, providerHookEnabled, processSlot)
    fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean = true,
        processSlot: String? = null
    ): EngineHostedBootstrapResult
    fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String? = null
    ): EngineHostedBootstrapResult = runBootstrap(instanceId, providerHookEnabled, processSlot)

    fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean = true,
        processSlot: String? = null
    ): HostedRuntimeBindOutcome
    fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String? = null
    ): HostedRuntimeBindOutcome = bindApplication(instanceId, providerHookEnabled, processSlot)
}

data class HostedRuntimeBindOutcome(
    val result: EngineHostedBootstrapResult,
    val ranBootstrapOnThisThread: Boolean
)

@Singleton
class DefaultHostedRuntimeEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : HostedRuntimeEngine {
    private val processRuntime: EngineHostedProcessRuntime = DefaultEngineHostedProcessRuntime()
    private val hostContext: Context
        get() = context.applicationContext ?: context

    init {
        EngineRuntimeIpcClients.install(hostContext)
    }

    override fun reusableResult(instanceId: String): EngineHostedBootstrapResult? {
        val fingerprint = bindingFingerprint(
            instanceId = instanceId,
            providerHookEnabled = false,
            processSlot = null,
            effectiveGuestProcessName = null
        ) ?: return null
        return processRuntime.reusableResult(instanceId, fingerprint)
    }

    override fun reusableResult(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): EngineHostedBootstrapResult? = reusableResult(
        instanceId = instanceId,
        providerHookEnabled = providerHookEnabled,
        processSlot = processSlot,
        effectiveGuestProcessName = null
    )

    override fun reusableResult(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String?
    ): EngineHostedBootstrapResult? {
        val fingerprint = bindingFingerprint(
            instanceId,
            providerHookEnabled,
            processSlot,
            effectiveGuestProcessName
        ) ?: return null
        return processRuntime.reusableResult(instanceId, fingerprint)
    }

    override fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): EngineHostedBootstrapResult = runBootstrap(
        instanceId = instanceId,
        providerHookEnabled = providerHookEnabled,
        processSlot = processSlot,
        effectiveGuestProcessName = null
    )

    override fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String?
    ): EngineHostedBootstrapResult = EngineHostedBootstrapResult.fromLoader(
        runBootstrapLoader(instanceId, providerHookEnabled, processSlot, effectiveGuestProcessName)
    )

    private fun runBootstrapLoader(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String?
    ): HostedBootstrapResult {
        val runtimeView = authoritativeRuntime(instanceId, processSlot, effectiveGuestProcessName)
            ?: return authorityFailureResult(
                instanceId,
                processSlot,
                "authoritative engine runtime unavailable or stale"
            )
        val runtime = runtimeView.runtime
        val installRecord = runtime.toInstallRecordOrNull()
            ?: return authorityFailureResult(
                instanceId,
                runtime.processSlot,
                "authoritative package snapshot is incomplete"
            )
        return HostedRuntimeBootstrap(
            instanceManager = ReadOnlyRuntimeInstanceManager(runtime),
            installRecordStore = ReadOnlyRuntimeInstallRecordStore(installRecord),
            hostContext = hostContext,
            providerHookInstallEnabled = providerHookEnabled,
            applicationThreadRunner = MainLooperApplicationThreadRunner(),
            applicationOnCreateInvoker = { application ->
                val activityThread = ActivityThreadCompat.currentActivityThread()
                ActivityThreadCompat.getInstrumentation(activityThread)
                    .callApplicationOnCreate(application)
            },
            processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
            activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
            runtimePublisher = { publishedInstanceId, result ->
                processRuntime.rememberApplication(
                    publishedInstanceId,
                    EngineHostedBootstrapResult.fromLoader(result)
                )
            },
            effectiveGuestProcessName = runtimeView.effectiveGuestProcessName
        ).run(instanceId, runtime.processSlot)
    }

    override fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): HostedRuntimeBindOutcome = bindApplication(
        instanceId = instanceId,
        providerHookEnabled = providerHookEnabled,
        processSlot = processSlot,
        effectiveGuestProcessName = null
    )

    override fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String?
    ): HostedRuntimeBindOutcome {
        val fingerprint = requireNotNull(
            bindingFingerprint(instanceId, providerHookEnabled, processSlot, effectiveGuestProcessName)
        ) {
            "Unable to build hosted runtime binding fingerprint: instanceId=$instanceId"
        }
        return processRuntime.bindApplication(instanceId, fingerprint) {
            EngineHostedBootstrapResult.fromLoader(
                runBootstrapLoader(instanceId, providerHookEnabled, processSlot, effectiveGuestProcessName)
            )
        }
    }

    private fun bindingFingerprint(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?,
        effectiveGuestProcessName: String?
    ): HostedRuntimeBindingFingerprint? {
        val runtimeView = authoritativeRuntime(instanceId, processSlot, effectiveGuestProcessName) ?: return null
        val engineRuntime = runtimeView.runtime
        val snapshot = engineRuntime.packageSnapshot
        val sourceSha256 = snapshot.sourceSha256 ?: return null
        if (snapshot.splitSourceDirs.size != snapshot.splitSha256s.size) return null
        return HostedRuntimeBindingFingerprint(
            instanceId = engineRuntime.instanceId,
            originPackageName = engineRuntime.originPackageName,
            virtualPackageName = engineRuntime.virtualPackageName,
            processSlot = engineRuntime.processSlot,
            dataRoot = File(engineRuntime.dataRoot).absoluteFile.normalize().path,
            versionCode = snapshot.versionCode,
            baseApkPath = File(snapshot.sourceDir).absoluteFile.normalize().path,
            baseApkSha256 = sourceSha256,
            splitApkPaths = snapshot.splitSourceDirs.map { path ->
                File(path).absoluteFile.normalize().path
            },
            splitApkSha256s = snapshot.splitSha256s,
            applicationClassName = snapshot.applicationClassName,
            engineProfile = engineRuntime.profile.name,
            providerHookEnabled = providerHookEnabled,
            effectiveGuestProcessName = runtimeView.effectiveGuestProcessName
        )
    }

    private fun authoritativeRuntime(
        instanceId: String,
        expectedProcessSlot: String?,
        effectiveGuestProcessName: String?
    ): ProcessSpecificHostedRuntimeView? = EngineRuntimeIpcClients.engineQueryRuntimeState(instanceId)
        ?.takeIf { runtime ->
            runtime.instanceId == instanceId &&
                runtime.state != VirtualRuntimeState.STOPPED &&
                runtime.state != VirtualRuntimeState.DEAD
        }
        ?.deriveHostedRuntimeView(expectedProcessSlot, effectiveGuestProcessName)

    private fun authorityFailureResult(
        instanceId: String,
        processSlot: String?,
        message: String
    ): HostedBootstrapResult {
        val failure = BootstrapResult.failed(RuntimeStage.CONFIG, message)
        return HostedBootstrapResult(
            instanceId = instanceId,
            installId = null,
            originPackageName = null,
            processSlot = processSlot,
            originApkPath = null,
            guestClassLoader = null,
            guestApplication = null,
            stageResults = listOf(failure),
            summary = listOf(failure).toSummary(),
            success = false
        )
    }
}

internal data class ProcessSpecificHostedRuntimeView(
    val runtime: VirtualInstanceRuntime,
    val effectiveGuestProcessName: String
)

internal fun VirtualInstanceRuntime.deriveHostedRuntimeView(
    expectedProcessSlot: String?,
    requestedEffectiveGuestProcessName: String?
): ProcessSpecificHostedRuntimeView? {
    val applicationGuestProcessName = packageSnapshot.processName
        .toEffectiveGuestProcessName(originPackageName)
    if (requestedEffectiveGuestProcessName == null) {
        if (!expectedProcessSlot.isNullOrBlank() && processSlot != expectedProcessSlot) return null
        return ProcessSpecificHostedRuntimeView(this, applicationGuestProcessName)
    }

    val effectiveGuestProcessName = requestedEffectiveGuestProcessName
        .trim()
        .takeIf(String::isNotEmpty)
        ?.toEffectiveGuestProcessName(originPackageName)
        ?: return null
    val targetProcessSlot = expectedProcessSlot?.trim()?.takeIf(String::isNotEmpty) ?: return null

    if (effectiveGuestProcessName == applicationGuestProcessName) {
        if (targetProcessSlot != processSlot) return null
        return ProcessSpecificHostedRuntimeView(this, applicationGuestProcessName)
    }
    if (targetProcessSlot == processSlot) return null
    if (effectiveGuestProcessName !in packageSnapshot.declaredComponentGuestProcessNames()) return null

    return ProcessSpecificHostedRuntimeView(
        runtime = copy(
            processSlot = targetProcessSlot,
            processId = null,
            processName = targetProcessSlot
        ),
        effectiveGuestProcessName = effectiveGuestProcessName
    )
}

private fun com.multiapp.core.model.virtual.VirtualPackageSnapshot.declaredComponentGuestProcessNames(): Set<String> =
    processName.toEffectiveGuestProcessName(originPackageName).let { applicationGuestProcessName ->
        sequenceOf(activities, services, receivers, providers)
            .flatten()
            .map { component ->
                component.processName
                    ?.toEffectiveGuestProcessName(originPackageName)
                    ?: applicationGuestProcessName
            }
            .toSet()
    }

private fun String?.toEffectiveGuestProcessName(originPackageName: String): String {
    val normalized = this?.trim()?.takeIf(String::isNotEmpty) ?: return originPackageName
    return if (normalized.startsWith(':')) originPackageName + normalized else normalized
}

private class ReadOnlyRuntimeInstanceManager(
    runtime: VirtualInstanceRuntime
) : InstanceManager {
    private val record = VirtualInstanceRecord(
        instanceId = runtime.instanceId,
        originPackageName = runtime.originPackageName,
        virtualPackageName = runtime.virtualPackageName,
        displayName = runtime.packageSnapshot.applicationLabel,
        dataRoot = runtime.dataRoot,
        compatibilityMode = CompatibilityMode.STANDARD,
        createdAtMs = 0L,
        updatedAtMs = 0L
    )

    override fun createInstance(
        originPackageName: String,
        displayName: String,
        compatibilityMode: CompatibilityMode
    ): Result<VirtualInstanceRecord> = Result.failure(UnsupportedOperationException("read-only runtime snapshot"))

    override fun getInstance(instanceId: String): VirtualInstanceRecord? = record.takeIf { it.instanceId == instanceId }
    override fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord> =
        listOf(record).filter { it.originPackageName == originPackageName }
    override fun listInstances(): List<VirtualInstanceRecord> = listOf(record)
    override fun deleteInstance(instanceId: String): Boolean = false
    override fun updateLaunchState(instanceId: String): VirtualInstanceRecord? = null
    override fun getDataRoot(instanceId: String): InstanceDataRoot? =
        record.takeIf { it.instanceId == instanceId }
            ?.let { InstanceDataRoot.fromBaseDir(it.instanceId, File(it.dataRoot)) }
}

private class ReadOnlyRuntimeInstallRecordStore(
    private val record: InstallRecord
) : InstallRecordStore {
    override fun save(record: InstallRecord): Result<String> =
        Result.failure(UnsupportedOperationException("read-only runtime snapshot"))
    override fun load(packageName: String): InstallRecord? = record.takeIf { it.packageName == packageName }
    override fun listAll(): List<InstallRecord> = listOf(record)
    override fun delete(packageName: String): Boolean = false
}

private fun VirtualInstanceRuntime.toInstallRecordOrNull(): InstallRecord? = runCatching {
    val snapshot = packageSnapshot
    val sourceSha256 = requireNotNull(snapshot.sourceSha256)
    check(snapshot.splitSourceDirs.size == snapshot.splitSha256s.size)
    InstallRecord(
        packageName = originPackageName,
        originApkPath = snapshot.sourceDir,
        originApkSha256 = sourceSha256,
        originCertSha256 = snapshot.originCertSha256.orEmpty(),
        signerSha256Digests = snapshot.signerSha256Digests,
        hasMultipleSigners = snapshot.hasMultipleSigners,
        splitApkPaths = snapshot.splitSourceDirs,
        splitPublicSourceDirs = snapshot.splitPublicSourceDirs,
        splitNames = snapshot.splitNames,
        splitApkSha256s = snapshot.splitSha256s,
        isolatedSplits = snapshot.isolatedSplits,
        versionCode = snapshot.versionCode,
        versionName = snapshot.versionName,
        targetSdk = snapshot.targetSdk,
        minSdk = snapshot.minSdk,
        nativeLibraries = snapshot.nativeLibraries,
        abiList = snapshot.abiList,
        applicationClassName = snapshot.applicationClassName,
        packageLabel = snapshot.applicationLabel,
        applicationMetaData = snapshot.typedMetaData,
        permissions = snapshot.permissions,
        activities = snapshot.activities.map(ResolvedComponent::toInstallComponent),
        services = snapshot.services.map(ResolvedComponent::toInstallComponent),
        receivers = snapshot.receivers.map(ResolvedComponent::toInstallComponent),
        providers = snapshot.providers.map(ResolvedComponent::toInstallComponent),
        installTimeMs = 0L,
        updatedAtMs = 0L
    )
}.getOrNull()

private fun ResolvedComponent.toInstallComponent(): ComponentInfo = ComponentInfo(
    name = name,
    exported = exported,
    permission = permission,
    readPermission = readPermission,
    writePermission = writePermission,
    grantUriPermissions = grantUriPermissions,
    pathPermissions = pathPermissions,
    uriPermissionPatterns = uriPermissionPatterns,
    launchMode = launchMode,
    processName = processName,
    taskAffinity = taskAffinity,
    themeId = themeId,
    metaData = typedMetaData,
    targetActivityName = targetActivityName
)

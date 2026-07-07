package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultVirtualizationEngine @Inject constructor(
    @ApplicationContext context: Context,
    instanceManager: InstanceManager,
    virtualInstallService: VirtualInstallService,
    activityLauncher: EngineActivityLauncher,
    slotStore: EngineRuntimeSlotStore
) : VirtualizationEngine by DefaultVirtualizationEngineCore(
    hostPackageName = context.packageName,
    instanceManager = instanceManager,
    virtualInstallService = virtualInstallService,
    activityLauncher = activityLauncher,
    slotStore = slotStore,
    runtimeRegistry = EngineRuntimeRegistry.global,
    profilePolicy = CompatibilityProfilePolicy(),
    evidenceSessionFactory = { UUID.randomUUID().toString() }
)

internal class DefaultVirtualizationEngineCore(
    private val hostPackageName: String,
    private val instanceManager: InstanceManager,
    private val virtualInstallService: VirtualInstallService,
    private val activityLauncher: EngineActivityLauncher,
    private val slotStore: EngineRuntimeSlotStore = InMemoryEngineRuntimeSlotStore(),
    private val runtimeRegistry: EngineRuntimeRegistry = EngineRuntimeRegistry(),
    private val profilePolicy: CompatibilityProfilePolicy = CompatibilityProfilePolicy(),
    private val evidenceSessionFactory: () -> String = { UUID.randomUUID().toString() }
) : VirtualizationEngine {

    override fun installOrRefreshPackage(originPackageName: String): EngineResult {
        if (originPackageName.isBlank()) {
            return EngineResult.fail(
                operation = OP_INSTALL,
                originPackageName = originPackageName,
                message = "originPackageName must not be blank"
            )
        }
        return if (virtualInstallService.hasInstallRecord(originPackageName)) {
            EngineResult.pass(
                operation = OP_INSTALL,
                originPackageName = originPackageName,
                message = "install record already exists"
            )
        } else {
            EngineResult.unsupported(
                operation = OP_INSTALL,
                originPackageName = originPackageName,
                message = "installOrRefreshPackage requires pre-extracted VirtualApp metadata in the current baseline"
            )
        }
    }

    override fun createInstance(originPackageName: String): EngineResult {
        if (!virtualInstallService.hasInstallRecord(originPackageName)) {
            return EngineResult.fail(
                operation = OP_CREATE,
                originPackageName = originPackageName,
                message = "InstallRecord not found for $originPackageName"
            )
        }
        return instanceManager.createInstance(originPackageName, originPackageName).fold(
            onSuccess = { instance ->
                EngineResult.pass(
                    operation = OP_CREATE,
                    instanceId = instance.instanceId,
                    originPackageName = originPackageName,
                    message = "instance created"
                )
            },
            onFailure = { error ->
                EngineResult.fail(
                    operation = OP_CREATE,
                    originPackageName = originPackageName,
                    message = error.message ?: "createInstance failed"
                )
            }
        )
    }

    override fun launchInstance(request: LaunchInstanceRequest): EngineResult {
        val instance = instanceManager.getInstance(request.instanceId)
            ?: return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = request.instanceId,
                message = "Instance not found: ${request.instanceId}"
            )
        val installRecord = virtualInstallService.getInstallRecord(instance.originPackageName)
            ?: return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "InstallRecord not found for ${instance.originPackageName}"
            )
        val decision = profilePolicy.evaluate(instance.originPackageName, instance.instanceId, request.profile)
        if (!decision.allowed) {
            return EngineResult.unsupported(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "Engine profile ${request.profile} rejected: ${decision.reason}"
            )
        }

        val runtime = runCatching {
            pruneRuntimeSlots()
            buildRuntime(instance, installRecord, request.profile)
        }.getOrElse { error ->
            if (error is EngineRuntimeSlotExhaustedException) {
                return EngineResult.unsupported(
                    operation = OP_LAUNCH,
                    instanceId = instance.instanceId,
                    originPackageName = instance.originPackageName,
                    message = error.message ?: "runtime slot exhausted"
                )
            }
            return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = error.message ?: "runtime slot assignment failed"
            )
        }
        runtimeRegistry.register(runtime)
        activityLauncher.launch(
            EngineLaunchSpec(
                instanceId = instance.instanceId,
                profile = request.profile,
                processSlot = runtime.processSlot,
                proxySlot = runtime.proxySlot,
                evidenceSessionId = runtime.evidenceSessionId,
                providerRoutingEnabled = decision.providerRoutingEnabled
            )
        )
        instanceManager.updateLaunchState(instance.instanceId)
        return EngineResult.pass(
            operation = OP_LAUNCH,
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            message = "launch dispatched through engine",
            runtime = runtime,
            evidence = runtimeRegistry.evidence(instance.instanceId)
        )
    }

    override fun stopInstance(instanceId: String): EngineResult {
        val stopped = runtimeRegistry.stop(instanceId)
        return if (stopped) {
            EngineResult.pass(operation = OP_STOP, instanceId = instanceId, message = "runtime stopped")
        } else {
            EngineResult.partial(operation = OP_STOP, instanceId = instanceId, message = "runtime was not active")
        }
    }

    override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? =
        runtimeRegistry.get(instanceId)

    override fun exportEvidence(instanceId: String): EngineEvidenceReport =
        runtimeRegistry.evidence(instanceId)

    private fun buildRuntime(
        instance: VirtualInstanceRecord,
        installRecord: InstallRecord,
        profile: EngineProfile
    ): VirtualInstanceRuntime {
        val slots = slotStore.assign(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            processCandidates = processSlotCandidates(),
            proxyCandidates = standardProxySlotCandidates()
        )
        return VirtualInstanceRuntime(
            instanceId = instance.instanceId,
            hostPackageName = hostPackageName,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            dataRoot = instance.dataRoot,
            packageSnapshot = buildSnapshot(instance, installRecord),
            profile = profile,
            processSlot = slots.processSlot,
            proxySlot = slots.proxySlot,
            evidenceSessionId = evidenceSessionFactory()
        )
    }

    private fun buildSnapshot(
        instance: VirtualInstanceRecord,
        installRecord: InstallRecord
    ): VirtualPackageSnapshot {
        return VirtualPackageSnapshot(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            applicationLabel = installRecord.packageLabel ?: instance.displayName.ifBlank { instance.originPackageName },
            versionCode = installRecord.versionCode,
            versionName = installRecord.versionName,
            targetSdk = installRecord.targetSdk,
            minSdk = installRecord.minSdk,
            sourceDir = installRecord.originApkPath,
            publicSourceDir = installRecord.originApkPath,
            splitSourceDirs = installRecord.splitApkPaths,
            splitPublicSourceDirs = installRecord.splitPublicSourceDirs.ifEmpty { installRecord.splitApkPaths },
            splitNames = installRecord.splitNames,
            isolatedSplits = installRecord.isolatedSplits,
            dataDir = instance.dataRoot,
            nativeLibraryDir = File(instance.dataRoot, "lib").absolutePath,
            applicationClassName = installRecord.applicationClassName,
            launcherActivityName = installRecord.activities.firstOrNull()?.name,
            activities = installRecord.activities.toResolvedComponents(),
            services = installRecord.services.toResolvedComponents(),
            receivers = installRecord.receivers.toResolvedComponents(),
            providers = installRecord.providers.toResolvedComponents(),
            permissions = installRecord.permissions,
            originCertSha256 = installRecord.originCertSha256
        )
    }

    private fun List<ComponentInfo>.toResolvedComponents(): List<ResolvedComponent> =
        map { component ->
            ResolvedComponent(
                name = component.name,
                exported = component.exported,
                permission = component.permission,
                grantUriPermissions = component.grantUriPermissions
            )
        }

    private fun pruneRuntimeSlots() {
        val validInstanceIds = instanceManager.listInstances().mapTo(linkedSetOf()) { it.instanceId }
        slotStore.prune(validInstanceIds)
    }

    private fun processSlotCandidates(): List<String> =
        (0 until PROCESS_SLOT_COUNT).map { index -> "${hostPackageName}:v$index" }

    private fun standardProxySlotCandidates(): List<String> =
        ProxyActivitySlots.classNames(hostPackageName)
            .filter { className -> ProxyActivitySlots.launchModeByClassName(hostPackageName)[className] == null }

    companion object {
        private const val OP_INSTALL = "installOrRefreshPackage"
        private const val OP_CREATE = "createInstance"
        private const val OP_LAUNCH = "launchInstance"
        private const val OP_STOP = "stopInstance"
        private const val PROCESS_SLOT_COUNT = 8
    }
}

data class EngineLaunchSpec(
    val instanceId: String,
    val profile: EngineProfile,
    val processSlot: String,
    val proxySlot: String,
    val evidenceSessionId: String,
    val providerRoutingEnabled: Boolean
)

fun interface EngineActivityLauncher {
    fun launch(spec: EngineLaunchSpec)
}

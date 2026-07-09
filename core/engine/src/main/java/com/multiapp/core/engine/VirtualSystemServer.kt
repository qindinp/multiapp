package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

interface VirtualSystemServer {
    val runtimeService: VirtualRuntimeService
    val packageService: VirtualPackageService
    val activityService: VirtualActivityService
    val providerService: VirtualProviderService
    val serviceService: VirtualServiceService
    val broadcastService: VirtualBroadcastService
    val storageService: VirtualStorageService
    val nativeService: VirtualNativeService
    val evidenceService: VirtualEvidenceService
}

interface VirtualRuntimeService {
    fun register(runtime: VirtualInstanceRuntime): VirtualInstanceRuntime
    fun get(instanceId: String): VirtualInstanceRuntime?
    fun stop(instanceId: String): Boolean
    fun evidence(instanceId: String): EngineEvidenceReport
    fun registerOperationEvidence(instanceId: String, evidence: EngineOperationEvidence): Boolean
}

interface VirtualEngineSubsystemService {
    val subsystem: EngineSubsystem
}

interface VirtualPackageService : VirtualEngineSubsystemService {
    fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot?
    fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity>
    fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent?

    fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent?
    fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String> = emptySet(),
        dataScheme: String? = null
    ): List<ResolvedComponent>
}
interface VirtualActivityService : VirtualEngineSubsystemService
interface VirtualProviderService : VirtualEngineSubsystemService
interface VirtualServiceService : VirtualEngineSubsystemService
interface VirtualBroadcastService : VirtualEngineSubsystemService
interface VirtualStorageService : VirtualEngineSubsystemService
interface VirtualNativeService : VirtualEngineSubsystemService
interface VirtualEvidenceService : VirtualEngineSubsystemService

enum class VirtualPackageComponentType {
    ACTIVITY,
    SERVICE,
    RECEIVER,
    PROVIDER
}

data class VirtualPackageIdentity(
    val instanceId: String,
    val hostPackageName: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val applicationLabel: String,
    val versionCode: Long,
    val versionName: String
) {
    companion object {
        fun from(runtime: VirtualInstanceRuntime): VirtualPackageIdentity {
            val snapshot = runtime.packageSnapshot
            return VirtualPackageIdentity(
                instanceId = snapshot.instanceId,
                hostPackageName = runtime.hostPackageName,
                originPackageName = snapshot.originPackageName,
                virtualPackageName = snapshot.virtualPackageName,
                applicationLabel = snapshot.applicationLabel,
                versionCode = snapshot.versionCode,
                versionName = snapshot.versionName
            )
        }
    }
}

class RegistryBackedVirtualRuntimeService(
    private val registry: EngineRuntimeRegistry
) : VirtualRuntimeService {
    override fun register(runtime: VirtualInstanceRuntime): VirtualInstanceRuntime =
        registry.register(runtime)

    override fun get(instanceId: String): VirtualInstanceRuntime? =
        registry.get(instanceId)

    override fun stop(instanceId: String): Boolean =
        registry.stop(instanceId)

    override fun evidence(instanceId: String): EngineEvidenceReport =
        registry.evidence(instanceId)

    override fun registerOperationEvidence(instanceId: String, evidence: EngineOperationEvidence): Boolean =
        registry.registerOperationEvidence(instanceId, evidence)
}

class RegistryBackedVirtualPackageService(
    private val runtimeService: VirtualRuntimeService
) : VirtualPackageService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PACKAGE

    override fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot? =
        runtimeService.get(instanceId)?.packageSnapshot

    override fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity> {
        val runtime = runtimeService.get(instanceId)
            ?: return Result.failure(IllegalStateException("runtime_not_found:$instanceId"))
        return Result.success(VirtualPackageIdentity.from(runtime))
    }

    override fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent? {
        if (className.isBlank()) return null
        return queryPackageSnapshot(instanceId)
            ?.components(type)
            ?.firstOrNull { component ->
                component.name == className || component.targetActivityName == className
            }
    }

    override fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent? {
        if (authority.isBlank()) return null
        return queryPackageSnapshot(instanceId)
            ?.providers
            ?.firstOrNull { provider -> authority in provider.authorities }
    }

    override fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String>,
        dataScheme: String?
    ): List<ResolvedComponent> {
        if (action.isBlank()) return emptyList()
        return queryPackageSnapshot(instanceId)
            ?.components(type)
            ?.filter { component ->
                component.resolvedIntentFilters.any { filter ->
                    filter.matches(action = action, categories = categories, dataScheme = dataScheme)
                }
            }
            .orEmpty()
    }
}

class DefaultVirtualSystemServer(
    registry: EngineRuntimeRegistry
) : VirtualSystemServer {
    override val runtimeService: VirtualRuntimeService = RegistryBackedVirtualRuntimeService(registry)
    override val packageService: VirtualPackageService = RegistryBackedVirtualPackageService(runtimeService)
    override val activityService: VirtualActivityService = DefaultVirtualActivityService
    override val providerService: VirtualProviderService = DefaultVirtualProviderService
    override val serviceService: VirtualServiceService = DefaultVirtualServiceService
    override val broadcastService: VirtualBroadcastService = DefaultVirtualBroadcastService
    override val storageService: VirtualStorageService = DefaultVirtualStorageService
    override val nativeService: VirtualNativeService = DefaultVirtualNativeService
    override val evidenceService: VirtualEvidenceService = DefaultVirtualEvidenceService
}

object DefaultVirtualPackageService : VirtualPackageService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PACKAGE

    override fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot? = null

    override fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity> =
        Result.failure(IllegalStateException("runtime_not_found:$instanceId"))

    override fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent? = null

    override fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent? = null

    override fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String>,
        dataScheme: String?
    ): List<ResolvedComponent> = emptyList()
}

object DefaultVirtualActivityService : VirtualActivityService {
    override val subsystem: EngineSubsystem = EngineSubsystem.ACTIVITY
}

object DefaultVirtualProviderService : VirtualProviderService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PROVIDER
}

object DefaultVirtualServiceService : VirtualServiceService {
    override val subsystem: EngineSubsystem = EngineSubsystem.SERVICE
}

object DefaultVirtualBroadcastService : VirtualBroadcastService {
    override val subsystem: EngineSubsystem = EngineSubsystem.BROADCAST
}

object DefaultVirtualStorageService : VirtualStorageService {
    override val subsystem: EngineSubsystem = EngineSubsystem.STORAGE
}

object DefaultVirtualNativeService : VirtualNativeService {
    override val subsystem: EngineSubsystem = EngineSubsystem.NATIVE
}

object DefaultVirtualEvidenceService : VirtualEvidenceService {
    override val subsystem: EngineSubsystem = EngineSubsystem.EVIDENCE
}

private fun VirtualPackageSnapshot.components(type: VirtualPackageComponentType): List<ResolvedComponent> =
    when (type) {
        VirtualPackageComponentType.ACTIVITY -> activities
        VirtualPackageComponentType.SERVICE -> services
        VirtualPackageComponentType.RECEIVER -> receivers
        VirtualPackageComponentType.PROVIDER -> providers
    }

private fun ResolvedIntentFilter.matches(
    action: String,
    categories: Set<String>,
    dataScheme: String?
): Boolean {
    val actionMatches = actions.isEmpty() || action in actions
    if (!actionMatches) return false

    val categoriesMatch = categories.all { category -> category in this.categories }
    if (!categoriesMatch) return false

    return dataScheme == null || dataSchemes.isEmpty() || dataScheme in dataSchemes
}

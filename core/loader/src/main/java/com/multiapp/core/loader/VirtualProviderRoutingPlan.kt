package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Describes how guest ContentProvider authorities should be routed for one
 * hosted virtual instance.
 *
 * VirtualApp/DroidPlugin style engines resolve provider calls through a host
 * declared stub authority. This plan makes that decision explicit so device
 * evidence can tell whether v2 is using the Java pass-through hook path or
 * should fall back to an ActivityThread/provider acquisition proxy.
 */
data class VirtualProviderRoutingPlan(
    val instanceId: String,
    val originPackageName: String,
    val hostPackageName: String?,
    val processSlot: String? = null,
    val providerCount: Int,
    val authorityCount: Int,
    val authorityMap: Map<String, String>,
    val primaryStrategy: ProviderRoutingStrategy,
    val fallbackStrategy: ProviderRoutingStrategy,
    val enabled: Boolean,
    val reason: String,
    val policySummary: VirtualProviderPolicySummary = VirtualProviderPolicySummary.EMPTY
) {
    fun toEvidence(contentResolverHookInstalled: Boolean = false): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("providerRoutingEnabled", enabled.toString(), SOURCE),
        BootstrapEvidence("providerRoutingReason", reason, SOURCE),
        BootstrapEvidence("providerRoutingPrimary", primaryStrategy.name, SOURCE),
        BootstrapEvidence("providerRoutingFallback", fallbackStrategy.name, SOURCE),
        BootstrapEvidence("providerCount", providerCount.toString(), SOURCE),
        BootstrapEvidence("providerAuthorityCount", authorityCount.toString(), SOURCE),
        BootstrapEvidence("providerAuthorityMapSize", authorityMap.size.toString(), SOURCE),
        BootstrapEvidence("providerHostPackage", hostPackageName ?: "", SOURCE),
        BootstrapEvidence("providerRoutingProcessSlot", processSlot.orEmpty(), SOURCE)
    ) + policySummary.toEvidence() + VirtualProviderOperationCapability.toEvidence(
        plan = this,
        contentResolverHookInstalled = contentResolverHookInstalled
    )

    companion object {
        private const val SOURCE = "VirtualProviderRoutingPlan"
    }
}

object VirtualProviderOperationCapability {
    private const val SOURCE = "VirtualProviderOperationCapability"
    private const val ROUTED_BY_STUB_PROVIDER = "ROUTED_BY_STUB_PROVIDER"
    private const val ROUTED_BY_CONTENT_RESOLVER_HOOK = "ROUTED_BY_CONTENT_RESOLVER_HOOK"
    private const val CONTENT_RESOLVER_HOOK_DISABLED = "CONTENT_RESOLVER_HOOK_DISABLED"
    private const val CONTENT_RESOLVER_HOOK_NOT_INSTALLED = "CONTENT_RESOLVER_HOOK_NOT_INSTALLED"
    private const val NO_URI_REWRITE_REQUIRED = "NO_URI_REWRITE_REQUIRED"
    private const val ROUTING_DISABLED = "ROUTING_DISABLED"

    private val stubProviderOperations = listOf(
        "query",
        "getType",
        "insert",
        "update",
        "delete",
        "call",
        "bulkInsert",
        "openFile",
        "openAssetFile",
        "openTypedAssetFile"
    )

    private val contentResolverHookOperations = listOf(
        "openFileDescriptor",
        "openAssetFileDescriptor",
        "openTypedAssetFileDescriptor",
        "notifyChange",
        "registerContentObserver",
        "grantUriPermission",
        "revokeUriPermission",
        "canonicalize",
        "uncanonicalize"
    )

    fun toEvidence(
        plan: VirtualProviderRoutingPlan,
        contentResolverHookInstalled: Boolean = false
    ): List<BootstrapEvidence> {
        val stubStatus = if (plan.enabled) ROUTED_BY_STUB_PROVIDER else ROUTING_DISABLED
        val hookStatus = when {
            !plan.enabled -> ROUTING_DISABLED
            plan.primaryStrategy != ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK ->
                CONTENT_RESOLVER_HOOK_DISABLED
            contentResolverHookInstalled ->
                ROUTED_BY_CONTENT_RESOLVER_HOOK
            else -> CONTENT_RESOLVER_HOOK_NOT_INSTALLED
        }
        val observerUnregisterStatus = when {
            !plan.enabled -> ROUTING_DISABLED
            plan.primaryStrategy != ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK ->
                CONTENT_RESOLVER_HOOK_DISABLED
            contentResolverHookInstalled -> NO_URI_REWRITE_REQUIRED
            else -> CONTENT_RESOLVER_HOOK_NOT_INSTALLED
        }
        return stubProviderOperations.map { operation ->
            BootstrapEvidence(operationStatusKey(operation), stubStatus, SOURCE)
        } + contentResolverHookOperations.map { operation ->
            BootstrapEvidence(operationStatusKey(operation), hookStatus, SOURCE)
        } + BootstrapEvidence(
            operationStatusKey("unregisterContentObserver"),
            observerUnregisterStatus,
            SOURCE
        )
    }

    private fun operationStatusKey(operation: String): String =
        "providerOperation${operation.replaceFirstChar { it.uppercaseChar() }}Status"

    private fun operationReasonKey(operation: String): String =
        "providerOperation${operation.replaceFirstChar { it.uppercaseChar() }}Reason"
}

enum class ProviderRoutingStrategy {
    NONE,
    CONTENT_RESOLVER_PASS_THROUGH_HOOK,
    ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY
}

class VirtualProviderRoutingPlanFactory(
    private val authorityMapFactoryBuilder: (String) -> VirtualProviderAuthorityMapFactory = {
        VirtualProviderAuthorityMapFactory(it)
    }
) {
    fun create(
        snapshot: VirtualPackageSnapshot,
        hostPackageName: String?,
        processSlot: String? = null,
        passThroughHookAllowed: Boolean = false
    ): VirtualProviderRoutingPlan {
        val authorities = snapshot.providers.flatMap { it.authorities }.distinct()
        val policySummary = VirtualProviderPolicySummary.fromProviders(snapshot.providers)
        if (snapshot.providers.isEmpty() || authorities.isEmpty()) {
            return VirtualProviderRoutingPlan(
                instanceId = snapshot.instanceId,
                originPackageName = snapshot.originPackageName,
                hostPackageName = hostPackageName,
                processSlot = processSlot,
                providerCount = snapshot.providers.size,
                authorityCount = authorities.size,
                authorityMap = emptyMap(),
                primaryStrategy = ProviderRoutingStrategy.NONE,
                fallbackStrategy = ProviderRoutingStrategy.NONE,
                enabled = false,
                reason = "NO_GUEST_PROVIDERS",
                policySummary = policySummary
            )
        }

        if (hostPackageName.isNullOrBlank()) {
            return VirtualProviderRoutingPlan(
                instanceId = snapshot.instanceId,
                originPackageName = snapshot.originPackageName,
                hostPackageName = hostPackageName,
                processSlot = processSlot,
                providerCount = snapshot.providers.size,
                authorityCount = authorities.size,
                authorityMap = emptyMap(),
                primaryStrategy = ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY,
                fallbackStrategy = ProviderRoutingStrategy.NONE,
                enabled = false,
                reason = "HOST_PACKAGE_UNAVAILABLE",
                policySummary = policySummary
            )
        }

        val authorityMap = authorityMapFactoryBuilder(hostPackageName).create(snapshot)
        val primary = if (passThroughHookAllowed) {
            ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK
        } else {
            ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY
        }
        val fallback = if (primary == ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK) {
            ProviderRoutingStrategy.ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY
        } else {
            ProviderRoutingStrategy.NONE
        }

        return VirtualProviderRoutingPlan(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            hostPackageName = hostPackageName,
            processSlot = processSlot,
            providerCount = snapshot.providers.size,
            authorityCount = authorities.size,
            authorityMap = authorityMap,
            primaryStrategy = primary,
            fallbackStrategy = fallback,
            enabled = authorityMap.isNotEmpty(),
            reason = if (authorityMap.isNotEmpty()) "AUTHORITY_MAP_READY" else "AUTHORITY_MAP_EMPTY",
            policySummary = policySummary
        )
    }
}

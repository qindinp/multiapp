package com.multiapp.core.loader

import android.app.Application
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Installs same-process guest ContentProviders after Application attach and
 * before Application.onCreate(), matching Android's bindApplication ordering.
 */
class GuestProviderPreinstaller(
    private val providerRuntime: VirtualProviderRuntime = VirtualProviderRuntime.global,
    private val providerManagerFactory: (hostPackageName: String, processSlot: String?) -> VirtualProviderManager =
        { hostPackageName, processSlot -> VirtualProviderManager(hostPackageName, processSlot = processSlot) }
) {
    fun preinstall(request: GuestProviderPreinstallRequest): GuestProviderPreinstallResult {
        val providers = request.snapshot.providers
        val effectiveGuestProcessName = request.config.effectiveGuestProcessName
        if (providers.isEmpty()) {
            return GuestProviderPreinstallResult(
                status = GuestProviderPreinstallStatus.SKIPPED,
                effectiveGuestProcessName = effectiveGuestProcessName,
                totalProviderCount = 0,
                skippedProviderCount = 0,
                skippedReasons = listOf("NO_PROVIDERS")
            )
        }

        val currentProcessProviders = providers.filter { provider ->
            provider.effectiveProcessName(request.snapshot) == effectiveGuestProcessName
        }
        val skippedProviders = providers
            .filterNot { provider -> provider.effectiveProcessName(request.snapshot) == effectiveGuestProcessName }
            .map { provider ->
                GuestProviderPreinstallSkippedProvider(
                    providerClassName = provider.name,
                    authorities = provider.authorities,
                    declaredProcessName = provider.processName,
                    effectiveProcessName = provider.effectiveProcessName(request.snapshot),
                    reason = GuestProviderPreinstallSkipReason.DIFFERENT_GUEST_PROCESS
                )
            }
        if (currentProcessProviders.isEmpty()) {
            return GuestProviderPreinstallResult(
                status = GuestProviderPreinstallStatus.SKIPPED,
                effectiveGuestProcessName = effectiveGuestProcessName,
                totalProviderCount = providers.size,
                skippedProviderCount = skippedProviders.size,
                skippedReasons = skippedProviders.map { it.reason.name }.distinct(),
                skippedProviders = skippedProviders
            )
        }

        val manager = providerManagerFactory(request.hostPackageName, request.config.processSlot)
        val installed = mutableListOf<String>()
        val cached = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val failedReasons = mutableListOf<String>()
        var attempted = 0
        var installedProviderCount = 0
        var cachedProviderCount = 0
        var failedProviderCount = 0

        currentProcessProviders.forEach { provider ->
            requireGuestThreadContextClassLoader(request, provider, "before")
            attempted += 1
            val authorities = provider.authorities.filter { it.isNotBlank() }
            val primaryAuthority = authorities.firstOrNull()
            val resolution = primaryAuthority?.let { manager.resolve(request.snapshot, it) }
            if (resolution == null) {
                failedProviderCount += 1
                failed += authorities
                failedReasons += "${primaryAuthority.orEmpty()}:RESOLUTION_NOT_FOUND"
                return@forEach
            }
            val result = providerRuntime.getOrCreate(
                VirtualProviderCreateRequest(
                    resolution = resolution,
                    guestContext = request.application,
                    guestClassLoader = request.guestClassLoader,
                    config = request.config
                )
            )
            requireGuestThreadContextClassLoader(request, provider, "after")
            when (result) {
                is VirtualProviderRuntimeResult.Created -> {
                    installedProviderCount += 1
                    installed += authorities
                }
                is VirtualProviderRuntimeResult.Cached -> {
                    cachedProviderCount += 1
                    cached += authorities
                }
                is VirtualProviderRuntimeResult.CreateFailed -> {
                    failedProviderCount += 1
                    failed += authorities
                    failedReasons += result.resolution.guestAuthority + ":" +
                        (result.error.message ?: result.error.javaClass.name)
                }
                is VirtualProviderRuntimeResult.AttachFailed -> {
                    failedProviderCount += 1
                    failed += authorities
                    failedReasons += result.resolution.guestAuthority + ":" +
                        (result.error.message ?: result.error.javaClass.name)
                }
            }
        }

        val status = when {
            failedProviderCount == 0 -> GuestProviderPreinstallStatus.PASS
            installedProviderCount == 0 && cachedProviderCount == 0 -> GuestProviderPreinstallStatus.FAILED
            else -> GuestProviderPreinstallStatus.PARTIAL
        }
        return GuestProviderPreinstallResult(
            status = status,
            effectiveGuestProcessName = effectiveGuestProcessName,
            totalProviderCount = providers.size,
            attemptedProviderCount = attempted,
            installedProviderCount = installedProviderCount,
            cachedProviderCount = cachedProviderCount,
            failedProviderCount = failedProviderCount,
            skippedProviderCount = skippedProviders.size,
            installedAuthorities = installed,
            cachedAuthorities = cached,
            failedAuthorities = failed,
            failedReasons = failedReasons,
            skippedReasons = skippedProviders.map { it.reason.name }.distinct(),
            skippedProviders = skippedProviders
        )
    }

    private fun requireGuestThreadContextClassLoader(
        request: GuestProviderPreinstallRequest,
        provider: ResolvedComponent,
        phase: String
    ) {
        check(Thread.currentThread().contextClassLoader === request.guestClassLoader) {
            "Guest thread context ClassLoader changed $phase Provider ${provider.name} preinstall"
        }
    }

    private fun ResolvedComponent.effectiveProcessName(snapshot: VirtualPackageSnapshot): String =
        normalizeGuestProcessName(processName ?: snapshot.processName, snapshot.originPackageName)

    private fun normalizeGuestProcessName(processName: String?, packageName: String): String {
        val normalized = processName?.trim()?.takeIf { it.isNotEmpty() } ?: return packageName
        return if (normalized.startsWith(":")) packageName + normalized else normalized
    }
}

data class GuestProviderPreinstallRequest(
    val hostPackageName: String,
    val snapshot: VirtualPackageSnapshot,
    val application: Application,
    val guestClassLoader: ClassLoader,
    val config: VirtualContextConfig
)

data class GuestProviderPreinstallResult(
    val status: GuestProviderPreinstallStatus,
    val effectiveGuestProcessName: String,
    val totalProviderCount: Int,
    val attemptedProviderCount: Int = 0,
    val installedProviderCount: Int = 0,
    val cachedProviderCount: Int = 0,
    val failedProviderCount: Int = 0,
    val skippedProviderCount: Int = 0,
    val installedAuthorities: List<String> = emptyList(),
    val cachedAuthorities: List<String> = emptyList(),
    val failedAuthorities: List<String> = emptyList(),
    val failedReasons: List<String> = emptyList(),
    val skippedReasons: List<String> = emptyList(),
    val skippedProviders: List<GuestProviderPreinstallSkippedProvider> = emptyList()
) {
    fun toEvidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("providerPreinstallStatus", status.name),
        BootstrapEvidence("providerPreinstallEffectiveGuestProcessName", effectiveGuestProcessName),
        BootstrapEvidence("providerPreinstallTotalCount", totalProviderCount.toString()),
        BootstrapEvidence("providerPreinstallAttemptedCount", attemptedProviderCount.toString()),
        BootstrapEvidence("providerPreinstallInstalledCount", installedProviderCount.toString()),
        BootstrapEvidence("providerPreinstallCachedCount", cachedProviderCount.toString()),
        BootstrapEvidence("providerPreinstallFailedCount", failedProviderCount.toString()),
        BootstrapEvidence("providerPreinstallSkippedCount", skippedProviderCount.toString()),
        BootstrapEvidence("providerPreinstallInstalledAuthorities", installedAuthorities.joinToString(",")),
        BootstrapEvidence("providerPreinstallCachedAuthorities", cachedAuthorities.joinToString(",")),
        BootstrapEvidence("providerPreinstallFailedAuthorities", failedAuthorities.joinToString(",")),
        BootstrapEvidence("providerPreinstallFailedReasons", failedReasons.joinToString(",")),
        BootstrapEvidence("providerPreinstallSkippedReasons", skippedReasons.joinToString(",")),
        BootstrapEvidence(
            "providerPreinstallSkippedProviders",
            skippedProviders.joinToString(";") { skipped ->
                "${skipped.providerClassName}@${skipped.effectiveProcessName}:" +
                    "${skipped.reason.name}[${skipped.authorities.joinToString("|")}]"
            }
        )
    )
}

data class GuestProviderPreinstallSkippedProvider(
    val providerClassName: String,
    val authorities: List<String>,
    val declaredProcessName: String?,
    val effectiveProcessName: String,
    val reason: GuestProviderPreinstallSkipReason
)

enum class GuestProviderPreinstallSkipReason {
    DIFFERENT_GUEST_PROCESS
}

enum class GuestProviderPreinstallStatus {
    PASS,
    PARTIAL,
    FAILED,
    SKIPPED
}

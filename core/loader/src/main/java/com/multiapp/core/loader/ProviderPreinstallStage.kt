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
        if (providers.isEmpty()) {
            return GuestProviderPreinstallResult(
                status = GuestProviderPreinstallStatus.SKIPPED,
                totalProviderCount = 0,
                skippedProviderCount = 0,
                skippedReasons = listOf("NO_PROVIDERS")
            )
        }

        val sameProcessProviders = providers.filter { it.runsInApplicationProcess(request.snapshot) }
        val skippedProcessProviders = providers.size - sameProcessProviders.size
        if (sameProcessProviders.isEmpty()) {
            return GuestProviderPreinstallResult(
                status = GuestProviderPreinstallStatus.SKIPPED,
                totalProviderCount = providers.size,
                skippedProviderCount = skippedProcessProviders,
                skippedReasons = listOf("NO_SAME_PROCESS_PROVIDERS")
            )
        }

        val manager = providerManagerFactory(request.hostPackageName, request.config.processSlot)
        val installed = mutableListOf<String>()
        val cached = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val failedReasons = mutableListOf<String>()
        var attempted = 0

        sameProcessProviders.forEach { provider ->
            provider.authorities.forEach { authority ->
                attempted += 1
                val resolution = manager.resolve(request.snapshot, authority)
                if (resolution == null) {
                    failed += authority
                    failedReasons += "$authority:RESOLUTION_NOT_FOUND"
                    return@forEach
                }
                when (val result = providerRuntime.getOrCreate(
                    VirtualProviderCreateRequest(
                        resolution = resolution,
                        guestContext = request.application,
                        guestClassLoader = request.guestClassLoader,
                        config = request.config
                    )
                )) {
                    is VirtualProviderRuntimeResult.Created -> installed += result.resolution.guestAuthority
                    is VirtualProviderRuntimeResult.Cached -> cached += result.resolution.guestAuthority
                    is VirtualProviderRuntimeResult.CreateFailed -> {
                        failed += result.resolution.guestAuthority
                        failedReasons += result.resolution.guestAuthority + ":" +
                            (result.error.message ?: result.error.javaClass.name)
                    }
                    is VirtualProviderRuntimeResult.AttachFailed -> {
                        failed += result.resolution.guestAuthority
                        failedReasons += result.resolution.guestAuthority + ":" +
                            (result.error.message ?: result.error.javaClass.name)
                    }
                }
            }
        }

        val status = when {
            failed.isEmpty() -> GuestProviderPreinstallStatus.PASS
            installed.isEmpty() && cached.isEmpty() -> GuestProviderPreinstallStatus.FAILED
            else -> GuestProviderPreinstallStatus.PARTIAL
        }
        return GuestProviderPreinstallResult(
            status = status,
            totalProviderCount = providers.size,
            attemptedProviderCount = attempted,
            installedProviderCount = installed.size,
            cachedProviderCount = cached.size,
            failedProviderCount = failed.size,
            skippedProviderCount = skippedProcessProviders,
            installedAuthorities = installed,
            cachedAuthorities = cached,
            failedAuthorities = failed,
            failedReasons = failedReasons
        )
    }

    private fun ResolvedComponent.runsInApplicationProcess(snapshot: VirtualPackageSnapshot): Boolean {
        val providerProcess = processName?.takeIf { it.isNotBlank() } ?: return true
        val appProcess = snapshot.processName?.takeIf { it.isNotBlank() } ?: snapshot.originPackageName
        return providerProcess == appProcess
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
    val skippedReasons: List<String> = emptyList()
) {
    fun toEvidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("providerPreinstallStatus", status.name),
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
        BootstrapEvidence("providerPreinstallSkippedReasons", skippedReasons.joinToString(","))
    )
}

enum class GuestProviderPreinstallStatus {
    PASS,
    PARTIAL,
    FAILED,
    SKIPPED
}

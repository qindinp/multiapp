package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

fun interface VirtualPackageManagerGlobalInstallAction {
    fun install(
        hostContext: Context?,
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int
    ): VirtualPackageManagerGlobalInstallResult
}

class VirtualPackageManagerGlobalInstaller(
    private val bridge: ActivityThreadPackageManagerBridge = ReflectionActivityThreadPackageManagerBridge()
) : VirtualPackageManagerGlobalInstallAction {

    override fun install(
        hostContext: Context?,
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int
    ): VirtualPackageManagerGlobalInstallResult {
        val readResult = bridge.readCurrentPackageManager()
        VirtualPackageManagerServiceRegistry.register(snapshot)
        val original = readResult.packageManager
        if (original == null) {
            return baseResult(
                snapshot = snapshot,
                runtimeUid = runtimeUid,
                readResult = readResult,
                status = VirtualPackageManagerGlobalInstallStatus.DEGRADED,
                sPackageManagerPatched = false,
                proxyClass = null,
                applicationPatchResults = emptyList(),
                degradedReasons = listOf(readResult.skippedReason ?: "S_PACKAGE_MANAGER_UNAVAILABLE")
            )
        }

        val proxy = runCatching {
            bridge.createProxy(original, snapshot, runtimeUid)
        }.getOrElse { error ->
            return baseResult(
                snapshot = snapshot,
                runtimeUid = runtimeUid,
                readResult = readResult,
                status = VirtualPackageManagerGlobalInstallStatus.DEGRADED,
                sPackageManagerPatched = false,
                proxyClass = null,
                applicationPatchResults = emptyList(),
                degradedReasons = listOf("PROXY_CREATE_FAILED:${error.javaClass.simpleName}")
            )
        }

        val globalPatch = bridge.writePackageManager(proxy)
        val applicationPatchResult = if (globalPatch.patched) {
            runCatching { bridge.patchApplicationPackageManagers(hostContext, proxy) }
        } else {
            Result.success(emptyList())
        }
        val applicationPatchResults = applicationPatchResult.getOrElse { emptyList() }
        val degradedReasons = buildList {
            if (!globalPatch.patched) add(globalPatch.skippedReason ?: "S_PACKAGE_MANAGER_SET_FAILED")
            applicationPatchResult.exceptionOrNull()?.let { error ->
                add("APPLICATION_PM_PATCH_FAILED:${error.javaClass.simpleName}")
            }
            applicationPatchResults
                .filterNot { it.patched }
                .mapTo(this) { "${it.target}:${it.skippedReason ?: "PATCH_SKIPPED"}" }
        }
        val status = if (globalPatch.patched && degradedReasons.isEmpty()) {
            VirtualPackageManagerGlobalInstallStatus.INSTALLED
        } else {
            VirtualPackageManagerGlobalInstallStatus.DEGRADED
        }

        return baseResult(
            snapshot = snapshot,
            runtimeUid = runtimeUid,
            readResult = readResult,
            status = status,
            sPackageManagerPatched = globalPatch.patched,
            proxyClass = proxy.javaClass.name,
            applicationPatchResults = applicationPatchResults,
            degradedReasons = degradedReasons
        )
    }

    private fun baseResult(
        snapshot: VirtualPackageSnapshot,
        runtimeUid: Int,
        readResult: ActivityThreadPackageManagerReadResult,
        status: VirtualPackageManagerGlobalInstallStatus,
        sPackageManagerPatched: Boolean,
        proxyClass: String?,
        applicationPatchResults: List<ActivityThreadPackageManagerPatchResult>,
        degradedReasons: List<String>,
        skippedReasons: List<String> = emptyList()
    ): VirtualPackageManagerGlobalInstallResult = VirtualPackageManagerGlobalInstallResult(
        status = status,
        instanceId = snapshot.instanceId,
        originPackageName = snapshot.originPackageName,
        virtualPackageName = snapshot.virtualPackageName,
        runtimeUid = runtimeUid,
        sPackageManagerRead = readResult.packageManager != null,
        sPackageManagerPatched = sPackageManagerPatched,
        ipackageManagerInterface = readResult.interfaceName,
        originalPackageManagerClass = readResult.packageManagerClassName,
        proxyClass = proxyClass,
        applicationPackageManagerPatchResults = applicationPatchResults,
        degradedReasons = degradedReasons,
        skippedReasons = skippedReasons.ifEmpty { listOfNotNull(readResult.skippedReason).filter { readResult.packageManager == null } }
    )
}

enum class VirtualPackageManagerGlobalInstallStatus {
    INSTALLED,
    DEGRADED,
    SKIPPED
}

data class VirtualPackageManagerGlobalInstallResult(
    val status: VirtualPackageManagerGlobalInstallStatus,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val runtimeUid: Int,
    val sPackageManagerRead: Boolean,
    val sPackageManagerPatched: Boolean,
    val ipackageManagerInterface: String? = null,
    val originalPackageManagerClass: String? = null,
    val proxyClass: String? = null,
    val applicationPackageManagerPatchResults: List<ActivityThreadPackageManagerPatchResult> = emptyList(),
    val degradedReasons: List<String> = emptyList(),
    val skippedReasons: List<String> = emptyList()
) {
    val applicationPackageManagerPatchedCount: Int
        get() = applicationPackageManagerPatchResults.count { it.patched }

    fun toEvidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("instanceId", instanceId, SOURCE),
        BootstrapEvidence("originPackageName", originPackageName, SOURCE),
        BootstrapEvidence("virtualPackageName", virtualPackageName, SOURCE),
        BootstrapEvidence("runtimeUid", runtimeUid.toString(), SOURCE),
        BootstrapEvidence("globalPmsProxyEnabled", sPackageManagerPatched.toString(), SOURCE),
        BootstrapEvidence("sPackageManagerRead", sPackageManagerRead.toString(), SOURCE),
        BootstrapEvidence("sPackageManagerPatched", sPackageManagerPatched.toString(), SOURCE),
        BootstrapEvidence("ipackageManagerInterface", ipackageManagerInterface.orEmpty(), SOURCE),
        BootstrapEvidence("originalPackageManagerClass", originalPackageManagerClass.orEmpty(), SOURCE),
        BootstrapEvidence("proxyClass", proxyClass.orEmpty(), SOURCE),
        BootstrapEvidence("applicationPackageManagerPatchedCount", applicationPackageManagerPatchedCount.toString(), SOURCE),
        BootstrapEvidence("applicationPackageManagerPatchedTargets", patchedTargets(), SOURCE),
        BootstrapEvidence("virtualizedQueryFamilies", QUERY_FAMILIES, SOURCE),
        BootstrapEvidence("globalInterceptedMethods", INTERCEPTED_METHODS, SOURCE),
        BootstrapEvidence("uidAggregateVirtualizationEnabled", "true", SOURCE),
        BootstrapEvidence("uidAggregateVirtualizationMode", UID_AGGREGATE_VIRTUALIZATION_MODE, SOURCE),
        BootstrapEvidence("deferredToLocalWrapperMethods", DEFERRED_TO_LOCAL_WRAPPER_METHODS, SOURCE),
        BootstrapEvidence("degradedReasons", degradedReasons.joinToString(";"), SOURCE),
        BootstrapEvidence("skippedReasons", skippedReasons.joinToString(";"), SOURCE),
        BootstrapEvidence("localWrapperFallbackAvailable", "true", SOURCE)
    )

    private fun patchedTargets(): String = applicationPackageManagerPatchResults
        .filter { it.patched }
        .joinToString(",") { it.target }

    companion object {
        private const val SOURCE = "VirtualPackageManagerGlobalInstaller"
        private const val QUERY_FAMILIES = "package,application,component,intent,permission,uid"
        private const val INTERCEPTED_METHODS =
            "getPackageInfo,getPackageInfoVersioned,getApplicationInfo,getActivityInfo,getServiceInfo," +
                "getReceiverInfo,getProviderInfo,resolveContentProvider,queryIntentActivities,resolveIntent," +
                "resolveActivity,queryIntentServices,resolveService,queryIntentReceivers," +
                "queryIntentContentProviders,getInstalledPackages,getInstalledApplications,checkPermission," +
                "getPackagesHoldingPermissions,getPackageUid,getPackagesForUid,getNameForUid," +
                "queryContentProviders,isInstantApp"
        private const val DEFERRED_TO_LOCAL_WRAPPER_METHODS =
            "getPermissionControllerPackageName,buildRequestPermissionsIntent,shouldShowRequestPermissionRationale"
        private const val UID_AGGREGATE_VIRTUALIZATION_MODE = "merge-packages-preserve-name"
    }
}

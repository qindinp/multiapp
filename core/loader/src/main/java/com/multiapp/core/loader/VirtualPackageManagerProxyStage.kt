package com.multiapp.core.loader

import android.content.Context
import android.os.Process

fun interface NotificationPackageProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

fun interface LauncherAppsPackageProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

fun interface ClipboardPackageProxyInstallAction {
    fun install(
        sourcePackages: Collection<String>,
        hostPackageName: String
    ): VirtualClipboardServiceProxyInstallResult
}

fun interface AppOpsPackageProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

fun interface AppOpsServiceManagerProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

fun interface UriGrantsServiceManagerProxyInstallAction {
    fun install(): Boolean
}

fun interface ContentProviderIdentityProxyInstallAction {
    fun install(
        sourcePackages: Collection<String>,
        hostPackageName: String,
        runtimeUid: Int
    ): VirtualContentProviderIdentityProxyInstallResult
}

fun interface StorageManagerProxyInstallAction {
    fun install(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        hostPackageName: String,
        dataRoot: String,
        processSlot: String?
    ): VirtualStorageManagerProxyInstallResult
}

class VirtualPackageManagerProxyStage(
    private val hostContext: Context?,
    private val installer: VirtualPackageManagerGlobalInstallAction = VirtualPackageManagerGlobalInstaller(),
    private val notificationPackageProxyInstaller: NotificationPackageProxyInstallAction =
        NotificationPackageProxyInstallAction { sourcePackages, hostPackageName ->
            IntentRemapDiagnostics.installNotificationManagerPackageProxy(sourcePackages, hostPackageName)
        },
    private val launcherAppsPackageProxyInstaller: LauncherAppsPackageProxyInstallAction =
        LauncherAppsPackageProxyInstallAction { sourcePackages, hostPackageName ->
            VirtualLauncherAppsServiceProxy.install(hostContext, sourcePackages, hostPackageName)
        },
    private val clipboardPackageProxyInstaller: ClipboardPackageProxyInstallAction =
        ClipboardPackageProxyInstallAction { sourcePackages, hostPackageName ->
            VirtualClipboardServiceProxy.installDetailed(hostContext, sourcePackages, hostPackageName)
        },
    private val appOpsPackageProxyInstaller: AppOpsPackageProxyInstallAction =
        AppOpsPackageProxyInstallAction { sourcePackages, hostPackageName ->
            IntentRemapDiagnostics.installAppOpsManagerPackageProxy(hostContext, sourcePackages, hostPackageName)
        },
    private val appOpsServiceManagerProxyInstaller: AppOpsServiceManagerProxyInstallAction =
        AppOpsServiceManagerProxyInstallAction { sourcePackages, hostPackageName ->
            IntentRemapDiagnostics.installAppOpsServiceManagerPackageProxy(sourcePackages, hostPackageName)
        },
    private val uriGrantsServiceManagerProxyInstaller: UriGrantsServiceManagerProxyInstallAction =
        UriGrantsServiceManagerProxyInstallAction(VirtualUriGrantsServiceProxy::install),
    private val contentProviderIdentityProxyInstaller: ContentProviderIdentityProxyInstallAction =
        ContentProviderIdentityProxyInstallAction(VirtualContentProviderIdentityProxy::install),
    private val storageManagerProxyInstaller: StorageManagerProxyInstallAction =
        StorageManagerProxyInstallAction { instanceId, origin, virtual, host, dataRoot, processSlot ->
            VirtualStorageManagerServiceProxy.installDetailed(
                context = hostContext,
                instanceId = instanceId,
                originPackageName = origin,
                virtualPackageName = virtual,
                hostPackageName = host,
                dataRoot = dataRoot,
                processSlot = processSlot
            )
        },
    private val runtimeUidProvider: () -> Int = {
        RuntimeUidCompat.resolve(
            runCatching { hostContext?.applicationInfo?.uid }.getOrNull()
        )
    },
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val snapshot = input.packageSnapshot
        if (snapshot == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                    message = "Package snapshot is required before package manager proxy install",
                    durationMs = clock() - startMs
                )
            )
        }

        val runtimeUid = runtimeUidProvider()
        VirtualAppOpsRuntimeBindings.bindActive(
            instanceId = snapshot.instanceId,
            packageNames = listOf(snapshot.originPackageName, snapshot.virtualPackageName)
        )
        val installResult = installer.install(hostContext, snapshot, runtimeUid)
        val notificationProxyResult = installNotificationPackageProxy(snapshot)
        val launcherAppsProxyResult = installLauncherAppsPackageProxy(snapshot)
        val clipboardProxyResult = installClipboardPackageProxy(snapshot)
        val appOpsProxyResult = installAppOpsPackageProxy(snapshot)
        val appOpsServiceManagerProxyResult = installAppOpsServiceManagerPackageProxy(snapshot)
        val uriGrantsServiceManagerProxyResult = installUriGrantsServiceManagerProxy(snapshot)
        val contentProviderIdentityProxyResult = installContentProviderIdentityProxy(snapshot, runtimeUid)
        val storageManagerProxyResult = installStorageManagerProxy(input, snapshot)
        val evidence = installResult.toEvidence() +
            notificationProxyResult.evidence +
            launcherAppsProxyResult.evidence +
            clipboardProxyResult.evidence +
            appOpsProxyResult.evidence +
            appOpsServiceManagerProxyResult.evidence +
            uriGrantsServiceManagerProxyResult.evidence +
            contentProviderIdentityProxyResult.evidence +
            storageManagerProxyResult.evidence
        val durationMs = clock() - startMs
        val result = when (installResult.status) {
            VirtualPackageManagerGlobalInstallStatus.INSTALLED -> {
                if (notificationProxyResult.degradesStage ||
                    launcherAppsProxyResult.degradesStage ||
                    clipboardProxyResult.degradesStage ||
                    appOpsProxyResult.degradesStage ||
                    appOpsServiceManagerProxyResult.degradesStage ||
                    contentProviderIdentityProxyResult.degradesStage ||
                    storageManagerProxyResult.degradesStage
                ) {
                    BootstrapResult.degraded(
                        stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                        message = "Global package manager proxy installed; system package proxy degraded",
                        evidence = evidence,
                        durationMs = durationMs
                    )
                } else {
                    BootstrapResult.success(
                        stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                        message = "Global package manager proxy install attempted",
                        evidence = evidence,
                        durationMs = durationMs
                    )
                }
            }
            VirtualPackageManagerGlobalInstallStatus.DEGRADED -> BootstrapResult.degraded(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "Global package manager proxy degraded; local wrapper fallback remains available",
                evidence = evidence,
                durationMs = durationMs
            )
            VirtualPackageManagerGlobalInstallStatus.SKIPPED -> BootstrapResult.skipped(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "Global package manager proxy skipped",
                evidence = evidence
            ).copy(durationMs = durationMs)
        }

        return BootstrapStageOutput(
            context = input,
            result = result,
            terminalFailure = false
        )
    }

    private fun installNotificationPackageProxy(snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot): NotificationPackageProxyResult {
        val hostPackageName = hostContext?.packageName?.takeIf { it.isNotBlank() }
            ?: return NotificationPackageProxyResult(
                evidence = notificationProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName),
                    hostPackageName = "",
                    reason = "HOST_CONTEXT_MISSING"
                ),
                degradesStage = false
            )
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        return runCatching {
            val installed = notificationPackageProxyInstaller.install(sourcePackages, hostPackageName)
            NotificationPackageProxyResult(
                evidence = notificationProxyEvidence(
                    status = if (installed) "INSTALLED" else "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = if (installed) "" else "INSTALLER_RETURNED_FALSE"
                ),
                degradesStage = !installed
            )
        }.getOrElse { error ->
            NotificationPackageProxyResult(
                evidence = notificationProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name
                ),
                degradesStage = true
            )
        }
    }

    private fun installAppOpsPackageProxy(snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot): AppOpsPackageProxyResult {
        val hostPackageName = hostContext?.packageName?.takeIf { it.isNotBlank() }
            ?: return AppOpsPackageProxyResult(
                evidence = appOpsProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName),
                    hostPackageName = "",
                    reason = "HOST_CONTEXT_MISSING"
                ),
                degradesStage = false
            )
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        return runCatching {
            val installed = appOpsPackageProxyInstaller.install(sourcePackages, hostPackageName)
            AppOpsPackageProxyResult(
                evidence = appOpsProxyEvidence(
                    status = if (installed) "INSTALLED" else "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = if (installed) "" else "INSTALLER_RETURNED_FALSE"
                ),
                degradesStage = !installed
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = appOpsProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name
                ),
                degradesStage = true
            )
        }
    }

    private fun installLauncherAppsPackageProxy(
        snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot
    ): AppOpsPackageProxyResult {
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        val hostPackageName = hostContext?.packageName?.takeIf { it.isNotBlank() }
            ?: return AppOpsPackageProxyResult(
                evidence = launcherAppsProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = "",
                    reason = "HOST_CONTEXT_MISSING"
                ),
                degradesStage = false
            )
        return runCatching {
            val installed = launcherAppsPackageProxyInstaller.install(sourcePackages, hostPackageName)
            AppOpsPackageProxyResult(
                evidence = launcherAppsProxyEvidence(
                    status = if (installed) "INSTALLED" else "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = if (installed) "" else "INSTALLER_RETURNED_FALSE"
                ),
                degradesStage = !installed
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = launcherAppsProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name
                ),
                degradesStage = true
            )
        }
    }

    private fun installClipboardPackageProxy(
        snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot
    ): AppOpsPackageProxyResult {
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        val hostPackageName = hostContext?.packageName?.takeIf { it.isNotBlank() }
            ?: return AppOpsPackageProxyResult(
                evidence = clipboardProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = "",
                    reason = "HOST_CONTEXT_MISSING",
                    managerStatus = "SKIPPED",
                    serviceManagerStatus = "SKIPPED"
                ),
                degradesStage = false
            )
        return runCatching {
            val installResult = clipboardPackageProxyInstaller.install(sourcePackages, hostPackageName)
            val status = when {
                installResult.complete -> "INSTALLED"
                installResult.installed -> "PARTIAL"
                else -> "FAILED"
            }
            val reason = when {
                installResult.complete -> ""
                !installResult.managerPatched && !installResult.serviceManagerPatched ->
                    "MANAGER_AND_SERVICE_MANAGER_PATCH_FAILED"
                !installResult.managerPatched -> "MANAGER_PATCH_FAILED"
                else -> "SERVICE_MANAGER_PATCH_FAILED"
            }
            AppOpsPackageProxyResult(
                evidence = clipboardProxyEvidence(
                    status = status,
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = reason,
                    managerStatus = if (installResult.managerPatched) "INSTALLED" else "FAILED",
                    serviceManagerStatus = if (installResult.serviceManagerPatched) "INSTALLED" else "FAILED"
                ),
                degradesStage = !installResult.complete
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = clipboardProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name,
                    managerStatus = "FAILED",
                    serviceManagerStatus = "FAILED"
                ),
                degradesStage = true
            )
        }
    }

    private fun installAppOpsServiceManagerPackageProxy(snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot): AppOpsPackageProxyResult {
        val hostPackageName = hostContext?.packageName?.takeIf { it.isNotBlank() }
            ?: return AppOpsPackageProxyResult(
                evidence = appOpsServiceManagerProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName),
                    hostPackageName = "",
                    reason = "HOST_CONTEXT_MISSING"
                ),
                degradesStage = false
            )
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        return runCatching {
            val installed = appOpsServiceManagerProxyInstaller.install(sourcePackages, hostPackageName)
            AppOpsPackageProxyResult(
                evidence = appOpsServiceManagerProxyEvidence(
                    status = if (installed) "INSTALLED" else "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = if (installed) "" else "INSTALLER_RETURNED_FALSE"
                ),
                degradesStage = !installed
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = appOpsServiceManagerProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name
                ),
                degradesStage = true
            )
        }
    }

    private fun installUriGrantsServiceManagerProxy(
        snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot
    ): AppOpsPackageProxyResult {
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        if (hostContext == null) {
            return AppOpsPackageProxyResult(
                evidence = uriGrantsServiceManagerProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = sourcePackages,
                    reason = "HOST_CONTEXT_MISSING"
                ),
                degradesStage = false
            )
        }
        return runCatching {
            val installed = uriGrantsServiceManagerProxyInstaller.install()
            AppOpsPackageProxyResult(
                evidence = uriGrantsServiceManagerProxyEvidence(
                    status = if (installed) "INSTALLED" else "UNSUPPORTED",
                    sourcePackages = sourcePackages,
                    reason = if (installed) "" else "INSTALLER_RETURNED_FALSE"
                ),
                degradesStage = false
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = uriGrantsServiceManagerProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name
                ),
                degradesStage = false
            )
        }
    }

    private fun installContentProviderIdentityProxy(
        snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot,
        runtimeUid: Int
    ): AppOpsPackageProxyResult {
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        val hostPackageName = hostContext?.packageName?.takeIf { it.isNotBlank() }
            ?: return AppOpsPackageProxyResult(
                evidence = contentProviderIdentityProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = "",
                    reason = "HOST_CONTEXT_MISSING"
                ),
                degradesStage = false
            )
        return runCatching {
            val installResult = contentProviderIdentityProxyInstaller.install(
                sourcePackages,
                hostPackageName,
                runtimeUid
            )
            val status = when {
                installResult.complete -> "INSTALLED"
                installResult.activityManagerProxyInstalled -> "PARTIAL"
                else -> "FAILED"
            }
            AppOpsPackageProxyResult(
                evidence = contentProviderIdentityProxyEvidence(
                    status = status,
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = installResult.failures.joinToString("|"),
                    activityManagerInstalled = installResult.activityManagerProxyInstalled,
                    providerCacheInspected = installResult.providerCacheInspected,
                    cachedProviderRecordCount = installResult.cachedProviderRecordCount,
                    cachedProviderPatchedCount = installResult.cachedProviderPatchedCount,
                    settingsProviderCacheInspectedCount = installResult.settingsProviderCacheInspectedCount,
                    settingsProviderCacheClearedCount = installResult.settingsProviderCacheClearedCount
                ),
                degradesStage = !installResult.complete
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = contentProviderIdentityProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name
                ),
                degradesStage = true
            )
        }
    }

    private fun installStorageManagerProxy(
        input: BootstrapStageInput,
        snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot
    ): AppOpsPackageProxyResult {
        val sourcePackages = listOf(snapshot.originPackageName, snapshot.virtualPackageName).distinct()
        val hostPackageName = resolveSystemHostPackageName(
            guestPackages = sourcePackages.toSet(),
            processSlot = input.processSlot,
            processName = runCatching { android.app.Application.getProcessName() }.getOrNull(),
            baseOpPackageName = hostContext?.opPackageNameCompat(),
            basePackageName = runCatching { hostContext?.packageName }.getOrNull()
        )
        if (hostContext == null || hostPackageName.isBlank()) {
            return AppOpsPackageProxyResult(
                evidence = storageManagerProxyEvidence(
                    status = "SKIPPED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    dataRoot = snapshot.dataDir,
                    processSlot = input.processSlot,
                    reason = "HOST_CONTEXT_MISSING",
                    managerStatus = "SKIPPED",
                    serviceManagerStatus = "SKIPPED"
                ),
                degradesStage = false
            )
        }
        return runCatching {
            val result = storageManagerProxyInstaller.install(
                snapshot.instanceId,
                snapshot.originPackageName,
                snapshot.virtualPackageName,
                hostPackageName,
                snapshot.dataDir,
                input.processSlot
            )
            val status = when {
                result.complete -> "INSTALLED"
                result.installed -> "PARTIAL"
                else -> "FAILED"
            }
            AppOpsPackageProxyResult(
                evidence = storageManagerProxyEvidence(
                    status = status,
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    dataRoot = snapshot.dataDir,
                    processSlot = input.processSlot,
                    reason = result.reason,
                    managerStatus = if (result.managerPatched) "INSTALLED" else "FAILED",
                    serviceManagerStatus = if (result.serviceManagerPatched) "INSTALLED" else "FAILED"
                ),
                degradesStage = !result.complete
            )
        }.getOrElse { error ->
            AppOpsPackageProxyResult(
                evidence = storageManagerProxyEvidence(
                    status = "FAILED",
                    sourcePackages = sourcePackages,
                    hostPackageName = hostPackageName,
                    dataRoot = snapshot.dataDir,
                    processSlot = input.processSlot,
                    reason = error.message ?: error.javaClass.name,
                    errorClass = error.javaClass.name,
                    managerStatus = "FAILED",
                    serviceManagerStatus = "FAILED"
                ),
                degradesStage = true
            )
        }
    }

    private fun notificationProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        reason: String,
        errorClass: String = ""
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("notificationPackageProxyStatus", status, NOTIFICATION_PROXY_SOURCE),
        BootstrapEvidence("notificationPackageProxySourcePackages", sourcePackages.joinToString(","), NOTIFICATION_PROXY_SOURCE),
        BootstrapEvidence("notificationPackageProxyHostPackage", hostPackageName, NOTIFICATION_PROXY_SOURCE),
        BootstrapEvidence("notificationPackageProxyMode", "guest-to-host-package-args", NOTIFICATION_PROXY_SOURCE),
        BootstrapEvidence("notificationPackageProxyReason", reason, NOTIFICATION_PROXY_SOURCE),
        BootstrapEvidence("notificationPackageProxyErrorClass", errorClass, NOTIFICATION_PROXY_SOURCE)
    )

    private fun appOpsProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        reason: String,
        errorClass: String = ""
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("appOpsPackageProxyStatus", status, APP_OPS_PROXY_SOURCE),
        BootstrapEvidence("appOpsPackageProxySourcePackages", sourcePackages.joinToString(","), APP_OPS_PROXY_SOURCE),
        BootstrapEvidence("appOpsPackageProxyHostPackage", hostPackageName, APP_OPS_PROXY_SOURCE),
        BootstrapEvidence("appOpsPackageProxyMode", "guest-to-host-package-args", APP_OPS_PROXY_SOURCE),
        BootstrapEvidence("appOpsPackageProxyReason", reason, APP_OPS_PROXY_SOURCE),
        BootstrapEvidence("appOpsPackageProxyErrorClass", errorClass, APP_OPS_PROXY_SOURCE)
    )

    private fun launcherAppsProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        reason: String,
        errorClass: String = ""
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("launcherAppsPackageProxyStatus", status, LAUNCHER_APPS_PROXY_SOURCE),
        BootstrapEvidence(
            "launcherAppsPackageProxySourcePackages",
            sourcePackages.joinToString(","),
            LAUNCHER_APPS_PROXY_SOURCE
        ),
        BootstrapEvidence("launcherAppsPackageProxyHostPackage", hostPackageName, LAUNCHER_APPS_PROXY_SOURCE),
        BootstrapEvidence(
            "launcherAppsPackageProxyMode",
            "launcherapps-manager-servicemanager-binder",
            LAUNCHER_APPS_PROXY_SOURCE
        ),
        BootstrapEvidence("launcherAppsPackageProxyReason", reason, LAUNCHER_APPS_PROXY_SOURCE),
        BootstrapEvidence("launcherAppsPackageProxyErrorClass", errorClass, LAUNCHER_APPS_PROXY_SOURCE)
    )

    private fun clipboardProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        reason: String,
        errorClass: String = "",
        managerStatus: String,
        serviceManagerStatus: String
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("clipboardPackageProxyStatus", status, CLIPBOARD_PROXY_SOURCE),
        BootstrapEvidence(
            "clipboardPackageProxySourcePackages",
            sourcePackages.joinToString(","),
            CLIPBOARD_PROXY_SOURCE
        ),
        BootstrapEvidence("clipboardPackageProxyHostPackage", hostPackageName, CLIPBOARD_PROXY_SOURCE),
        BootstrapEvidence(
            "clipboardPackageProxyMode",
            "clipboard-manager-servicemanager-binder",
            CLIPBOARD_PROXY_SOURCE
        ),
        BootstrapEvidence("clipboardPackageProxyReason", reason, CLIPBOARD_PROXY_SOURCE),
        BootstrapEvidence("clipboardPackageProxyErrorClass", errorClass, CLIPBOARD_PROXY_SOURCE),
        BootstrapEvidence("clipboardManagerProxyStatus", managerStatus, CLIPBOARD_PROXY_SOURCE),
        BootstrapEvidence(
            "clipboardServiceManagerProxyStatus",
            serviceManagerStatus,
            CLIPBOARD_PROXY_SOURCE
        )
    )

    private fun appOpsServiceManagerProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        reason: String,
        errorClass: String = ""
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("appOpsServiceManagerProxyStatus", status, APP_OPS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("appOpsServiceManagerProxySourcePackages", sourcePackages.joinToString(","), APP_OPS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("appOpsServiceManagerProxyHostPackage", hostPackageName, APP_OPS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("appOpsServiceManagerProxyMode", "servicemanager-appops-binder", APP_OPS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("appOpsServiceManagerProxyReason", reason, APP_OPS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("appOpsServiceManagerProxyErrorClass", errorClass, APP_OPS_SERVICE_MANAGER_PROXY_SOURCE)
    )

    private fun uriGrantsServiceManagerProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        reason: String,
        errorClass: String = ""
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("uriGrantsServiceManagerProxyStatus", status, URI_GRANTS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence(
            "uriGrantsServiceManagerProxySourcePackages",
            sourcePackages.joinToString(","),
            URI_GRANTS_SERVICE_MANAGER_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "uriGrantsServiceManagerProxyMode",
            "servicemanager-uri-grants-binder",
            URI_GRANTS_SERVICE_MANAGER_PROXY_SOURCE
        ),
        BootstrapEvidence("uriGrantsServiceManagerProxyReason", reason, URI_GRANTS_SERVICE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence(
            "uriGrantsServiceManagerProxyErrorClass",
            errorClass,
            URI_GRANTS_SERVICE_MANAGER_PROXY_SOURCE
        )
    )

    private fun contentProviderIdentityProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        reason: String,
        errorClass: String = "",
        activityManagerInstalled: Boolean = false,
        providerCacheInspected: Boolean = false,
        cachedProviderRecordCount: Int = 0,
        cachedProviderPatchedCount: Int = 0,
        settingsProviderCacheInspectedCount: Int = 0,
        settingsProviderCacheClearedCount: Int = 0
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("contentProviderIdentityProxyStatus", status, CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE),
        BootstrapEvidence(
            "contentProviderIdentityProxySourcePackages",
            sourcePackages.joinToString(","),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "contentProviderIdentityProxyHostPackage",
            hostPackageName,
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "contentProviderIdentityProxyMode",
            "activitymanager-holder-and-cached-provider-attribution",
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence("contentProviderIdentityProxyReason", reason, CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE),
        BootstrapEvidence("contentProviderIdentityProxyErrorClass", errorClass, CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE),
        BootstrapEvidence(
            "contentProviderActivityManagerProxyInstalled",
            activityManagerInstalled.toString(),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "contentProviderCacheInspected",
            providerCacheInspected.toString(),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "contentProviderCachedRecordCount",
            cachedProviderRecordCount.toString(),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "contentProviderCachedPatchedCount",
            cachedProviderPatchedCount.toString(),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "settingsProviderCacheInspectedCount",
            settingsProviderCacheInspectedCount.toString(),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        ),
        BootstrapEvidence(
            "settingsProviderCacheClearedCount",
            settingsProviderCacheClearedCount.toString(),
            CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE
        )
    )

    private fun storageManagerProxyEvidence(
        status: String,
        sourcePackages: List<String>,
        hostPackageName: String,
        dataRoot: String,
        processSlot: String?,
        reason: String,
        errorClass: String = "",
        managerStatus: String,
        serviceManagerStatus: String
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("storageManagerProxyStatus", status, STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence(
            "storageManagerProxySourcePackages",
            sourcePackages.joinToString(","),
            STORAGE_MANAGER_PROXY_SOURCE
        ),
        BootstrapEvidence("storageManagerProxyHostPackage", hostPackageName, STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("storageManagerProxyDataRoot", dataRoot, STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("storageManagerProxyProcessSlot", processSlot.orEmpty(), STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence(
            "storageManagerProxyMode",
            "mount-manager-servicemanager-instance-mkdirs",
            STORAGE_MANAGER_PROXY_SOURCE
        ),
        BootstrapEvidence("storageManagerProxyReason", reason, STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("storageManagerProxyErrorClass", errorClass, STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence("storageManagerInstanceProxyStatus", managerStatus, STORAGE_MANAGER_PROXY_SOURCE),
        BootstrapEvidence(
            "storageManagerServiceManagerProxyStatus",
            serviceManagerStatus,
            STORAGE_MANAGER_PROXY_SOURCE
        )
    )

    private data class NotificationPackageProxyResult(
        val evidence: List<BootstrapEvidence>,
        val degradesStage: Boolean
    )

    private data class AppOpsPackageProxyResult(
        val evidence: List<BootstrapEvidence>,
        val degradesStage: Boolean
    )

    private companion object {
        const val NOTIFICATION_PROXY_SOURCE = "NotificationPackageProxy"
        const val LAUNCHER_APPS_PROXY_SOURCE = "LauncherAppsPackageProxy"
        const val CLIPBOARD_PROXY_SOURCE = "ClipboardPackageProxy"
        const val APP_OPS_PROXY_SOURCE = "AppOpsPackageProxy"
        const val APP_OPS_SERVICE_MANAGER_PROXY_SOURCE = "AppOpsServiceManagerProxy"
        const val URI_GRANTS_SERVICE_MANAGER_PROXY_SOURCE = "UriGrantsServiceManagerProxy"
        const val CONTENT_PROVIDER_IDENTITY_PROXY_SOURCE = "ContentProviderIdentityProxy"
        const val STORAGE_MANAGER_PROXY_SOURCE = "StorageManagerProxy"
    }
}

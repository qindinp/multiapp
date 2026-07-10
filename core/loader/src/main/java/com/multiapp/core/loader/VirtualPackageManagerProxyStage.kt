package com.multiapp.core.loader

import android.content.Context
import android.os.Process

fun interface NotificationPackageProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

fun interface AppOpsPackageProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

fun interface AppOpsServiceManagerProxyInstallAction {
    fun install(sourcePackages: Collection<String>, hostPackageName: String): Boolean
}

class VirtualPackageManagerProxyStage(
    private val hostContext: Context?,
    private val installer: VirtualPackageManagerGlobalInstallAction = VirtualPackageManagerGlobalInstaller(),
    private val notificationPackageProxyInstaller: NotificationPackageProxyInstallAction =
        NotificationPackageProxyInstallAction { sourcePackages, hostPackageName ->
            IntentRemapDiagnostics.installNotificationManagerPackageProxy(sourcePackages, hostPackageName)
        },
    private val appOpsPackageProxyInstaller: AppOpsPackageProxyInstallAction =
        AppOpsPackageProxyInstallAction { sourcePackages, hostPackageName ->
            IntentRemapDiagnostics.installAppOpsManagerPackageProxy(hostContext, sourcePackages, hostPackageName)
        },
    private val appOpsServiceManagerProxyInstaller: AppOpsServiceManagerProxyInstallAction =
        AppOpsServiceManagerProxyInstallAction { sourcePackages, hostPackageName ->
            IntentRemapDiagnostics.installAppOpsServiceManagerPackageProxy(sourcePackages, hostPackageName)
        },
    private val runtimeUidProvider: () -> Int = { runCatching { Process.myUid() }.getOrDefault(0) },
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
        val appOpsProxyResult = installAppOpsPackageProxy(snapshot)
        val appOpsServiceManagerProxyResult = installAppOpsServiceManagerPackageProxy(snapshot)
        val evidence = installResult.toEvidence() +
            notificationProxyResult.evidence +
            appOpsProxyResult.evidence +
            appOpsServiceManagerProxyResult.evidence
        val durationMs = clock() - startMs
        val result = when (installResult.status) {
            VirtualPackageManagerGlobalInstallStatus.INSTALLED -> {
                if (notificationProxyResult.degradesStage ||
                    appOpsProxyResult.degradesStage ||
                    appOpsServiceManagerProxyResult.degradesStage
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
        const val APP_OPS_PROXY_SOURCE = "AppOpsPackageProxy"
        const val APP_OPS_SERVICE_MANAGER_PROXY_SOURCE = "AppOpsServiceManagerProxy"
    }
}

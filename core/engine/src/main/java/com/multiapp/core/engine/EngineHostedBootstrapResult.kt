package com.multiapp.core.engine

import android.app.Application
import com.multiapp.core.hook.NativeDiagnosticsResult
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.BootstrapStatus
import com.multiapp.core.loader.BootstrapSummary
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

enum class EngineBootstrapStage {
    CONFIG,
    ORIGIN_APK,
    PACKAGE_METADATA,
    NATIVE_LIBS,
    RESOURCES,
    PACKAGE_MANAGER_PROXY,
    CLASS_LOADER,
    GUEST_CONTEXT,
    APPLICATION,
    LAUNCHER_ACTIVITY;

    companion object {
        fun fromLoader(stage: RuntimeStage): EngineBootstrapStage =
            valueOf(stage.name)
    }
}

enum class EngineBootstrapStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
    DEGRADED;

    companion object {
        fun fromLoader(status: BootstrapStatus): EngineBootstrapStatus =
            valueOf(status.name)
    }
}

data class EngineBootstrapStageResult(
    val stage: EngineBootstrapStage,
    val status: EngineBootstrapStatus,
    val message: String,
    val evidence: Map<String, String>,
    val durationMs: Long,
    val rollbackNote: String?,
    val errorClass: String?,
    val errorMessage: String?
) {
    val isSuccessful: Boolean
        get() = status == EngineBootstrapStatus.SUCCESS

    val isTerminalFailure: Boolean
        get() = status == EngineBootstrapStatus.FAILED

    fun toEvidenceFields(): Map<String, String> {
        return buildMap {
            put("stage", stage.name)
            put("status", status.name)
            put("message", message)
            put("durationMs", durationMs.toString())
            errorClass?.let { put("errorClass", it) }
            errorMessage?.let { put("errorMessage", it) }
            rollbackNote?.let { put("rollbackNote", it) }
            evidence.forEach { (key, value) -> put(key, value) }
        }
    }

    companion object {
        fun fromLoader(result: BootstrapResult): EngineBootstrapStageResult =
            EngineBootstrapStageResult(
                stage = EngineBootstrapStage.fromLoader(result.stage),
                status = EngineBootstrapStatus.fromLoader(result.status),
                message = result.message,
                evidence = result.evidence.associate { it.key to it.value },
                durationMs = result.durationMs,
                rollbackNote = result.rollbackNote,
                errorClass = result.errorClass,
                errorMessage = result.errorMessage
            )
    }
}

class EngineHostedBootstrapResult private constructor(
    internal val loaderResult: HostedBootstrapResult
) {
    val instanceId: String get() = loaderResult.instanceId
    val installId: String? get() = loaderResult.installId
    val originPackageName: String? get() = loaderResult.originPackageName
    val virtualPackageName: String? get() = loaderResult.virtualPackageName
    val applicationLabel: String? get() = loaderResult.applicationLabel
    val processSlot: String? get() = loaderResult.processSlot
    val originApkPath: String? get() = loaderResult.originApkPath
    val dataRoot: String? get() = loaderResult.dataRoot
    val guestClassLoader: ClassLoader? get() = loaderResult.guestClassLoader
    val guestApplication: Application? get() = loaderResult.guestApplication
    val installRecord: InstallRecord? get() = loaderResult.installRecord
    val packageSnapshot: VirtualPackageSnapshot? get() = loaderResult.packageSnapshot
    val launcherActivityClassName: String? get() = loaderResult.launcherActivityClassName
    val stageResults: List<EngineBootstrapStageResult> =
        loaderResult.stageResults.map(EngineBootstrapStageResult::fromLoader)
    val summary: BootstrapSummary get() = loaderResult.summary
    val success: Boolean get() = loaderResult.success
    val diagnostics: NativeDiagnosticsResult? get() = loaderResult.diagnostics

    fun firstStageResult(stage: EngineBootstrapStage): EngineBootstrapStageResult? =
        stageResults.firstOrNull { it.stage == stage }

    fun lastStageResult(stage: EngineBootstrapStage): EngineBootstrapStageResult? =
        stageResults.lastOrNull { it.stage == stage }

    companion object {
        fun fromLoader(result: HostedBootstrapResult): EngineHostedBootstrapResult =
            EngineHostedBootstrapResult(result)

        fun unwrap(result: Any): HostedBootstrapResult? = when (result) {
            is EngineHostedBootstrapResult -> result.loaderResult
            is HostedBootstrapResult -> result
            else -> null
        }
    }
}

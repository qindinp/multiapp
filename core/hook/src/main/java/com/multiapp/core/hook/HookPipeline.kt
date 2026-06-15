package com.multiapp.core.hook

import timber.log.Timber

/**
 * HookPipeline — 阶段化 Hook 编排器
 *
 * 将散落的 Hook 安装逻辑统一为有序的阶段化编排。
 * 每个阶段按优先级排序执行，关键阶段失败时可中止启动或进入诊断模式。
 *
 * 注意：不使用 Hilt @Singleton/@Inject，因为 LoaderFactory 在 AppComponentFactory 阶段
 * 运行，此时 Hilt 尚未初始化。所有调用方通过 getInstance() 获取全局单例。
 */
class HookPipeline private constructor() {

    companion object {
        private const val TAG = "HookPipeline"

        @Volatile
        private var instance: HookPipeline? = null

        fun getInstance(): HookPipeline {
            return instance ?: synchronized(this) {
                instance ?: HookPipeline().also { instance = it }
            }
        }

        fun resetInstance() {
            instance = null
        }
    }

    private val stages = mutableListOf<HookStage>()
    private var executed = false

    fun registerStage(stage: HookStage) {
        stages.add(stage)
        stages.sortBy { it.priority }
        Timber.tag(TAG).d("Registered stage: ${stage.name} (priority=${stage.priority}, critical=${stage.critical})")
    }

    fun registerStages(vararg newStages: HookStage) {
        stages.addAll(newStages)
        stages.sortBy { it.priority }
        Timber.tag(TAG).d("Registered ${newStages.size} stages")
    }

    fun execute(context: HookStageContext): HookPipelineResult {
        if (executed) {
            Timber.tag(TAG).w("Pipeline already executed, skipping")
            return HookPipelineResult(
                status = HookPipelineStatus.ALREADY_EXECUTED,
                stageResults = emptyList()
            )
        }

        Timber.tag(TAG).i("=== HookPipeline.execute() 开始 — ${stages.size} 个阶段 ===")
        val results = mutableListOf<StageExecutionRecord>()
        var diagnosticFallback = false

        for (stage in stages) {
            Timber.tag(TAG).i("阶段 [${stage.priority}] ${stage.name} 开始执行")
            val startTime = System.currentTimeMillis()

            val result = try {
                stage.execute(context)
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "阶段 [${stage.priority}] ${stage.name} 抛出异常")
                HookStageResult.fatal(e.message ?: "unknown error", e)
            }

            val elapsed = System.currentTimeMillis() - startTime
            val record = StageExecutionRecord(
                stageName = stage.name,
                stagePriority = stage.priority,
                critical = stage.critical,
                result = result,
                elapsedMs = elapsed
            )
            results.add(record)

            Timber.tag(TAG).i(
                "阶段 [${stage.priority}] ${stage.name} 完成 — " +
                    "status=${result.status}, elapsed=${elapsed}ms"
            )

            when (result.status) {
                HookStageStatus.FATAL -> {
                    if (stage.critical) {
                        Timber.tag(TAG).e(
                            "关键阶段 [${stage.priority}] ${stage.name} 失败，中止启动: ${result.message}"
                        )
                        return HookPipelineResult(
                            status = HookPipelineStatus.ABORTED,
                            stageResults = results,
                            abortReason = "关键阶段 ${stage.name} 失败: ${result.message}"
                        )
                    } else {
                        Timber.tag(TAG).w(
                            "非关键阶段 [${stage.priority}] ${stage.name} 失败，继续执行: ${result.message}"
                        )
                    }
                }
                HookStageStatus.DIAGNOSTIC_FALLBACK -> {
                    diagnosticFallback = true
                    Timber.tag(TAG).w(
                        "阶段 [${stage.priority}] ${stage.name} 进入诊断回退模式: ${result.message}"
                    )
                }
                HookStageStatus.SUCCESS, HookStageStatus.DEGRADED -> {
                    // 正常继续
                }
            }
        }

        executed = true
        val finalStatus = if (diagnosticFallback) {
            HookPipelineStatus.DIAGNOSTIC_MODE
        } else {
            HookPipelineStatus.SUCCESS
        }

        Timber.tag(TAG).i(
            "=== HookPipeline.execute() 完成 — status=$finalStatus, " +
                "阶段数=${results.size} ==="
        )

        return HookPipelineResult(
            status = finalStatus,
            stageResults = results
        )
    }

    fun isExecuted(): Boolean = executed

    fun getRegisteredStages(): List<HookStage> = stages.toList()

    fun reset() {
        stages.clear()
        executed = false
        Timber.tag(TAG).d("Pipeline reset")
    }
}

enum class HookStageType {
    RUNTIME_CONFIG,
    NATIVE_BASE_HOOKS,
    LOADED_APK_INSTALL,
    GUEST_BOUND_NATIVE_HOOKS,
    PACKAGE_IDENTITY_HOOKS,
    SYSTEM_SERVICE_PROXIES,
    PACKER_RUNTIME_HOOKS,
    GUEST_BUSINESS_PROBES
}

interface HookStage {
    val name: String
    val priority: Int
    val critical: Boolean
    fun execute(context: HookStageContext): HookStageResult
}

data class HookStageContext(
    val hookEngine: HookEngine,
    val nativeBridge: NativeHookBridge,
    val classLoader: ClassLoader? = null,
    val packageName: String? = null,
    val instanceId: String? = null,
    val extras: Map<String, Any?> = emptyMap()
)

enum class HookStageStatus {
    SUCCESS,
    FATAL,
    DEGRADED,
    DIAGNOSTIC_FALLBACK
}

data class HookStageResult(
    val status: HookStageStatus,
    val message: String = "",
    val details: Map<String, Any?> = emptyMap(),
    val error: Throwable? = null
) {
    companion object {
        fun success(message: String = "", details: Map<String, Any?> = emptyMap()) =
            HookStageResult(HookStageStatus.SUCCESS, message, details)

        fun fatal(message: String, error: Throwable? = null) =
            HookStageResult(HookStageStatus.FATAL, message, error = error)

        fun degraded(message: String, details: Map<String, Any?> = emptyMap()) =
            HookStageResult(HookStageStatus.DEGRADED, message, details)

        fun diagnosticFallback(message: String, details: Map<String, Any?> = emptyMap()) =
            HookStageResult(HookStageStatus.DIAGNOSTIC_FALLBACK, message, details)
    }
}

enum class HookPipelineStatus {
    SUCCESS,
    ABORTED,
    DIAGNOSTIC_MODE,
    ALREADY_EXECUTED
}

data class StageExecutionRecord(
    val stageName: String,
    val stagePriority: Int,
    val critical: Boolean,
    val result: HookStageResult,
    val elapsedMs: Long
)

data class HookPipelineResult(
    val status: HookPipelineStatus,
    val stageResults: List<StageExecutionRecord>,
    val abortReason: String? = null
) {
    fun summary(): String {
        val sb = StringBuilder()
        sb.appendLine("Pipeline status: $status")
        if (abortReason != null) sb.appendLine("Abort reason: $abortReason")
        sb.appendLine("Stages executed: ${stageResults.size}")
        for (record in stageResults) {
            val marker = when (record.result.status) {
                HookStageStatus.SUCCESS -> "✓"
                HookStageStatus.DEGRADED -> "~"
                HookStageStatus.FATAL -> "✗"
                HookStageStatus.DIAGNOSTIC_FALLBACK -> "!"
            }
            val criticalTag = if (record.critical) " [CRITICAL]" else ""
            sb.appendLine(
                "  $marker [${record.stagePriority}] ${record.stageName}$criticalTag — " +
                    "${record.result.status} (${record.elapsedMs}ms)"
            )
            if (record.result.message.isNotEmpty()) {
                sb.appendLine("    ${record.result.message}")
            }
        }
        return sb.toString()
    }
}

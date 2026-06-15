package com.multiapp.core.loader.stages

import com.multiapp.core.hook.HookStage
import com.multiapp.core.hook.HookStageContext
import com.multiapp.core.hook.HookStageResult
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * Stage 0: RuntimeConfig — 配置加载
 *
 * 从 Stub APK 的 assets/multiapp_config.json 读取打包配置，
 * 提取 originalPackageName 和 stubPackageName。
 *
 * 读取结果写入 context.extras["runtimeConfig"]，供下游阶段使用。
 */
class RuntimeConfigStage : HookStage {

    companion object {
        private const val TAG = "RuntimeConfigStage"
        const val KEY_CONFIG = "runtimeConfig"
    }

    override val name: String = "RuntimeConfig"
    override val priority: Int = 0
    override val critical: Boolean = true

    override fun execute(context: HookStageContext): HookStageResult {
        val stubApkPath = context.extras["stubApkPath"] as? String
        if (stubApkPath == null) {
            Timber.tag(TAG).e("stubApkPath not provided in context extras")
            return HookStageResult.fatal("stubApkPath is null")
        }

        return try {
            val config = readConfig(stubApkPath)
            Timber.tag(TAG).i(
                "Config loaded: originalPkg=%s, stubPkg=%s",
                config.originalPkg, config.stubPkg
            )

            // Store config in shared mutable extras for downstream stages
            @Suppress("UNCHECKED_CAST")
            val extras = context.extras as? MutableMap<String, Any?>
            extras?.set(KEY_CONFIG, config)

            HookStageResult.success(
                "Config loaded: ${config.originalPkg} / ${config.stubPkg}",
                mapOf("originalPkg" to config.originalPkg, "stubPkg" to config.stubPkg)
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to read config from %s", stubApkPath)
            HookStageResult.fatal("Config read failed: ${e.message}", e)
        }
    }

    private fun readConfig(stubApkPath: String): RuntimeConfig {
        ZipFile(File(stubApkPath)).use { zip ->
            val entry = zip.getEntry("assets/multiapp_config.json")
                ?: throw IllegalStateException("assets/multiapp_config.json not found in stub APK")
            val json = zip.getInputStream(entry).bufferedReader().readText()

            val originalPkg = json.regexFind("\"originalPackageName\"\\s*:\\s*\"([^\"]+)\"")
                ?: throw IllegalStateException("originalPackageName not found in config")
            val stubPkg = json.regexFind("\"stubPackageName\"\\s*:\\s*\"([^\"]+)\"")
                ?: throw IllegalStateException("stubPackageName not found in config")

            return RuntimeConfig(originalPkg = originalPkg, stubPkg = stubPkg)
        }
    }

    private fun String.regexFind(pattern: String): String? {
        return Regex(pattern).find(this)?.groupValues?.getOrNull(1)
    }
}

/**
 * Minimal config data extracted from multiapp_config.json.
 */
data class RuntimeConfig(
    val originalPkg: String,
    val stubPkg: String
)

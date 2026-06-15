package com.multiapp.core.loader.stages

import com.multiapp.core.hook.HookStage
import com.multiapp.core.hook.HookStageContext
import com.multiapp.core.hook.HookStageResult
import com.multiapp.core.identity.PackageIdentityHook
import com.multiapp.core.identity.VirtualPackageManager
import timber.log.Timber

/**
 * Stage 4: PackageIdentityHooks — 包名身份伪装
 *
 * 安装 PackageIdentityHook（Context.getPackageName 等）和 VirtualPackageManager，
 * 让分身应用在系统面前表现为原始包名。
 *
 * 依赖 RuntimeConfig 阶段写入的 config（通过 context.extras 传递）。
 */
class PackageIdentityHooksStage : HookStage {

    companion object {
        private const val TAG = "PackageIdentityHooksStage"
    }

    override val name: String = "PackageIdentityHooks"
    override val priority: Int = 4
    override val critical: Boolean = false

    override fun execute(context: HookStageContext): HookStageResult {
        val config = context.extras["runtimeConfig"] as? RuntimeConfig
        if (config == null) {
            Timber.tag(TAG).w("RuntimeConfig not found in extras, skipping identity hooks")
            return HookStageResult.degraded("RuntimeConfig missing, skipped")
        }

        val stubPkg = config.stubPkg
        val originPkg = config.originalPkg

        return try {
            // PackageIdentityHook: hook Context.getPackageName(), Process.myPackageName() 等
            PackageIdentityHook.applyDirect(stubPkg, originPkg)
            Timber.tag(TAG).i("PackageIdentityHook installed: %s -> %s", stubPkg, originPkg)

            // VirtualPackageManager: hook PackageManager 查询返回原始包名信息
            VirtualPackageManager.install(stubPkg, originPkg)
            Timber.tag(TAG).i("VirtualPackageManager installed: %s -> %s", stubPkg, originPkg)

            HookStageResult.success(
                "Identity hooks installed: $stubPkg -> $originPkg",
                mapOf("stubPkg" to stubPkg, "originPkg" to originPkg)
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "PackageIdentityHooks failed (non-critical)")
            HookStageResult.degraded("PackageIdentityHooks failed: ${e.message}")
        }
    }
}

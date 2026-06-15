package com.multiapp.core.loader.stages

import com.multiapp.core.hook.HookStage
import com.multiapp.core.hook.HookStageContext
import com.multiapp.core.hook.HookStageResult
import timber.log.Timber

/**
 * Stage 1: NativeBaseHooks — NativeHookBridge 初始化
 *
 * 安装 open/fopen/readlink 等 libc hook，过滤 /proc/self/maps 中的
 * multiapp/shadowhook 路径。必须在 identity hooks 之前执行。
 */
class NativeBaseHooksStage : HookStage {

    companion object {
        private const val TAG = "NativeBaseHooksStage"
    }

    override val name: String = "NativeBaseHooks"
    override val priority: Int = 1
    override val critical: Boolean = false

    override fun execute(context: HookStageContext): HookStageResult {
        return try {
            val hooksOk = context.nativeBridge.initNativeHooks()
            Timber.tag(TAG).i("NativeHookBridge.initNativeHooks: %s", hooksOk)

            if (hooksOk) {
                HookStageResult.success("Native hooks initialized")
            } else {
                HookStageResult.degraded("Native hooks init returned false")
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "NativeBaseHooks failed (non-critical)")
            HookStageResult.degraded("NativeBaseHooks failed: ${e.message}")
        }
    }
}

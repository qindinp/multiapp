package com.multiapp.core.identity

import com.multiapp.core.hook.HookEngine

/**
 * Hook point interface. All identity proxy hooks implement this.
 */
interface HookPoint {

    /**
     * Apply the hook with the given identity configuration.
     * hookEngine 参数可选，实现类通过 HookEngine.getInstance() 获取全局单例。
     */
    fun apply(config: IdentityConfig, hookEngine: HookEngine = HookEngine.getInstance())
}

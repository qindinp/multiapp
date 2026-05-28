package com.multiapp.core.identity

/**
 * Hook point interface. All identity proxy hooks implement this.
 */
interface HookPoint {

    /**
     * Apply the hook with the given identity configuration.
     */
    fun apply(config: IdentityConfig)
}

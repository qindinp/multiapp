package com.multiapp.core.model

enum class CompatibilityMode {
    APP_CONTAINER,
    NORMAL,
    PROTECTED_APP_BASELINE,
    PROTECTED_BASELINE,
    DIAGNOSTIC_NATIVE,
    ISOLATED_STORAGE_ONLY;

    val protectedAppBaseline: Boolean
        get() = this == PROTECTED_APP_BASELINE || this == PROTECTED_BASELINE

    val requiresHookFreeRuntime: Boolean
        get() = true

    val requiresRuntimeHooks: Boolean
        get() = false

    val isHookFree: Boolean
        get() = !requiresRuntimeHooks

    val allowsOptionalHookRuntime: Boolean
        get() = false

    companion object {
        val DEFAULT: CompatibilityMode = APP_CONTAINER
    }
}

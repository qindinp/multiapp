package com.multiapp.core.model

sealed interface LauncherItem {
    val displayName: String
    val isSystemApp: Boolean

    data class Virtual(val app: VirtualApp) : LauncherItem {
        override val displayName: String get() = app.appName
        override val isSystemApp: Boolean get() = false
    }

    data class System(val systemApp: SystemApp) : LauncherItem {
        override val displayName: String get() = systemApp.appName
        override val isSystemApp: Boolean get() = true
    }
}

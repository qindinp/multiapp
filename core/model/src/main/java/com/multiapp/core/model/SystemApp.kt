package com.multiapp.core.model

data class SystemApp(
    val id: String,
    val appName: String,
    val route: String,
    val iconType: SystemAppIcon
)

enum class SystemAppIcon {
    FILE_MANAGER
}

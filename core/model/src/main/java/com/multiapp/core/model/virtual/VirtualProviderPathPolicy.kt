package com.multiapp.core.model.virtual

enum class VirtualProviderPathPatternType {
    LITERAL,
    PREFIX,
    SIMPLE_GLOB,
    ADVANCED_GLOB,
    SUFFIX
}

data class VirtualProviderPathPattern(
    val path: String,
    val type: VirtualProviderPathPatternType
) {
    init {
        require(path.isNotEmpty()) { "provider path pattern must not be empty" }
    }
}

data class VirtualProviderPathPermission(
    val pattern: VirtualProviderPathPattern,
    val readPermission: String? = null,
    val writePermission: String? = null
) {
    init {
        require(readPermission == null || readPermission.isNotBlank()) {
            "provider path readPermission must not be blank"
        }
        require(writePermission == null || writePermission.isNotBlank()) {
            "provider path writePermission must not be blank"
        }
        require(readPermission != null || writePermission != null) {
            "provider path permission requires readPermission or writePermission"
        }
    }
}

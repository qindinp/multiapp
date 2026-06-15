package com.multiapp.core.hook

interface AppCompatProfile {
    val packageName: String
    val knownPacker: PackerType?
    val startupNeutralizeList: List<String>
    val forbiddenNeutralizeList: List<String>
    val diagnosticHooks: List<String>
}

enum class PackerType {
    JIAGU_360,
    TENCENT,
    BANGCLE,
    IJAMI,
    UNKNOWN
}

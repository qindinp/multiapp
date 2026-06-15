package com.multiapp.core.hook

class GenericPackerProfile(
    override val packageName: String,
    override val knownPacker: PackerType = PackerType.UNKNOWN
) : AppCompatProfile {

    override val startupNeutralizeList: List<String> = listOf(
        "com.stub.StubApp.interface20",
        "com.qihoo.util.StubApp.interface20"
    )

    override val forbiddenNeutralizeList: List<String> = listOf(
        "com.stub.StubApp.load",
        "com.qihoo.util.StubApp.load"
    )

    override val diagnosticHooks: List<String> = emptyList()
}

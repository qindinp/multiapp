package com.multiapp.core.hook.antidetection

/**
 * 加固壳家族枚举。
 *
 * Phase1 类型化检测引擎的核心枚举：所有检测信号（SO/DEX/manifest/runtime）最终
 * 都会聚合到一个 [PackerFamily]。家族决定运行时适配（按家族路由到特定
 * PackerRuntime）与 evidence 记录。
 */
enum class PackerFamily {

    /** 360 加固（奇虎）。含 libjiagu_*、libslib、libdexvmp、libpatchtools 等变体。 */
    QIHOO_360,

    /** 腾讯乐固 / 应用加固（legu）。libshella-* / libshellx-*。 */
    TENCENT_JIAGU,

    /** 爱加密（iJiami）。libexec.so。 */
    IJIMAI,

    /** 梆梆加固（Bangcle / SecShell）。 */
    BANGCLE,

    /** 阿里聚安全 / 阿里加固。libsgmain.so。 */
    ALIBABA,

    /** 检测到特征但无法归入已知家族。 */
    OTHER,

    /** 未检测到任何加固特征。 */
    UNKNOWN;

    /**
     * 与旧版 String 标签的映射（兼容 [PackerDetector.detect] 返回值、
     * [PackerDetectionBypass] 的 when 分支与既有测试断言）。
     */
    val legacyLabel: String
        get() = when (this) {
            QIHOO_360 -> "360 Jiagu"
            TENCENT_JIAGU -> "Tencent Jiagu"
            IJIMAI -> "iJiami"
            BANGCLE -> "Bangcle"
            ALIBABA -> "Alibaba"
            OTHER -> "other"
            UNKNOWN -> "unknown"
        }

    companion object {
        /** 从旧版 String 标签解析家族；未知标签归入 [OTHER]。 */
        fun fromLegacyLabel(label: String?): PackerFamily = when (label) {
            "360 Jiagu" -> QIHOO_360
            "Tencent Jiagu" -> TENCENT_JIAGU
            "iJiami" -> IJIMAI
            "Bangcle" -> BANGCLE
            "Alibaba" -> ALIBABA
            "unknown", null -> UNKNOWN
            else -> OTHER
        }
    }
}

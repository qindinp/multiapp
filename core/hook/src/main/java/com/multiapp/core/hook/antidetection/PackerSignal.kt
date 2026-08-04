package com.multiapp.core.hook.antidetection

/**
 * 检测信号来源层级。
 *
 * 层级同时表达置信度优先级：L1（SO 名）> L2（DEX 类名）> L3（manifest），
 * L4（运行时证据）由 PackerRuntime 执行期补充。
 */
enum class PackerSignalLevel {
    /** native 库名称命中。 */
    L1_SO,

    /** DEX 类名/包名特征命中。 */
    L2_DEX,

    /** AndroidManifest 组件特征命中。 */
    L3_MANIFEST,

    /** 运行时证据（由 PackerRuntime 执行期补充，Phase1 保留枚举位）。 */
    L4_RUNTIME;
}

/**
 * 置信度。
 *
 * 规则：L1 so 名命中 → [HIGH]；L2/L3 → [MEDIUM]；多信号可提升为 [HIGH]。
 */
enum class PackerConfidence {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * 检测到加固家族后的适配策略。
 *
 * - [ROUTE_SPECIFIC]：按家族路由到专用 PackerRuntime（如 360 → JiaguRuntime）。
 * - [ROUTE_GENERIC]：回退到 GenericPackerRuntime 的通用加固引导路径。
 * - [BYPASS]：未识别加固 / 检测失败，跳过加固适配（family = UNKNOWN）。
 */
enum class PackerDetectionStrategy {
    ROUTE_SPECIFIC,
    ROUTE_GENERIC,
    BYPASS
}

/**
 * 单条加固检测信号。
 *
 * @param family   命中的加固家族
 * @param level    信号来源层级
 * @param pattern  命中的具体特征（so 文件名 / 类名模式 / manifest 模式）
 * @param confidence 本条信号的置信度
 */
data class PackerSignal(
    val family: PackerFamily,
    val level: PackerSignalLevel,
    val pattern: String,
    val confidence: PackerConfidence
)

/**
 * 类型化加固检测结果。
 *
 * @param family     聚合后的家族（[PackerFamily.UNKNOWN] 表示未识别）
 * @param confidence 聚合置信度
 * @param signals    三级扫描收集到的全部信号（有序，按扫描优先级）
 * @param strategy   适配策略（由 family/confidence 推导）
 */
data class PackerDetectionEvidence(
    val family: PackerFamily,
    val confidence: PackerConfidence,
    val signals: List<PackerSignal>,
    val strategy: PackerDetectionStrategy
) {

    /** 是否识别到任何加固特征。 */
    val detected: Boolean
        get() = family != PackerFamily.UNKNOWN && family != PackerFamily.OTHER

    companion object {
        /** 未识别 / 检测失败的缺省 evidence：BYPASS 策略。 */
        val UNKNOWN = PackerDetectionEvidence(
            family = PackerFamily.UNKNOWN,
            confidence = PackerConfidence.LOW,
            signals = emptyList(),
            strategy = PackerDetectionStrategy.BYPASS
        )

        /**
         * 从旧版 String 标签构造 evidence（兼容路径）。
         * 已知标签 → ROUTE_SPECIFIC；unknown → BYPASS。
         */
        fun fromLegacy(label: String?): PackerDetectionEvidence {
            val family = PackerFamily.fromLegacyLabel(label)
            if (family == PackerFamily.UNKNOWN || family == PackerFamily.OTHER) {
                return UNKNOWN
            }
            return PackerDetectionEvidence(
                family = family,
                confidence = PackerConfidence.MEDIUM,
                signals = emptyList(),
                strategy = PackerDetectionStrategy.ROUTE_SPECIFIC
            )
        }
    }
}

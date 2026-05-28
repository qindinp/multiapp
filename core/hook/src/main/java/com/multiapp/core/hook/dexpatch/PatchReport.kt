package com.multiapp.core.hook.dexpatch

/**
 * DEX Patch 结果报告
 */
data class PatchReport(
    val patchedMethodCount: Int,
    val totalDexFiles: Int,
    val errors: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = errors.isEmpty() && patchedMethodCount >= 0
}

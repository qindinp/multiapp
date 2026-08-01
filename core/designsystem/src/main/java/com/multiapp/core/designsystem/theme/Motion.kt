package com.multiapp.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

// ════════════════════════════════════════════════════════════════
// MultiApp Motion — MIUI 12 动效原则（DESIGN.md §1.5）
// 四档时长 + 两条曲线 + spring；动效参数禁止引入第五种曲线
// 降级：检测到「减少动态效果」时统一 100ms 淡入（见 ReducedMotion.kt 或调用方判断）
// ════════════════════════════════════════════════════════════════

object MultiAppMotion {
    /** Micro 按压/开关 100ms */
    const val DurationMicro = 100
    /** Short 局部反馈 200ms */
    const val DurationShort = 200
    /** Base 元素转场 300ms */
    const val DurationBase = 300
    /** Long 页面转场 450ms */
    const val DurationLong = 450

    /** 标准缓动（Short 及以下）cubic-bezier(0.25, 0.1, 0.25, 1) */
    val StandardEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** 强调缓动（Base/Long 转场）cubic-bezier(0.2, 0.0, 0.0, 1.0) */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 物理回弹 spring（stiffness 180, damping 22），点赞/Tab 指示条 */
    fun <T> springSpec(): FiniteAnimationSpec<T> = spring(
        stiffness = 180f,
        dampingRatio = 0.22f // DESIGN.md damping=22 → 阻尼比 0.22，轻微回弹
    )
}

/** 短反馈动画规格（按压、卡片 hover） */
fun <T> shortSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = MultiAppMotion.DurationShort,
    easing = MultiAppMotion.StandardEasing
)

/** 基础转场动画规格（弹窗、抽屉、列表入场） */
fun <T> baseSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = MultiAppMotion.DurationBase,
    easing = MultiAppMotion.EmphasizedEasing
)

/** 长转场动画规格（页面光锥过渡） */
fun <T> longSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = MultiAppMotion.DurationLong,
    easing = MultiAppMotion.EmphasizedEasing
)

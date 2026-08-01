package com.multiapp.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ════════════════════════════════════════════════════════════════
// MultiApp Shapes — MIUI 12 圆角体系（DESIGN.md §6.1）
// 原则：圆角随面积增大而增大；同区块只用一个主圆角等级
// ════════════════════════════════════════════════════════════════

val RadiusXs = RoundedCornerShape(4.dp)     // --radius-xs 标签内角、进度条
val RadiusSm = RoundedCornerShape(8.dp)     // --radius-sm 输入框、小按钮
val RadiusMd = RoundedCornerShape(12.dp)    // --radius-md 标准按钮、次级卡片
val RadiusLg = RoundedCornerShape(16.dp)    // --radius-lg 弹窗、大按钮
val RadiusXl = RoundedCornerShape(28.dp)    // --radius-xl MIUI 12 标志性大圆角：主卡片

val MultiAppShapes = Shapes(
    extraSmall = RadiusXs,
    small = RadiusSm,
    medium = RadiusMd,
    large = RadiusLg,
    extraLarge = RadiusXl
)

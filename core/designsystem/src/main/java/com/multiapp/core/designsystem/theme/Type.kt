package com.multiapp.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════
// MultiApp Typography — MIUI 12 排版体系（DESIGN.md §3）
// 主字体：MiSans（开源可商用）——接入后仅需替换 MiUiFontFamily 一处
// 当前按决策回退系统字体，token 结构已就位
// 字重收敛：400 / 600 / 700 三档（DESIGN.md §3.4）
// ════════════════════════════════════════════════════════════════

/** 替换为 MiSans 时改这一行即可：FontFamily("MiSans", ...) */
val MiUiFontFamily: FontFamily = FontFamily.SansSerif

val MultiAppTypography = Typography(
    // Display Hero 34/700（§3.2）
    displayLarge = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp
    ),
    // h1 28/600
    headlineLarge = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp
    ),
    // h2 24/600
    headlineMedium = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp
    ),
    // h3 20/600
    headlineSmall = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.1).sp
    ),
    // h4 18/600
    titleLarge = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    // h5 16/600
    titleMedium = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    // h6 14/600
    titleSmall = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    // Body L 16/400
    bodyLarge = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 26.sp
    ),
    // Body 15/400（中文正文舒适区）
    bodyMedium = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp
    ),
    // Body S 14/400
    bodySmall = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp
    ),
    // 按钮文字 15/600（§4.1）
    labelLarge = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    // Caption 12/600（标签、徽标）
    labelMedium = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp
    ),
    // Nano 11/400（角标）
    labelSmall = TextStyle(
        fontFamily = MiUiFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp
    )
)

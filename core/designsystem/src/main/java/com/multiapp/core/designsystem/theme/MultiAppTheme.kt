package com.multiapp.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ════════════════════════════════════════════════════════════════
// MultiApp Theme — MIUI 12 通感设计（DESIGN.md）
// 决策（2026-08-01 项目负责人确认）：
//   1. dynamicColor 优先：Android 12+ 使用壁纸动态取色（保持现状）
//   2. 静态品牌 scheme 为 Android 11- 及无动态色时的 fallback
//      采用 DESIGN.md 品牌橙 #FF6900 系（浅色+深色全套）
//   3. 字体暂回退系统字体（MiSans 接入仅需改 Type.kt 的 MiUiFontFamily）
// ════════════════════════════════════════════════════════════════

// ── Light Scheme · 品牌橙（DESIGN.md §2.1-2.4）──
private val LightColorScheme = lightColorScheme(
    primary = Orange500,
    onPrimary = Color.White,
    primaryContainer = Orange50,
    onPrimaryContainer = Orange800,
    secondary = AccentBlue500,
    onSecondary = Color.White,
    secondaryContainer = AccentBlue50,
    onSecondaryContainer = AccentBlue800,
    tertiary = SuccessGreen,
    onTertiary = Color.White,
    tertiaryContainer = SuccessBg,
    onTertiaryContainer = SuccessGreen700,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorBg,
    onErrorContainer = ErrorRed700,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightTextDisabled,
    outlineVariant = LightBorder,
)

// ── Dark Scheme · 深灰底 + 品牌色提亮（DESIGN.md §2.5）──
private val DarkColorScheme = darkColorScheme(
    primary = DarkOrange,
    onPrimary = DarkOnOrange,
    primaryContainer = Orange900.copy(alpha = 0.35f),
    onPrimaryContainer = Orange200,
    secondary = DarkAccent,
    onSecondary = DarkOnAccent,
    secondaryContainer = AccentBlue900.copy(alpha = 0.45f),
    onSecondaryContainer = AccentBlue100,
    tertiary = DarkSuccess,
    onTertiary = Color(0xFF0A3A22),
    tertiaryContainer = Color(0xFF12352A),
    onTertiaryContainer = Color(0xFFA0E8C8),
    error = DarkError,
    onError = Color(0xFF3A0010),
    errorContainer = Color(0xFF3D1B1B),
    onErrorContainer = Color(0xFFFFB4B4),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkTextDisabled,
    outlineVariant = DarkBorder,
)

/**
 * MultiApp Material 3 Theme — MIUI 12 通感设计
 *
 * @param darkTheme 深色模式
 * @param dynamicColor Android 12+ 是否启用壁纸动态取色（决策：优先，品牌色仅作 fallback）
 */
@Composable
fun MultiAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MultiAppTypography,
        shapes = MultiAppShapes,
        content = content
    )
}

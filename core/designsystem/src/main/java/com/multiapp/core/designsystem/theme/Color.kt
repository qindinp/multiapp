package com.multiapp.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// ════════════════════════════════════════════════════════════════
// MultiApp Brand Colors — MIUI 12 通感设计（DESIGN.md §2）
// 静态品牌 scheme：Android 11 及以下 / dynamicColor=false 时使用
// Android 12+ 由 dynamicColor 壁纸取色覆盖（MainActivity 处决策）
// ════════════════════════════════════════════════════════════════

// ── Primary · MIUI 橙 #FF6900（DESIGN.md §2.1 色阶）──
val Orange50 = Color(0xFFFFF7EE)    // --color-primary-50
val Orange100 = Color(0xFFFFEBD4)   // --color-primary-100
val Orange200 = Color(0xFFFFD4A8)   // --color-primary-200
val Orange300 = Color(0xFFFFBA73)   // --color-primary-300（深色模式强调）
val Orange400 = Color(0xFFFF9A3D)   // --color-primary-400
val Orange500 = Color(0xFFFF6900)   // --color-primary-500 品牌橙基准
val Orange600 = Color(0xFFE65C00)   // --color-primary-600 hover/按压
val Orange700 = Color(0xFFC74E00)   // --color-primary-700 正文级橙（白底 4.65:1）
val Orange800 = Color(0xFF9E3D00)   // --color-primary-800
val Orange900 = Color(0xFF7A2E00)   // --color-primary-900

// ── Accent · 光锥蓝 #1C9BF6（DESIGN.md §2.2）──
val AccentBlue50 = Color(0xFFE8F4FE)   // --color-accent-50
val AccentBlue100 = Color(0xFFC8E7FD)  // --color-accent-100
val AccentBlue300 = Color(0xFF5FB9F8)  // --color-accent-300（深色模式强调）
val AccentBlue500 = Color(0xFF1C9BF6)  // --color-accent-500 链接/信息
val AccentBlue600 = Color(0xFF0B82E0)  // --color-accent-600 链接按压/focus ring（白底 3.97:1）
val AccentBlue700 = Color(0xFF0A6BBC)  // --color-accent-700
val AccentBlue800 = Color(0xFF0B5392)
val AccentBlue900 = Color(0xFF0A3E6E)

// ── Semantic · 语义色（DESIGN.md §2.3）──
val SuccessGreen = Color(0xFF00A862)     // --color-success
val SuccessGreen700 = Color(0xFF00824C)  // --color-success-700（白底 4.89:1）
val SuccessBg = Color(0xFFE6F7EF)        // --color-success-bg
val WarningAmber = Color(0xFFFF9F1C)     // --color-warning
val WarningAmber700 = Color(0xFFA05E00)  // --color-warning-700（白底 5.13:1）
val WarningBg = Color(0xFFFFF3E0)        // --color-warning-bg
val ErrorRed = Color(0xFFF53535)         // --color-error
val ErrorRed700 = Color(0xFFD92626)      // --color-error-700（白底 4.93:1）
val ErrorBg = Color(0xFFFFEBEB)          // --color-error-bg

// ── Neutral · 中性灰阶 浅色（DESIGN.md §2.4）──
val LightBackground = Color(0xFFF5F5F7)        // --color-bg
val LightSurface = Color(0xFFFFFFFF)           // --color-surface
val LightSurfaceVariant = Color(0xFFF0F0F2)    // --color-surface-variant
val LightBorder = Color(0xFFE8E8EC)            // --color-border
val LightDivider = Color(0xFFF0F0F3)           // --color-divider
val LightTextPrimary = Color(0xFF26282C)       // --color-text-primary（白底 14.8:1）
val LightTextSecondary = Color(0xFF737680)     // --color-text-secondary（白底 4.53:1 ✅）
val LightTextTertiary = Color(0xFF8F939C)      // --color-text-tertiary（3.08:1，仅非关键信息）
val LightTextDisabled = Color(0xFFC4C7CC)      // --color-text-disabled

// ── Neutral · 深色模式（DESIGN.md §2.5，拒绝纯黑）──
val DarkBackground = Color(0xFF17181A)         // --color-bg（MIUI 深色 2.0 深灰）
val DarkSurface = Color(0xFF1F2124)            // --color-surface
val DarkSurfaceVariant = Color(0xFF26282C)     // --color-surface-variant
val DarkBorder = Color(0xFF33363B)             // --color-border
val DarkDivider = Color(0xFF2B2E33)            // --color-divider
val DarkTextPrimary = Color(0xFFF5F6F7)        // --color-text-primary（深底 16.3:1）
val DarkTextSecondary = Color(0xFFA9ADB5)      // --color-text-secondary（深底 7.9:1 ✅）
val DarkTextTertiary = Color(0xFF8A8F99)       // --color-text-tertiary（深底 5.5:1 ✅）
val DarkTextDisabled = Color(0xFF4A4E55)       // --color-text-disabled

// ── 深色模式品牌色提亮策略（DESIGN.md §2.5：500→300/400 档）──
val DarkOrange = Color(0xFFFFBA73)             // 深色模式 primary（Orange300 提亮）
val DarkOnOrange = Color(0xFF4A2200)           // 深橙容器上的深色文字
val DarkAccent = Color(0xFF5FB9F8)             // 深色模式 accent（Accent300）
val DarkOnAccent = Color(0xFF0A3E6E)
val DarkSuccess = Color(0xFF34C98A)            // 深色模式 success
val DarkError = Color(0xFFFF6B6B)              // 深色模式 error

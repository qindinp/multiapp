package com.multiapp.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════
// MultiApp Brand Colors — HyperOS Soft Blue-Gray
// ════════════════════════════════════════════════════════════════

// Primary: Soft Blue-Gray
private val HyperPrimary = Color(0xFF5B6B8A)
private val HyperOnPrimary = Color(0xFFFFFFFF)
private val HyperPrimaryContainer = Color(0xFFD8E2F0)
private val HyperOnPrimaryContainer = Color(0xFF1A2A44)

// Secondary: Muted Lavender-Gray
private val HyperSecondary = Color(0xFF7B7D9A)
private val HyperOnSecondary = Color(0xFFFFFFFF)
private val HyperSecondaryContainer = Color(0xFFE0E0F0)
private val HyperOnSecondaryContainer = Color(0xFF2A2A44)

// Tertiary: Warm Sage
private val HyperTertiary = Color(0xFF6B8A7B)
private val HyperOnTertiary = Color(0xFFFFFFFF)
private val HyperTertiaryContainer = Color(0xFFD4E8DC)
private val HyperOnTertiaryContainer = Color(0xFF1A3A2A)

// Neutrals — Warm Gray
private val HyperLightBackground = Color(0xFFF2F2F5)
private val HyperLightSurface = Color(0xFFF7F7FA)
private val HyperLightSurfaceVariant = Color(0xFFECECF0)
private val HyperOnSurface = Color(0xFF1A1B2E)
private val HyperOnSurfaceVariant = Color(0xFF6B6B80)
private val HyperOutline = Color(0xFFC8C8D8)
private val HyperOutlineVariant = Color(0xFFE0E0EA)

// Error
private val HyperError = Color(0xFFD94452)
private val HyperOnError = Color(0xFFFFFFFF)
private val HyperErrorContainer = Color(0xFFFCE4E8)
private val HyperOnErrorContainer = Color(0xFF5C1020)

// Dark theme — Deep Blue-Gray (not pure black)
private val HyperDarkPrimary = Color(0xFF8B9DC3)
private val HyperDarkOnPrimary = Color(0xFF1A1A2E)
private val HyperDarkPrimaryContainer = Color(0xFF2A3A5C)
private val HyperDarkOnPrimaryContainer = Color(0xFFD8E2F0)
private val HyperDarkSecondary = Color(0xFFA0A0C0)
private val HyperDarkOnSecondary = Color(0xFF1A1A2E)
private val HyperDarkSecondaryContainer = Color(0xFF3A3A58)
private val HyperDarkOnSecondaryContainer = Color(0xFFE0E0F0)
private val HyperDarkTertiary = Color(0xFF8AAA9B)
private val HyperDarkOnTertiary = Color(0xFF1A2A22)
private val HyperDarkTertiaryContainer = Color(0xFF2A4A3A)
private val HyperDarkOnTertiaryContainer = Color(0xFFD4E8DC)
private val HyperDarkBackground = Color(0xFF1C1C28)
private val HyperDarkSurface = Color(0xFF252538)
private val HyperDarkSurfaceVariant = Color(0xFF2E2E44)
private val HyperOnDarkSurface = Color(0xFFE4E4F0)
private val HyperOnDarkSurfaceVariant = Color(0xFFA0A0B8)
private val HyperDarkOutline = Color(0xFF444460)
private val HyperDarkOutlineVariant = Color(0xFF363650)
private val HyperDarkError = Color(0xFFFF8A98)
private val HyperDarkOnError = Color(0xFF3A0010)
private val HyperDarkErrorContainer = Color(0xFF7A1030)
private val HyperDarkOnErrorContainer = Color(0xFFFCE4E8)

private val LightColorScheme = lightColorScheme(
    primary = HyperPrimary,
    onPrimary = HyperOnPrimary,
    primaryContainer = HyperPrimaryContainer,
    onPrimaryContainer = HyperOnPrimaryContainer,
    secondary = HyperSecondary,
    onSecondary = HyperOnSecondary,
    secondaryContainer = HyperSecondaryContainer,
    onSecondaryContainer = HyperOnSecondaryContainer,
    tertiary = HyperTertiary,
    onTertiary = HyperOnTertiary,
    tertiaryContainer = HyperTertiaryContainer,
    onTertiaryContainer = HyperOnTertiaryContainer,
    error = HyperError,
    onError = HyperOnError,
    errorContainer = HyperErrorContainer,
    onErrorContainer = HyperOnErrorContainer,
    background = HyperLightBackground,
    onBackground = HyperOnSurface,
    surface = HyperLightSurface,
    onSurface = HyperOnSurface,
    surfaceVariant = HyperLightSurfaceVariant,
    onSurfaceVariant = HyperOnSurfaceVariant,
    outline = HyperOutline,
    outlineVariant = HyperOutlineVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = HyperDarkPrimary,
    onPrimary = HyperDarkOnPrimary,
    primaryContainer = HyperDarkPrimaryContainer,
    onPrimaryContainer = HyperDarkOnPrimaryContainer,
    secondary = HyperDarkSecondary,
    onSecondary = HyperDarkOnSecondary,
    secondaryContainer = HyperDarkSecondaryContainer,
    onSecondaryContainer = HyperDarkOnSecondaryContainer,
    tertiary = HyperDarkTertiary,
    onTertiary = HyperDarkOnTertiary,
    tertiaryContainer = HyperDarkTertiaryContainer,
    onTertiaryContainer = HyperDarkOnTertiaryContainer,
    error = HyperDarkError,
    onError = HyperDarkOnError,
    errorContainer = HyperDarkErrorContainer,
    onErrorContainer = HyperDarkOnErrorContainer,
    background = HyperDarkBackground,
    onBackground = HyperOnDarkSurface,
    surface = HyperDarkSurface,
    onSurface = HyperOnDarkSurface,
    surfaceVariant = HyperDarkSurfaceVariant,
    onSurfaceVariant = HyperOnDarkSurfaceVariant,
    outline = HyperDarkOutline,
    outlineVariant = HyperDarkOutlineVariant,
)

private val MultiAppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.15.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/**
 * MultiApp Material 3 Theme — HyperOS Soft Blue-Gray
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
        content = content
    )
}

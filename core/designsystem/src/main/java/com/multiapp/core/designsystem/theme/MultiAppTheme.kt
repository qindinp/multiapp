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
// MultiApp Brand Colors — Deep Indigo + Electric Violet + Cyan
// ════════════════════════════════════════════════════════════════

// Primary: Deep Indigo
private val DeepIndigo = Color(0xFF6366F1)
private val LightIndigo = Color(0xFF818CF8)
private val OnIndigo = Color(0xFFFFFFFF)
private val IndigoContainer = Color(0xFFE0E0FF)
private val OnIndigoContainer = Color(0xFF1A1A5E)

// Secondary: Electric Violet
private val ElectricViolet = Color(0xFF8B5CF6)
private val LightViolet = Color(0xFFA78BFA)
private val OnViolet = Color(0xFFFFFFFF)
private val VioletContainer = Color(0xFFEDE5FF)
private val OnVioletContainer = Color(0xFF2D1A6E)

// Tertiary: Cyan Accent
private val CyanAccent = Color(0xFF06B6D4)
private val LightCyan = Color(0xFF22D3EE)
private val OnCyan = Color(0xFFFFFFFF)
private val CyanContainer = Color(0xFFCCFBFF)
private val OnCyanContainer = Color(0xFF003D47)

// Neutrals
private val CleanWhiteBg = Color(0xFFFAFBFF)
private val PureWhiteSurface = Color(0xFFFFFFFF)
private val SoftLavender = Color(0xFFF1F0FF)
private val OnSurface = Color(0xFF1A1B2E)
private val OnSurfaceVariant = Color(0xFF5C5C7A)
private val OutlineColor = Color(0xFFCCCCE0)
private val OutlineVariantColor = Color(0xFFE2E2F0)

// Error
private val CoralRed = Color(0xFFEF4444)
private val OnCoralRed = Color(0xFFFFFFFF)
private val CoralRedContainer = Color(0xFFFEE2E2)
private val OnCoralRedContainer = Color(0xFF7F1D1D)

// Dark theme
private val DarkBg = Color(0xFF0F0F23)
private val DarkSurface = Color(0xFF1A1A3E)
private val DarkSurfaceVariant = Color(0xFF262650)
private val OnDarkSurface = Color(0xFFE4E4F0)
private val OnDarkSurfaceVariant = Color(0xFFA0A0C0)
private val DarkOutline = Color(0xFF3D3D60)
private val DarkOutlineVariant = Color(0xFF2E2E50)

private val LightColorScheme = lightColorScheme(
    primary = DeepIndigo,
    onPrimary = OnIndigo,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = OnIndigoContainer,
    secondary = ElectricViolet,
    onSecondary = OnViolet,
    secondaryContainer = VioletContainer,
    onSecondaryContainer = OnVioletContainer,
    tertiary = CyanAccent,
    onTertiary = OnCyan,
    tertiaryContainer = CyanContainer,
    onTertiaryContainer = OnCyanContainer,
    error = CoralRed,
    onError = OnCoralRed,
    errorContainer = CoralRedContainer,
    onErrorContainer = OnCoralRedContainer,
    background = CleanWhiteBg,
    onBackground = OnSurface,
    surface = PureWhiteSurface,
    onSurface = OnSurface,
    surfaceVariant = SoftLavender,
    onSurfaceVariant = OnSurfaceVariant,
    outline = OutlineColor,
    outlineVariant = OutlineVariantColor,
)

private val DarkColorScheme = darkColorScheme(
    primary = LightIndigo,
    onPrimary = Color(0xFF2A2A6E),
    primaryContainer = Color(0xFF3A3A8E),
    onPrimaryContainer = IndigoContainer,
    secondary = LightViolet,
    onSecondary = Color(0xFF2D1A6E),
    secondaryContainer = Color(0xFF4A2E8E),
    onSecondaryContainer = VioletContainer,
    tertiary = LightCyan,
    onTertiary = Color(0xFF003D47),
    tertiaryContainer = Color(0xFF005768),
    onTertiaryContainer = CyanContainer,
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkBg,
    onBackground = OnDarkSurface,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val MultiAppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.15.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/**
 * MultiApp Material 3 Theme — Deep Indigo + Electric Violet + Cyan
 */
@Composable
fun MultiAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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

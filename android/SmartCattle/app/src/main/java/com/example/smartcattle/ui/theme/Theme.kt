package com.example.smartcattle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = ForestGreenLight,
    onPrimary = Color.White,
    primaryContainer = ForestGreenContainerLight,
    onPrimaryContainer = Color(0xFF04210B),
    secondary = EarthBrownLight,
    onSecondary = Color.White,
    secondaryContainer = EarthContainerLight,
    onSecondaryContainer = Color(0xFF1D1B16),
    tertiary = RiverBlueLight,
    onTertiary = Color.White,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderLight,
    error = HealthCriticalLight,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = ForestGreenDark,
    onPrimary = Color(0xFF052E16),
    primaryContainer = ForestGreenContainerDark,
    onPrimaryContainer = Color(0xFFDCFCE7),
    secondary = EarthBrownDark,
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = EarthContainerDark,
    onSecondaryContainer = Color(0xFFEFEBE9),
    tertiary = RiverBlueDark,
    onTertiary = Color(0xFF082F49),
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderDark,
    error = HealthCriticalDark,
    onError = Color.White
)

data class HealthColors(
    val healthy: Color,
    val warning: Color,
    val critical: Color,
    val offline: Color,
    val cyanAccent: Color,
    val purpleAccent: Color
)

val LocalHealthColors = staticCompositionLocalOf {
    HealthColors(
        healthy = HealthHealthyDark,
        warning = HealthWarningDark,
        critical = HealthCriticalDark,
        offline = HealthOfflineDark,
        cyanAccent = HealthAccentCyan,
        purpleAccent = HealthAccentPurple
    )
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

@Composable
fun SmartCattleTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val healthColors = if (darkTheme) {
        HealthColors(
            healthy = HealthHealthyDark,
            warning = HealthWarningDark,
            critical = HealthCriticalDark,
            offline = HealthOfflineDark,
            cyanAccent = HealthAccentCyan,
            purpleAccent = HealthAccentPurple
        )
    } else {
        HealthColors(
            healthy = HealthHealthyLight,
            warning = HealthWarningLight,
            critical = HealthCriticalLight,
            offline = HealthOfflineLight,
            cyanAccent = HealthAccentCyan,
            purpleAccent = HealthAccentPurple
        )
    }

    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalHealthColors provides healthColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SmartCattleTypography,
            shapes = SmartCattleShapes,
            content = content
        )
    }
}

val MaterialTheme.healthColors: HealthColors
    @Composable
    @ReadOnlyComposable
    get() = LocalHealthColors.current

val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current

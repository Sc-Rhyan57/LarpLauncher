package com.larp.launcher.core.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = LarpPrimary,
    onPrimary = LarpOnPrimary,
    primaryContainer = LarpPrimaryContainer,
    onPrimaryContainer = LarpOnPrimaryContainer,
    secondary = LarpSecondary,
    onSecondary = LarpOnSecondary,
    secondaryContainer = LarpSecondaryContainer,
    onSecondaryContainer = LarpOnSecondaryContainer,
    tertiary = LarpTertiary,
    onTertiary = LarpOnTertiary,
    tertiaryContainer = LarpTertiaryContainer,
    onTertiaryContainer = LarpOnTertiaryContainer,
    error = LarpError,
    onError = LarpOnError,
    errorContainer = LarpErrorContainer,
    onErrorContainer = LarpOnErrorContainer,
    background = LarpBackground,
    onBackground = LarpOnBackground,
    surface = LarpSurface,
    onSurface = LarpOnSurface,
    surfaceVariant = LarpSurfaceVariant,
    onSurfaceVariant = LarpOnSurfaceVariant,
    outline = LarpOutline,
    outlineVariant = LarpOutlineVariant,
    surfaceContainer = LarpSurfaceContainer,
    surfaceContainerHigh = LarpSurfaceContainerHigh,
    surfaceContainerHighest = LarpSurfaceContainerHighest
)

private val DarkColors = darkColorScheme(
    primary = LarpPrimaryDark,
    onPrimary = LarpOnPrimaryDark,
    primaryContainer = LarpPrimaryContainerDark,
    onPrimaryContainer = LarpOnPrimaryContainerDark,
    secondary = LarpSecondaryDark,
    onSecondary = LarpOnSecondaryDark,
    secondaryContainer = LarpSecondaryContainerDark,
    onSecondaryContainer = LarpOnSecondaryContainerDark,
    tertiary = LarpTertiaryDark,
    onTertiary = LarpOnTertiaryDark,
    tertiaryContainer = LarpTertiaryContainerDark,
    onTertiaryContainer = LarpOnTertiaryContainerDark,
    error = LarpErrorDark,
    onError = LarpOnErrorDark,
    errorContainer = LarpErrorContainerDark,
    onErrorContainer = LarpOnErrorContainerDark,
    background = LarpBackgroundDark,
    onBackground = LarpOnBackgroundDark,
    surface = LarpSurfaceDark,
    onSurface = LarpOnSurfaceDark,
    surfaceVariant = LarpSurfaceVariantDark,
    onSurfaceVariant = LarpOnSurfaceVariantDark,
    outline = LarpOutlineDark,
    outlineVariant = LarpOutlineVariantDark,
    surfaceContainer = LarpSurfaceContainerDark,
    surfaceContainerHigh = LarpSurfaceContainerHighDark,
    surfaceContainerHighest = LarpSurfaceContainerHighestDark
)

@Composable
fun LarpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val colorScheme = if (darkTheme) base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0B),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF222222)
    ) else base

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LarpTypography,
        content = content
    )
}

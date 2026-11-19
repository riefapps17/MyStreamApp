package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanPulse,
    onPrimary = OnCyanPulse,
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = CoralAmber,
    onSecondary = OnCoralAmber,
    secondaryContainer = CoralContainer,
    onSecondaryContainer = OnCoralContainer,
    tertiary = ElectricSky,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = OutlineDark,
    outlineVariant = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = OnLightPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightSurface,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = OnLightSecondaryContainer,
    tertiary = ElectricSky,
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBg,
    surfaceContainer = LightSurfaceElevated,
    surfaceContainerHigh = LightSurfaceElevated,
    outline = OutlineLight,
    outlineVariant = OutlineLight
)

@Composable
fun StreamVaultTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StreamVaultTypography,
        content = content
    )
}

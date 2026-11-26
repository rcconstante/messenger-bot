package com.pagerelay.bot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PageRelaySemanticColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val emergency: Color,
    val emergencyContainer: Color,
    val onEmergencyContainer: Color,
    val accentSurface: Color
)

private val LightSemanticColors = PageRelaySemanticColors(
    success = EmeraldSuccess,
    successContainer = EmeraldContainerLight,
    onSuccessContainer = EmeraldOnContainerLight,
    warning = AmberWarning,
    warningContainer = AmberContainerLight,
    onWarningContainer = AmberOnContainerLight,
    emergency = CrimsonStop,
    emergencyContainer = CrimsonContainerLight,
    onEmergencyContainer = CrimsonOnContainerLight,
    accentSurface = LightSurfaceTint
)

private val DarkSemanticColors = PageRelaySemanticColors(
    success = EmeraldSuccessLight,
    successContainer = EmeraldContainerDark,
    onSuccessContainer = EmeraldOnContainerDark,
    warning = AmberWarningLight,
    warningContainer = AmberContainerDark,
    onWarningContainer = AmberOnContainerDark,
    emergency = CrimsonStopLight,
    emergencyContainer = CrimsonContainerDark,
    onEmergencyContainer = CrimsonOnContainerDark,
    accentSurface = DarkSurfaceElevated
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

private val LightColorScheme = lightColorScheme(
    primary = CobaltBlue,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = CobaltBlueDark,
    secondary = RelayTeal,
    onSecondary = Color.White,
    secondaryContainer = RelayTealContainerLight,
    onSecondaryContainer = RelayTealContainerDark,
    tertiary = AmberWarning,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    error = CrimsonStop,
    onError = Color.White,
    errorContainer = CrimsonContainerLight,
    onErrorContainer = CrimsonOnContainerLight,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceTint,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = LightOutlineVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = CobaltBlueLight,
    onPrimary = Color(0xFF0A1936),
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = CobaltContainerLight,
    secondary = RelayTealLight,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = RelayTealContainerDark,
    onSecondaryContainer = RelayTealContainerLight,
    tertiary = AmberWarningLight,
    onTertiary = Color(0xFF331500),
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    error = CrimsonStopLight,
    onError = Color(0xFF3F0606),
    errorContainer = CrimsonContainerDark,
    onErrorContainer = CrimsonOnContainerDark,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkOutlineVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

@Composable
fun PageRelayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PageRelayTypography,
            content = content
        )
    }
}

object PageRelayThemeExtras {
    val semantic: PageRelaySemanticColors
        @Composable get() = LocalSemanticColors.current
}

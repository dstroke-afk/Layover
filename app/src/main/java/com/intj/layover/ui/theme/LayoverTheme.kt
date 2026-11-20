package com.intj.layover.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class LayoverCustomColors(
    val darkBackground: Color = DarkNavyBackground,
    val cardSurface: Color = ElevatedCardSurface,
    val cyanPrimary: Color = CyanPrimary,
    val cyanAccent: Color = CyanAccent,
    val incomingBubble: Color = IncomingBubbleSurface,
    val outgoingBubble: Color = OutgoingCyanBubble,
    val mutedText: Color = MutedText,
    val lightText: Color = LightText,
)

val LocalLayoverCustomColors = staticCompositionLocalOf { LayoverCustomColors() }

object LayoverTheme {
    val colors: LayoverCustomColors
        @Composable
        get() = LocalLayoverCustomColors.current
}

private val LayoverDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = DarkNavyBackground,
    primaryContainer = OutgoingCyanBubble,
    onPrimaryContainer = LightText,
    secondary = CyanAccent,
    onSecondary = DarkNavyBackground,
    background = DarkNavyBackground,
    onBackground = LightText,
    surface = ElevatedCardSurface,
    onSurface = LightText,
    surfaceVariant = IncomingBubbleSurface,
    onSurfaceVariant = MutedText,
    outline = MutedText,
)

@Composable
fun LayoverTheme(
    content: @Composable () -> Unit,
) {
    val customColors = LayoverCustomColors()

    CompositionLocalProvider(
        LocalLayoverCustomColors provides customColors
    ) {
        MaterialTheme(
            colorScheme = LayoverDarkColorScheme,
            typography = Typography,
            content = content
        )
    }
}

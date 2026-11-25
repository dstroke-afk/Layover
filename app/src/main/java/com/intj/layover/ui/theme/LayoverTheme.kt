package com.intj.layover.ui.theme

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
data class LayoverCustomColors(
    val isDark: Boolean = true,
    val darkBackground: Color = DarkNavyBackground,
    val cardSurface: Color = ElevatedCardSurface,
    val cyanPrimary: Color = CyanPrimary,
    val cyanAccent: Color = CyanAccent,
    val incomingBubble: Color = IncomingBubbleSurface,
    val outgoingBubble: Color = OutgoingCyanBubble,
    val mutedText: Color = MutedText,
    val lightText: Color = LightText,
    val gradientStart: Color = DarkNavyBackground,
    val gradientMid: Color = Color(0xFF0E1A29),
    val gradientEnd: Color = DarkNavyBackground,
    val overlayAlpha: Float = 0.12f,
)

private val LightBackground = Color(0xFFF4F8FB)
private val LightGradientStart = Color(0xFFF8FBFE)
private val LightGradientMid = Color(0xFFEAF2F8)
private val LightGradientEnd = Color(0xFFF4F8FB)
private val LightCardSurface = Color(0xFFFFFFFF)
private val LightIncomingBubble = Color(0xFFCBD5E1)
private val LightOutgoingBubble = Color(0xFF0095C2)
private val LightMutedText = Color(0xFF52667A)
private val LightPrimaryText = Color(0xFF0B1622)
private val LightCyanPrimary = Color(0xFF008EB8)
private val LightCyanAccent = Color(0xFF00779B)

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

private val LayoverLightColorScheme = lightColorScheme(
    primary = LightCyanPrimary,
    onPrimary = Color.White,
    primaryContainer = LightIncomingBubble,
    onPrimaryContainer = LightPrimaryText,
    secondary = LightCyanAccent,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightPrimaryText,
    surface = LightCardSurface,
    onSurface = LightPrimaryText,
    surfaceVariant = LightIncomingBubble,
    onSurfaceVariant = LightMutedText,
    outline = LightMutedText,
)

@Composable
fun LayoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val customColors = if (darkTheme) {
        LayoverCustomColors(
            isDark = true,
            darkBackground = DarkNavyBackground,
            cardSurface = ElevatedCardSurface,
            cyanPrimary = CyanPrimary,
            cyanAccent = CyanAccent,
            incomingBubble = IncomingBubbleSurface,
            outgoingBubble = OutgoingCyanBubble,
            mutedText = MutedText,
            lightText = LightText,
            gradientStart = DarkNavyBackground,
            gradientMid = Color(0xFF0E1A29),
            gradientEnd = DarkNavyBackground,
            overlayAlpha = 0.12f,
        )
    } else {
        LayoverCustomColors(
            isDark = false,
            darkBackground = LightBackground,
            cardSurface = LightCardSurface,
            cyanPrimary = LightCyanPrimary,
            cyanAccent = LightCyanAccent,
            incomingBubble = LightIncomingBubble,
            outgoingBubble = LightOutgoingBubble,
            mutedText = LightMutedText,
            lightText = LightPrimaryText,
            gradientStart = LightGradientStart,
            gradientMid = LightGradientMid,
            gradientEnd = LightGradientEnd,
            overlayAlpha = 0.05f,
        )
    }

    val colorScheme = if (darkTheme) LayoverDarkColorScheme else LayoverLightColorScheme

    CompositionLocalProvider(
        LocalLayoverCustomColors provides customColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

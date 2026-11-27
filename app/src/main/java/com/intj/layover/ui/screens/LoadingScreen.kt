package com.intj.layover.ui.screens

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val LayoverTurquoiseAccent = Color(0xFF00A6D6)
internal const val DEFAULT_LOADING_DISPLAY_MS = 5_000L

@SuppressLint("DiscouragedApi")
@Composable
fun rememberSylfaenFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        val regularId = context.resources.getIdentifier("sylfaen", "font", context.packageName)
        val italicId = context.resources.getIdentifier("sylfaen_italic", "font", context.packageName)
        val fonts = buildList {
            if (regularId != 0) {
                add(Font(resId = regularId, weight = FontWeight.Normal, style = FontStyle.Normal))
            }
            if (italicId != 0) {
                add(Font(resId = italicId, weight = FontWeight.Normal, style = FontStyle.Italic))
                add(Font(resId = italicId, weight = FontWeight.Bold, style = FontStyle.Italic))
            }
        }
        if (fonts.isNotEmpty()) FontFamily(fonts) else FontFamily.Serif
    }
}

@Composable
fun LayoverLoadingScreen(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    autoFinishDelayMs: Long = DEFAULT_LOADING_DISPLAY_MS,
    isReady: Boolean = true,
    onFinished: (() -> Unit)? = null
) {
    val backgroundColor = if (darkTheme) Color.Black else Color.White
    val titleColor = if (darkTheme) Color.White else Color.Black
    val subtitleColor = LayoverTurquoiseAccent
    val brandFontFamily = rememberSylfaenFontFamily()

    val splashStartElapsedMs = rememberSaveable { SystemClock.elapsedRealtime() }
    var hasFinished by remember { mutableStateOf(false) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentIsReady by rememberUpdatedState(isReady)

    LaunchedEffect(Unit) {
        if (currentOnFinished != null) {
            val elapsedMs = (SystemClock.elapsedRealtime() - splashStartElapsedMs).coerceAtLeast(0L)
            val remainingMs = (autoFinishDelayMs - elapsedMs).coerceAtLeast(0L)
            if (remainingMs > 0L) {
                delay(remainingMs)
            }
            snapshotFlow { currentIsReady }.first { it }
            if (!hasFinished) {
                hasFinished = true
                currentOnFinished?.invoke()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Layover",
                color = titleColor,
                fontFamily = brandFontFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = 58.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Look Around...",
                color = subtitleColor,
                fontFamily = brandFontFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 0.4.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LoadingScreen(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    autoFinishDelayMs: Long = DEFAULT_LOADING_DISPLAY_MS,
    isReady: Boolean = true,
    onFinished: (() -> Unit)? = null
) {
    LayoverLoadingScreen(
        modifier = modifier,
        darkTheme = darkTheme,
        autoFinishDelayMs = autoFinishDelayMs,
        isReady = isReady,
        onFinished = onFinished
    )
}

@Preview(name = "Loading Screen - Light", showSystemUi = true)
@Composable
private fun LoadingScreenLightPreview() {
    LayoverLoadingScreen(darkTheme = false)
}

@Preview(name = "Loading Screen - Dark", showSystemUi = true)
@Composable
private fun LoadingScreenDarkPreview() {
    LayoverLoadingScreen(darkTheme = true)
}

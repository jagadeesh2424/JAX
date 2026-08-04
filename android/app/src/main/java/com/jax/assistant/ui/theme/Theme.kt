package com.jax.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PureDark = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val CyanAccent = Color(0xFF00F0FF)
val GoldAccent = Color(0xFFFFD700)
val TextLight = Color(0xFFE6EDF3)
val TextMuted = Color(0xFF8B949E)

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    secondary = GoldAccent,
    background = PureDark,
    surface = SurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = TextLight,
    onSurface = TextLight
)

@Composable
fun JAXAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

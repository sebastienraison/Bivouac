package com.bivouac.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Deep, muted forest green — matches the distance icon color used in the track sheet, instead of
// the default Material baseline purple.
private val PrimaryLight = Color(0xFF3C7A5D)
private val OnPrimaryLight = Color(0xFFFFFFFF)
private val PrimaryDark = Color(0xFF8DC6A8)
private val OnPrimaryDark = Color(0xFF0B3B27)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
)
private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
)

@Composable
fun BivouacTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}

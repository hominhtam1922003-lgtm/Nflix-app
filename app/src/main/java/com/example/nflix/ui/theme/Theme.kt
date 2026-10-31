package com.example.nflix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NflixColors = darkColorScheme(
    primary = NflixViolet,
    onPrimary = Color(0xFFFFFFFF),
    background = NflixBackground,
    onBackground = NflixText,
    surface = NflixSurface,
    onSurface = NflixText,
    surfaceVariant = NflixSurfaceVariant,
    onSurfaceVariant = NflixTextSecondary,
    outline = NflixBorder,
    error = NflixRed
)

@Composable
fun NflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NflixColors,
        content = content
    )
}

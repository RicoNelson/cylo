package com.riconelson.cyclocomp.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF385BA9),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF536D8B),
    tertiary = androidx.compose.ui.graphics.Color(0xFF75658A),
)

private val DarkScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB5C4FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF002B6B),
    secondary = androidx.compose.ui.graphics.Color(0xFFBBC7E0),
    tertiary = androidx.compose.ui.graphics.Color(0xFFDCC0F7),
)

@Composable
fun CyclocompTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}

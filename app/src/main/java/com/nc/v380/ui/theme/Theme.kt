package com.nc.v380.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF72DED3),
    secondary = Color(0xFFB3CDC9),
    secondaryContainer = Color(0xFF304D49),
    onSecondaryContainer = Color(0xFFCFEDE8),
    background = Color(0xFF101B20),
    surface = Color(0xFF17272D),
    surfaceVariant = Color(0xFF22353B),
    onBackground = Color(0xFFE3EDEF),
    onSurface = Color(0xFFE3EDEF),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B64),
    secondary = Color(0xFF496864),
    secondaryContainer = Color(0xFFE0EEEC),
    onSecondaryContainer = Color(0xFF16403B),
    background = Color(0xFFF4F8F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EFEE),
    onBackground = Color(0xFF152E33),
    onSurface = Color(0xFF152E33),
)

@Composable
fun NcV380Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

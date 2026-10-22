package com.scanner.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFFFF8F00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF3F8F7),
    surface = Color(0xFFF3F8F7),
    surfaceVariant = Color(0xFFDDE8E6),
    surfaceContainerHighest = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3E2400),
    secondaryContainer = Color(0xFF5C3A00),
    onSecondaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF0F1514),
    surface = Color(0xFF0F1514),
    surfaceVariant = Color(0xFF2A3331),
    surfaceContainerHighest = Color(0xFF1A2321),
)

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    // tema scuro sempre attivo
    MaterialTheme(colorScheme = Dark, content = content)
}

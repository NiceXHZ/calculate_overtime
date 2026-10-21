package com.liu.overtime.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Match the semantic palette in web/styles.css; keep opaque text and fallback surfaces readable.
internal fun overtimeColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFA0DFBB), onPrimary = Color(0xFF133022),
    primaryContainer = Color(0xFF294737), onPrimaryContainer = Color(0xFFEDF6EF),
    secondary = Color(0xFFCEE5D7), onSecondary = Color(0xFF152C20),
    secondaryContainer = Color(0xFF294235), onSecondaryContainer = Color(0xFFF0FAF3),
    background = Color(0xFF111B18), onBackground = Color(0xFFEDF6EF),
    surface = Color(0xFF1C2B24), onSurface = Color(0xFFEDF6EF),
    surfaceVariant = Color(0xFF24372D), onSurfaceVariant = Color(0xFFBDCDC2),
    surfaceContainerLowest = Color(0xFF111B18), surfaceContainerLow = Color(0xFF19271F),
    surfaceContainer = Color(0xFF24372D), surfaceContainerHigh = Color(0xFF2B4034),
    surfaceContainerHighest = Color(0xFF344B3D),
    outline = Color(0xFF9AAF9F), outlineVariant = Color(0xFF47604F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
) else lightColorScheme(
    primary = Color(0xFF236C52), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EEE6), onPrimaryContainer = Color(0xFF18553F),
    secondary = Color(0xFF52685F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF1EC), onSecondaryContainer = Color(0xFF243A2C),
    background = Color(0xFFF3F5F4), onBackground = Color(0xFF192E28),
    surface = Color.White, onSurface = Color(0xFF192E28),
    surfaceVariant = Color(0xFFEDF3EF), onSurfaceVariant = Color(0xFF52685F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9F7),
    surfaceContainer = Color(0xFFEDF3EF), surfaceContainerHigh = Color(0xFFE6EEE8),
    surfaceContainerHighest = Color(0xFFDDE8E0),
    outline = Color(0xFF6C8175), outlineVariant = Color(0xFFD5E0D8),
    error = Color(0xFFA83738), onError = Color.White
)

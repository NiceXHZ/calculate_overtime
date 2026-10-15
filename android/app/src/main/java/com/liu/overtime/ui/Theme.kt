package com.liu.overtime.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal fun overtimeColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFA3F0C8), onPrimary = Color(0xFF09291C),
    primaryContainer = Color(0xFF315A45), onPrimaryContainer = Color(0xFFE4FFEE),
    secondary = Color(0xFFCEE5D7), onSecondary = Color(0xFF152C20),
    secondaryContainer = Color(0xFF354B3E), onSecondaryContainer = Color(0xFFF0FAF3),
    background = Color(0xFF0E1412), onBackground = Color(0xFFF4FAF6),
    surface = Color(0xFF203029), onSurface = Color(0xFFF4FAF6),
    surfaceVariant = Color(0xFF30443A), onSurfaceVariant = Color(0xFFCFDDD5),
    surfaceContainerLowest = Color(0xFF111A15), surfaceContainerLow = Color(0xFF1B2922),
    surfaceContainer = Color(0xFF24372D), surfaceContainerHigh = Color(0xFF2B4035),
    surfaceContainerHighest = Color(0xFF344B3E),
    outline = Color(0xFF9DB5A7), outlineVariant = Color(0xFF526D5D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
) else lightColorScheme(
    primary = Color(0xFF1B7054), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F0E1), onPrimaryContainer = Color(0xFF123B2B),
    secondary = Color(0xFF546B5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EBE4), onSecondaryContainer = Color(0xFF243A2C),
    background = Color(0xFFF4F7F3), onBackground = Color(0xFF1C3029),
    surface = Color(0xFFFDFEFC), onSurface = Color(0xFF1C3029),
    onSurfaceVariant = Color(0xFF455A50), outline = Color(0xFF6E8779), outlineVariant = Color(0xFFC1D2C7)
)

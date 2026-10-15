package com.liu.overtime

import androidx.compose.ui.graphics.luminance
import com.liu.overtime.ui.overtimeColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun darkTextAndControlsHaveStrongContrast() {
        val colors = overtimeColorScheme(true)
        fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
            val x = a.luminance(); val y = b.luminance()
            return (maxOf(x,y) + .05f) / (minOf(x,y) + .05f)
        }
        assertTrue(ratio(colors.onSurface, colors.surface) >= 7f)
        assertTrue(ratio(colors.onSurfaceVariant, colors.surface) >= 7f)
        assertTrue(ratio(colors.primary, colors.surface) >= 7f)
        assertTrue(ratio(colors.onPrimary, colors.primary) >= 7f)
        assertTrue(ratio(colors.onSecondaryContainer, colors.secondaryContainer) >= 7f)
    }
}

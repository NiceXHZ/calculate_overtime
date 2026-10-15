package com.liu.overtime

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.overtime.domain.WorkZone
import com.liu.overtime.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp-320dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowDateSummaryAndWheelsStaySingleLineAtLargeFonts() = inspectDateLayouts("narrow")

    @Test @Config(qualifiers="w640dp-h360dp-240dpi")
    fun landscapeDateSummaryAndWheelsStaySingleLineAtLargeFonts() = inspectDateLayouts("landscape")

    private fun inspectDateLayouts(name: String) {
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        var view: android.view.View? = null
        compose.setContent {
            val baseDensity = LocalDensity.current.density
            view = androidx.compose.ui.platform.LocalView.current.rootView
            CompositionLocalProvider(LocalDensity provides Density(baseDensity,scale)) {
                MaterialTheme(colorScheme=overtimeColorScheme(dark)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AddPunchSheet(rememberLayerBackdrop(),false,Preferences(pickerSound=false,pickerHaptics=false),
                            ZonedDateTime.of(2026,12,31,19,59,0,0,WorkZone),onDismiss={},onSave={_,_->})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("日期").performClick()
        for (font in listOf(1f,1.6f)) for (night in listOf(false,true)) {
            compose.runOnIdle { scale=font;dark=night }
            compose.waitForIdle()
            for (node in listOf(compose.onNodeWithTag("punch-date-value",useUnmergedTree=true),compose.onNodeWithTag("punch-time-value",useUnmergedTree=true),
                compose.onNodeWithText("2026年"),compose.onNodeWithText("12月"),compose.onNodeWithText("31日"))) {
                val results = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
                assertEquals(1,results.single().lineCount)
                assertFalse("$name font=$font ${results.single().layoutInput.text} overflow",results.single().hasVisualOverflow)
            }
            compose.onNodeWithText("保存打卡").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle {
                val root = requireNotNull(view)
                val bitmap = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val file = File("build/screenshots/date-$name-fs${(font*100).toInt()}-${if (night) "dark" else "light"}.png").apply { parentFile?.mkdirs() }
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            }
        }
    }
}

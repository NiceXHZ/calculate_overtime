package com.liu.overtime

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.liu.overtime.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-320dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebVisualLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowPagesKeepNavigationMonthsAndWidgetSizesReadable() = inspectPages("narrow")

    @Test @Config(qualifiers = "w640dp-h360dp-240dpi")
    fun landscapePagesRemainScrollableAndHeatmapOpensExistingDayPanel() = inspectPages("landscape")

    @Test fun longMonthlyTotalFitsCompactMetricAtLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                MaterialTheme(colorScheme = overtimeColorScheme(false)) {
                    Box(Modifier.width(92.dp)) { NumericValue("744.0", unit = "小时") }
                }
            }
        }
        assertSingleLine(compose.onNodeWithText("744.0"))
        compose.onNodeWithText("小时").assertIsDisplayed()
    }

    private fun inspectPages(size: String) {
        val vm = MainViewModel(compose.activity.application)
        compose.activity.viewModelStore.put("web-visual-review", vm)
        var font by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, font)) { OvertimeApp(vm) }
        }
        compose.waitUntil(15_000) { vm.state.value.loaded }
        vm.store.import("""{"2026-10-01":{"entries":["2026-10-01T09:00:00+08:00","2026-10-01T18:00:00+08:00"]},"2026-10-09":{"entries":["2026-10-09T09:00:00+08:00","2026-10-09T19:45:00+08:00"]},"2026-10-20":{"entries":["2026-10-20T09:00:00+08:00","2026-10-20T17:45:00+08:00"]}}""")
        compose.runOnIdle { vm.month.value = YearMonth.of(2026, 10) }
        for (scale in listOf(1f, 1.6f)) for (dark in listOf(false, true)) {
            compose.runOnIdle {
                font = scale
                vm.updatePreferences(Preferences(glass = false, dark = if (dark) "dark" else "light"))
            }
            for ((tab, title) in listOf("今天" to "今天", "记录" to "打卡记录", "统计" to "时间统计", "设置" to "偏好设置")) {
                compose.onNodeWithContentDescription(tab).performClick()
                compose.onNodeWithTag("main-page").performScrollToIndex(0)
                compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
                for (nav in listOf("今天", "记录", "统计", "设置")) compose.onNodeWithContentDescription(nav).assertIsDisplayed()
                if (tab == "记录" || tab == "统计") {
                    compose.onNodeWithText("2026年 10月").performScrollTo().assertIsDisplayed()
                    assertSingleLine(compose.onNodeWithText("2026年 10月"))
                    if (tab == "统计") {
                        val monthBounds = compose.onNodeWithText("2026年 10月").fetchSemanticsNode().boundsInRoot
                        val totalBounds = compose.onNodeWithText("本月累计加班").fetchSemanticsNode().boundsInRoot
                        assertTrue("Month must remain above the summary: $monthBounds / $totalBounds", monthBounds.bottom <= totalBounds.top)
                    }
                }
                if (tab == "记录") {
                    assertSingleLine(compose.onNodeWithText("补录打卡"))
                    assertSingleLine(compose.onNodeWithText("导入 JSON"))
                }
                capture("web-$size-${(scale * 100).toInt()}-${if (dark) "dark" else "light"}-$tab")
            }
            compose.onNodeWithTag("main-page").performScrollToNode(hasText("2×2"))
            for (label in listOf("2×1", "2×2", "3×2", "4×1", "4×2", "4×3")) {
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                assertSingleLine(compose.onNodeWithText(label))
            }
        }
        compose.onNodeWithContentDescription("统计").performClick()
        compose.onNodeWithTag("main-page").performScrollToNode(hasContentDescription("2026-10-09", substring = true))
        // A floating navigation bar overlays the scrolling viewport; bring the date above it before tapping.
        val dateBottom = compose.onNodeWithContentDescription("2026-10-09", substring = true).fetchSemanticsNode().boundsInRoot.bottom
        val navTop = compose.onNodeWithContentDescription("今天").fetchSemanticsNode().boundsInRoot.top
        if (dateBottom > navTop) compose.onNodeWithTag("main-page")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, dateBottom - navTop + 30f) }
        capture("web-$size-heatmap")
        compose.onNodeWithContentDescription("2026-10-09", substring = true).performClick()
        compose.onNodeWithTag("glass-sheet").assertIsDisplayed()
        compose.onNodeWithText("19:45:00").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭10月9日 星期五").performClick()
        compose.onNodeWithTag("glass-sheet").assertDoesNotExist()
    }

    private fun assertSingleLine(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse("${layouts.single().layoutInput.text} overflow: ${layouts.single().size} width=${layouts.single().didOverflowWidth} height=${layouts.single().didOverflowHeight}", layouts.single().hasVisualOverflow)
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}

package com.liu.overtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.overtime.ui.GlassConfirmation
import com.liu.overtime.ui.GlassSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entryAndCloseHaveVisibleIntermediatePositionsAndCloseOnlyOnce() {
        var shown by mutableStateOf(false)
        var closes = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (shown) GlassSheet("动画测试", rememberLayerBackdrop(), false,
                        onDismiss = { closes++; shown = false }) { Text("面板内容", Modifier.height(160.dp)) }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = true }
        advance(32)
        advance(160)
        val entering = sheetTop()
        advance(500)
        val settled = sheetTop()
        assertTrue("Opening should still be moving after 160 ms: $entering / $settled", entering > settled + 20f)
        compose.onNodeWithContentDescription("关闭动画测试").assertIsNotFocused().performClick()
        advance(160)
        assertEquals(0, closes)
        assertTrue("Dismissal should slide down before removing content", sheetTop() > settled + 20f)
        // Repeated cancellation during the exit cannot run the completion twice.
        compose.onNodeWithContentDescription("关闭弹层遮罩").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        advance(600)
        compose.onNodeWithTag("glass-sheet").assertDoesNotExist()
        assertEquals(1, closes)
    }

    @Test fun cancellationWaitsForExitAndCommitIsImmediateWithoutDuplicateActions() {
        var shown by mutableStateOf(true)
        var confirmed = 0
        var canceled = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    if (shown) GlassConfirmation("确认测试", "测试操作", "执行", backdrop = rememberLayerBackdrop(),
                        glass = false, onDismiss = { canceled++; shown = false }, onConfirm = { confirmed++ })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("执行").performClick()
        advance(160)
        assertEquals(0, canceled)
        assertEquals(0, confirmed)
        advance(600)
        assertEquals(1, canceled)
        assertEquals(0, confirmed)
        compose.runOnIdle { shown = true }
        advance(32)
        advance(600)
        compose.onNodeWithText("执行").performClick()
        compose.onNodeWithText("执行").performClick()
        advance(160)
        assertEquals(1, confirmed)
        assertEquals(1, canceled)
        compose.onNodeWithTag("glass-sheet").assertExists()
        advance(600)
        assertEquals(1, confirmed)
        assertEquals(2, canceled)
        compose.onNodeWithTag("glass-sheet").assertDoesNotExist()
    }

    @Test fun shortDragReboundsAndLongDragDismissesWithoutScrollingTheBody() {
        var shown by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    if (shown) GlassSheet("拖动测试", rememberLayerBackdrop(), false,
                        onDismiss = { shown = false }) { Text("可滚动的内容", Modifier.height(220.dp)) }
                }
            }
        }
        val settled = sheetTop()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput {
            val start = Offset(centerX, 8f)
            down(start)
            moveTo(start + Offset(0f, 80f), delayMillis = 250)
            moveTo(start + Offset(0f, 80f), delayMillis = 250)
            up()
        }
        advance(600)
        assertTrue(shown)
        assertEquals(settled, sheetTop(), 2f)
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput {
            val start = Offset(centerX, 8f)
            down(start)
            moveTo(start + Offset(0f, 280f), delayMillis = 300)
            up()
        }
        advance(160)
        assertTrue(shown)
        advance(600)
        assertFalse(shown)
    }

    private fun sheetTop(): Float = compose.onNodeWithTag("glass-sheet").getUnclippedBoundsInRoot().top.value

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}

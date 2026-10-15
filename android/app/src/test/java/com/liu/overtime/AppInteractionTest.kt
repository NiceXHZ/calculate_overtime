package com.liu.overtime

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.liu.overtime.data.RecordStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun punchUndoAndNavigateAllFourScreens() {
        val store = RecordStore.get(compose.activity)
        compose.waitUntil(15_000) { store.state.value.loaded }
        compose.onNodeWithText("记录打卡").assertIsEnabled()
        val before = store.state.value.records.sumOf { it.punches.size }
        compose.onNodeWithText("记录打卡").performClick()
        compose.waitUntil(10_000) { store.state.value.records.sumOf { it.punches.size } == before + 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(10_000) { store.state.value.records.sumOf { it.punches.size } == before }
        compose.onNodeWithText("记录").performClick()
        compose.onNodeWithText("打卡记录").assertIsDisplayed()
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("时间统计").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("偏好设置").assertIsDisplayed()
        compose.runOnIdle {
            MainActivity::class.java.getDeclaredMethod("onNewIntent",android.content.Intent::class.java).apply { isAccessible = true }
                .invoke(compose.activity,android.content.Intent(compose.activity.intent).putExtra(MainActivity.EXTRA_SHOW_TODAY,true))
        }
        compose.onNodeWithText("记录打卡").assertIsDisplayed()
    }

    @Test fun renderTodayForVisualReview() {
        compose.waitUntil(15_000) { RecordStore.get(compose.activity).state.value.loaded }
        // The library's AGSL shaders require a GPU. Review the documented fallback on the JVM.
        compose.runOnIdle {
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.liu.overtime.ui.MainViewModel::class.java]
                .updatePreferences(com.liu.overtime.ui.Preferences(glass = false))
        }
        compose.waitForIdle()
        // PixelCopy requires a hardware window. Draw the Android view directly for JVM review.
        capture("today")
        compose.onNodeWithText("记录").performClick()
        capture("records")
        compose.onNodeWithText("统计").performClick()
        capture("statistics")
        compose.onNodeWithText("设置").performClick()
        capture("settings")
        compose.runOnIdle {
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.liu.overtime.ui.MainViewModel::class.java]
                .updatePreferences(com.liu.overtime.ui.Preferences(glass = false, dark = "dark"))
        }
        compose.onNodeWithContentDescription("今天").performClick()
        capture("today-dark")
    }

    @Test fun completePunchSheetAndWheelCutoffSave() {
        val store = RecordStore.get(compose.activity)
        compose.waitUntil(15_000) { store.state.value.loaded }
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[com.liu.overtime.ui.MainViewModel::class.java]
        compose.runOnIdle { vm.updatePreferences(com.liu.overtime.ui.Preferences(glass = false)) }
        compose.onNodeWithText("补卡").performClick()
        compose.onNodeWithText("保存打卡").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("小时滚轮").assertIsDisplayed()
        capture("punch-time")
        compose.runOnIdle { vm.updatePreferences { it.copy(dark = "dark") } }
        capture("punch-time-dark")
        compose.runOnIdle { vm.updatePreferences { it.copy(dark = "light") } }
        compose.onNodeWithText("日期").performClick()
        compose.onNodeWithContentDescription("月份滚轮").assertIsDisplayed()
        compose.onNodeWithText("保存打卡").assertIsDisplayed()
        capture("punch-date")
        val before = store.state.value.records.sumOf { it.punches.size }
        compose.onNodeWithText("保存打卡").performClick()
        compose.waitUntil(10_000) { store.state.value.records.sumOf { it.punches.size } == before + 1 }
        compose.onNodeWithText("记录").performClick()
        val dayLabel = java.time.LocalDate.now(com.liu.overtime.domain.WorkZone)
            .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA))
        compose.onNodeWithText(dayLabel).performClick()
        capture("day-detail")
        compose.onNodeWithContentDescription("删除第 1 次打卡").performClick()
        compose.onNodeWithText("删除打卡").assertIsDisplayed()
        capture("delete-confirmation")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("删除第 1 次打卡").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before + 1, store.state.value.records.sumOf { it.punches.size }) }
        compose.onNodeWithContentDescription("关闭$dayLabel").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("工作日下班时间").performScrollTo().performClick()
        val actions = compose.onNodeWithContentDescription("小时滚轮").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "增加小时" }.action()) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("小时滚轮").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "18时"))
        compose.onNodeWithContentDescription("小时滚轮").performTouchInput {
            swipeUp(startY = centerY + 30f, endY = centerY - 70f, durationMillis = 500)
        }
        compose.waitForIdle()
        val chosenHour = compose.onNodeWithContentDescription("小时滚轮").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].removeSuffix("时").toInt()
        assertNotEquals(18, chosenHour)
        capture("cutoff")
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(java.time.LocalTime.of(chosenHour, 30), vm.preferences.value.cutoff) }
        compose.onNodeWithText("恢复最近副本").performScrollTo().performClick()
        compose.onNodeWithText("恢复副本").assertIsDisplayed()
        capture("restore-confirmation")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("刻度音效").performScrollTo()
        compose.onNodeWithContentDescription("刻度音效").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("轻触震动").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertFalse(vm.preferences.value.pickerSound)
            assertFalse(vm.preferences.value.pickerHaptics)
            // AndroidViewModelFactory is cached across Robolectric applications. Read the owning app's store.
            val saved = vm.getApplication<android.app.Application>().getSharedPreferences("preferences", 0)
            assertFalse(saved.all.toString(), saved.getBoolean("pickerSound", true))
            assertFalse(saved.all.toString(), saved.getBoolean("pickerHaptics", true))
        }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-240dpi")
    fun landscapeSheetKeepsPickerAndSaveVisible() {
        compose.waitUntil(15_000) { RecordStore.get(compose.activity).state.value.loaded }
        compose.runOnIdle {
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.liu.overtime.ui.MainViewModel::class.java]
                .updatePreferences(com.liu.overtime.ui.Preferences(glass = false, dark = "dark"))
        }
        compose.onNodeWithTag("main-page").performScrollToNode(hasText("补卡"))
        compose.onNodeWithText("补卡").performClick()
        compose.onNodeWithText("保存打卡").assertIsDisplayed()
        compose.onNodeWithContentDescription("小时滚轮").assertIsDisplayed()
        val save = compose.onNodeWithText("保存打卡").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(save.bottom <= root.bottom)
        assertTrue(save.top >= root.top)
        capture("punch-landscape-dark")
        compose.onNodeWithContentDescription("关闭补录打卡").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("保存打卡").assertDoesNotExist()
        compose.onNodeWithTag("main-page").performScrollToNode(hasText("记录打卡"))
        compose.onNodeWithText("记录打卡").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val file = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(bitmap.width > 300)
    }
}

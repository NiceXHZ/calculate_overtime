package com.liu.overtime

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.liu.overtime.ui.MainViewModel
import com.liu.overtime.ui.OvertimeApp
import com.liu.overtime.ui.Preferences
import com.liu.overtime.widget.PinOutcome
import com.liu.overtime.widget.WidgetPinRequests
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppWidgetManager
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** A launcher that advertises support but drops the request, as reported on some Xiaomi desktops. */
@Implements(AppWidgetManager::class)
class SilentPinLauncherShadow : ShadowAppWidgetManager() {
    @Implementation
    public override fun requestPinAppWidget(provider: ComponentName, extras: Bundle?, callback: PendingIntent?): Boolean = true
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi", shadows = [SilentPinLauncherShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPinFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before @After fun isolateLauncherSingleton() {
        // Robolectric can retain application statics between sandboxes. This test's silent launcher
        // must neither reuse an earlier AppWidgetManager nor leak into the next test application.
        WidgetPinRequests::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun ignoredRequestWaitsUntilForegroundThenShowsGuideOnceWithoutChangingRecords() {
        // Own this test's Application; AndroidViewModelFactory caches applications across Robolectric sandboxes.
        val vm = MainViewModel(compose.activity.application)
        compose.activity.viewModelStore.put("pin-flow", vm)
        compose.setContent { OvertimeApp(vm) }
        compose.waitUntil(15_000) { vm.state.value.loaded }
        val before = vm.state.value.records
        compose.runOnIdle {
            ShadowBuild.setManufacturer("Xiaomi")
            vm.widgetPin.dismiss()
            vm.updatePreferences(Preferences(glass = false, dark = "dark"))
            val app = vm.getApplication<android.app.Application>()
            shadowOf(AppWidgetManager.getInstance(app)).setRequestPinAppWidgetSupported(true)
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("main-page").performScrollToNode(hasText("2×2"))
        compose.onNodeWithText("2×2").performClick()
        compose.runOnIdle { assertEquals("After pin click: ${vm.widgetPin.state.value}", PinOutcome.PENDING, vm.widgetPin.state.value.outcome) }
        compose.onNodeWithText("尚未确认添加成功").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
        compose.mainClock.advanceTimeBy(7_000)
        assertEquals(PinOutcome.PENDING, vm.widgetPin.state.value.outcome)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(15_000) { vm.widgetPin.state.value.outcome == PinOutcome.UNCONFIRMED }
        compose.onNodeWithText("尚未确认添加成功").assertIsDisplayed()
        compose.onNodeWithTag("xiaomi-guide-thumbnail").assertIsDisplayed().performClick()
        compose.onNodeWithText("小米官方添加流程").assertIsDisplayed()
        compose.onNodeWithText("返回提示").performClick()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("尚未确认添加成功").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(PinOutcome.IDLE, vm.widgetPin.state.value.outcome)
            assertEquals(before, vm.state.value.records)
        }
    }
}

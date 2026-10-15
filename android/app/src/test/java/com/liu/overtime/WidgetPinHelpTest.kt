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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liu.overtime.ui.*
import com.liu.overtime.widget.PinOutcome
import com.liu.overtime.widget.PinUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-320dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPinHelpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun xiaomiHelpAndOfflineImageRemainUsableInLightDarkAndLargeFonts() = reviewHelp("narrow")

    @Test @Config(qualifiers = "w640dp-h360dp-240dpi")
    fun landscapeHelpKeepsDismissActionVisible() = reviewHelp("landscape")

    private fun reviewHelp(name: String) {
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        var dismissed = false
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current.rootView
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme(colorScheme = overtimeColorScheme(dark)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        WidgetPinHelpSheet(PinUiState(PinOutcome.UNCONFIRMED, size = "2×2"),
                            rememberLayerBackdrop(), false, xiaomi = true, onDismiss = { dismissed = true })
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.6f)) {
            compose.runOnIdle { dark = night; fontScale = font }
            compose.onNodeWithText("尚未确认添加成功").assertIsDisplayed()
            compose.onNodeWithText("知道了").assertIsDisplayed().assertIsEnabled()
            capture(view, "pin-help-$name-fs${(font * 100).toInt()}-${if (night) "dark" else "light"}")
        }
        compose.onNodeWithTag("xiaomi-guide-thumbnail").performScrollTo().performClick()
        compose.onNodeWithText("小米官方添加流程").assertIsDisplayed()
        compose.onNodeWithText("返回提示").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("小米官方图示：右上角搜索 → 应用列表底部 → 安卓原生小部件").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("放大").performScrollTo().performClick()
        compose.onNodeWithText("缩小").assertIsEnabled()
        capture(view, "pin-image-$name-dark")
        compose.onNodeWithText("返回提示").performClick()
        compose.onNodeWithText("尚未确认添加成功").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun otherBrandsGetGenericHelpWithoutXiaomiImage() {
        assertTrue(isXiaomiDevice("Xiaomi", "generic"))
        assertTrue(isXiaomiDevice("generic", "POCO"))
        assertTrue(isXiaomiDevice("generic", "Redmi"))
        assertFalse(isXiaomiDevice("samsung", "samsung"))
        compose.setContent {
            MaterialTheme {
                WidgetPinHelpSheet(PinUiState(PinOutcome.UNAVAILABLE, size = "4×2"),
                    rememberLayerBackdrop(), false, xiaomi = false, onDismiss = {})
            }
        }
        compose.onNodeWithText("请从桌面添加").assertIsDisplayed()
        compose.onNodeWithText("长按桌面空白处", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("xiaomi-guide-thumbnail").assertDoesNotExist()
    }

    private fun capture(view: android.view.View?, name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}

package com.liu.overtime.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.liu.overtime.widget.PinOutcome
import com.liu.overtime.widget.PinUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

internal const val XiaomiWidgetGuideUrl = "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1588"
internal fun isXiaomiDevice(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Boolean =
    listOf(manufacturer, brand).any { it.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

@Composable
internal fun WidgetPinHelpSheet(
    state: PinUiState,
    backdrop: Backdrop,
    glass: Boolean,
    xiaomi: Boolean = isXiaomiDevice(),
    onDismiss: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val openOfficial = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, XiaomiWidgetGuideUrl.toUri())) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "未找到浏览器", Toast.LENGTH_SHORT).show() }
    }
    if (expanded && xiaomi) {
        GlassSheet("小米官方添加流程", backdrop, glass, onDismiss = { expanded = false }, footer = {
            Button(onClick = { expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("返回提示") }
        }) {
            Text("双指缩放、拖动查看，或使用下方缩放按钮。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            ZoomableOfficialGuide()
        }
    } else {
        val title = if (state.outcome == PinOutcome.UNAVAILABLE) "请从桌面添加" else "尚未确认添加成功"
        GlassSheet(title, backdrop, glass, onDismiss, footer = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("知道了") }
        }) {
            Text(if (state.outcome == PinOutcome.UNAVAILABLE) "当前桌面未接受快捷添加请求，可以手动添加 ${state.size} 小组件。"
                else "还没有确认 ${state.size} 小组件已添加。若桌面仍有确认窗口，请先完成操作；也可以按下面的方法手动添加。",
                fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            Text(if (xiaomi) "桌面双指捏合 → 添加小部件 → 右上角搜索 → 应用列表底部的「安卓小部件」→ 加班打卡。"
                else "长按桌面空白处 → 小组件／小部件 → 加班打卡 → 选择所需尺寸。",
                fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (xiaomi) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().clickable(onClickLabel = "放大小米官方添加流程") { expanded = true }
                    .testTag("xiaomi-guide-thumbnail").background(Color.White)) {
                    OfficialGuideImage(false, Modifier.fillMaxWidth().aspectRatio(2880f / 1618f))
                }
                Text("点按图片可放大 · 图示来自小米官方，界面可能随系统版本变化。",
                    fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = openOfficial) { Text("小米官方说明") }
            }
        }
    }
}

@Composable
private fun OfficialGuideImage(fullResolution: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, context, fullResolution) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("guides/xiaomi-native-widgets.webp").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (fullResolution) 1 else 2 })?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image, "小米官方图示：右上角搜索 → 应用列表底部 → 安卓原生小部件", modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { Text("请按上方步骤添加", fontSize = 13.sp, color = Color.DarkGray) }
}

@Composable
private fun ZoomableOfficialGuide() {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(330.dp).clipToBounds().background(Color.White)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val imageWidth = minOf(width, height * 2880f / 1618f)
        val imageHeight = imageWidth * 1618f / 2880f
        fun bounded(value: Offset): Offset {
            val x = ((imageWidth * scale - width) / 2).coerceAtLeast(0f)
            val y = ((imageHeight * scale - height) / 2).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
        }
        OfficialGuideImage(true, Modifier.fillMaxSize().pointerInput(width, height) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset = bounded(offset + pan)
            }
        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { scale = (scale - .5f).coerceAtLeast(1f); offset = Offset.Zero }, enabled = scale > 1f) { Text("缩小") }
        TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("还原") }
        TextButton(onClick = { scale = (scale + .5f).coerceAtMost(6f); offset = Offset.Zero }, enabled = scale < 6f) { Text("放大") }
    }
}

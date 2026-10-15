package com.liu.overtime.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.launch

/** A single-window presentation keeps the sampled backdrop aligned with the sheet.
 *  There is deliberately no partial-expansion anchor: actions are visible on opening.
 */
@Composable
internal fun GlassSheet(
    title: String,
    backdrop: Backdrop,
    glass: Boolean,
    onDismiss: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .2f
    val shape = RoundedCornerShape(34.dp)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val entry = remember { Animatable(1f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                entry.animateTo(1f, spring(stiffness = 450f))
                currentDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { entry.animateTo(0f, spring(dampingRatio = .88f, stiffness = 380f)) }
    BackHandler(onBack = dismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val travel = with(density) { maxHeight.toPx() }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - entry.value }
            .background(Color.Black.copy(alpha = if (dark) .45f else .22f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss)
            .semantics { contentDescription = "关闭弹层遮罩" })
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(10.dp)) {
            val compact = maxHeight < 400.dp
            val material = if (glass && Build.VERSION.SDK_INT >= 31) {
                Modifier.drawBackdrop(backdrop, { shape }, effects = {
                    blur(24.dp.toPx())
                    lens(20.dp.toPx(), 28.dp.toPx())
                }, onDrawSurface = {
                    drawRect(colors.surface.copy(alpha = if (dark) .92f else .78f))
                })
            } else Modifier.background(colors.surface, shape)
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = maxHeight)
                .graphicsLayer { translationY = entry.value * travel + drag }
                .shadow(24.dp, shape, clip = false).then(material).clip(shape)
                .border(.8.dp, if (dark) colors.outlineVariant else Color.White.copy(alpha = .85f), shape)
                // Consume taps within the panel instead of passing through to the dimmed page.
                .pointerInput(Unit) { detectTapGestures {} }
                .semantics { paneTitle = title; isTraversalGroup = true; dialog(); dismiss { dismiss(); true } }) {
                Column(Modifier.fillMaxWidth().draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) },
                    onDragStopped = { velocity ->
                        if (drag > with(density) { 72.dp.toPx() } || velocity > 1200f) dismiss()
                        else {
                            val rebound = Animatable(drag)
                            rebound.animateTo(0f, spring()) { drag = value }
                        }
                    },
                )) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 4.dp)
                        .size(34.dp, 5.dp).background(colors.onSurfaceVariant.copy(alpha = .28f), CircleShape))
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = dismiss, modifier = Modifier.size(44.dp)
                            .background(colors.onSurface.copy(alpha = .06f), CircleShape)) {
                            Icon(Icons.Outlined.Close, "关闭$title", tint = colors.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), content = content)
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp,
                    top = if (compact) 8.dp else 12.dp, bottom = if (compact) 12.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = footer)
            }
        }
    }
}

@Composable
internal fun GlassConfirmation(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = false,
    backdrop: Backdrop,
    glass: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    GlassSheet(title, backdrop, glass, onDismiss, footer = {
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = RoundedCornerShape(22.dp),
            colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()) { Text(confirm, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消", fontSize = 16.sp) }
    }) {
        Text(message, fontSize = 16.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

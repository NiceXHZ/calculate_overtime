package com.liu.overtime.ui

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liu.overtime.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/** Keep valid days when changing a month/year, including leap years. */
internal fun changePickerDate(date: LocalDate, year: Int = date.year, month: Int = date.monthValue): LocalDate =
    LocalDate.of(year, month, date.dayOfMonth.coerceAtMost(YearMonth.of(year, month).lengthOfMonth()))

@Composable
internal fun rememberPickerFeedback(sound: Boolean, haptics: Boolean): () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val currentSound by rememberUpdatedState(sound)
    val currentHaptics by rememberUpdatedState(haptics)
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val pool = remember {
        SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    }
    var tick by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(pool) {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        tick = pool.load(context, R.raw.picker_tick, 1)
        onDispose { pool.release() }
    }
    var last by remember { mutableLongStateOf(0L) }
    return remember(pool, view, audio) { {
        val uptime = SystemClock.uptimeMillis()
        if (uptime - last >= 35) {
            last = uptime
            // No ignore-setting flags: device haptic preferences still apply.
            if (currentHaptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (currentSound && ready && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0) {
                pool.play(tick, .35f, .35f, 0, 0, 1f)
            }
        }
    } }
}

@Composable
internal fun DateWheels(
    date: LocalDate,
    today: LocalDate,
    feedback: () -> Unit,
    onBusy: (Boolean) -> Unit,
    compact: Boolean = false,
    onChange: (LocalDate) -> Unit,
) {
    var yearMoving by remember { mutableStateOf(false) }
    var monthMoving by remember { mutableStateOf(false) }
    var dayMoving by remember { mutableStateOf(false) }
    LaunchedEffect(yearMoving, monthMoving, dayMoving) { onBusy(yearMoving || monthMoving || dayMoving) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    WheelFrame(compact) {
        WheelColumn("年份", (1900..today.year).toList(), date.year, "年", Modifier.weight(1.35f), feedback,
            { yearMoving = it }, compact = compact) { onChange(changePickerDate(date, year = it)) }
        WheelColumn("月份", (1..12).toList(), date.monthValue, "月", Modifier.weight(1f), feedback,
            { monthMoving = it }, compact = compact) { onChange(changePickerDate(date, month = it)) }
        WheelColumn("日期", (1..date.lengthOfMonth()).toList(), date.dayOfMonth, "日", Modifier.weight(1f), feedback,
            { dayMoving = it }, compact = compact) { onChange(date.withDayOfMonth(it)) }
    }
}

@Composable
internal fun TimeWheels(time: LocalTime, feedback: () -> Unit, onBusy: (Boolean) -> Unit, compact: Boolean = false, onChange: (LocalTime) -> Unit) {
    var hourMoving by remember { mutableStateOf(false) }
    var minuteMoving by remember { mutableStateOf(false) }
    LaunchedEffect(hourMoving, minuteMoving) { onBusy(hourMoving || minuteMoving) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    WheelFrame(compact) {
        Spacer(Modifier.weight(.2f))
        WheelColumn("小时", (0..23).toList(), time.hour, "时", Modifier.weight(1f), feedback,
            { hourMoving = it }, pad = true, compact = compact) { onChange(time.withHour(it)) }
        Text(":", fontSize = 24.sp, fontWeight = FontWeight.Medium)
        WheelColumn("分钟", (0..59).toList(), time.minute, "分", Modifier.weight(1f), feedback,
            { minuteMoving = it }, pad = true, compact = compact) { onChange(time.withMinute(it)) }
        Spacer(Modifier.weight(.2f))
    }
}

@Composable
private fun wheelRowHeight() = with(LocalDensity.current) { maxOf(44.dp, 32.sp.toDp()) }

@Composable
private fun WheelFrame(compact: Boolean, content: @Composable RowScope.() -> Unit) {
    val rowHeight = wheelRowHeight()
    Box(Modifier.fillMaxWidth().height(rowHeight * if (compact) 3 else 5)) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), RoundedCornerShape(14.dp)))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun WheelColumn(
    label: String,
    values: List<Int>,
    value: Int,
    suffix: String,
    modifier: Modifier,
    feedback: () -> Unit,
    onBusy: (Boolean) -> Unit,
    pad: Boolean = false,
    compact: Boolean = false,
    onChange: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(value).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val rowHeight = wheelRowHeight()
    val itemPixels = with(LocalDensity.current) { rowHeight.toPx() }
    val currentValues by rememberUpdatedState(values)
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onChange)
    val currentFeedback by rememberUpdatedState(feedback)
    val currentBusy by rememberUpdatedState(onBusy)
    // With two rows of content padding, scrollToItem(index) places that row in the centre.
    val position by remember(state, itemPixels) { derivedStateOf { state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPixels } }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().collect { currentBusy(it) }
    }
    DisposableEffect(Unit) { onDispose { currentBusy(false) } }
    LaunchedEffect(state, itemPixels) {
        snapshotFlow { position.roundToInt().coerceIn(0, currentValues.lastIndex) to state.isScrollInProgress }
            .distinctUntilChanged().collect { (index, moving) ->
                if (moving) {
                    val picked = currentValues[index]
                    // Initialization, dependent date clamping, and recomposition are silent.
                    if (picked != currentValue) {
                        currentChange(picked)
                        currentFeedback()
                    }
                }
            }
    }
    LaunchedEffect(value, values, state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            val index = values.indexOf(value).coerceAtLeast(0)
            if (abs(position - index) > .01f) state.scrollToItem(index)
        }
    }
    val choose: (Int) -> Boolean = { index ->
        if (index in values.indices) {
            scope.launch { state.animateScrollToItem(index) }; true
        } else false
    }
    LazyColumn(modifier.height(rowHeight * if (compact) 3 else 5).semantics {
        contentDescription = "${label}滚轮"
        stateDescription = "$value$suffix"
        customActions = listOf(
            CustomAccessibilityAction("增加$label") { choose(values.indexOf(value) + 1) },
            CustomAccessibilityAction("减少$label") { choose(values.indexOf(value) - 1) },
        )
    }, state = state, contentPadding = PaddingValues(vertical = rowHeight * if (compact) 1 else 2),
        flingBehavior = rememberSnapFlingBehavior(state), horizontalAlignment = Alignment.CenterHorizontally) {
        items(values.size, key = { values[it] }) { index ->
            val distance = index - position
            Box(Modifier.fillMaxWidth().height(rowHeight).graphicsLayer {
                rotationX = distance.coerceIn(-2.5f, 2.5f) * -16f
                scaleX = 1f - abs(distance).coerceAtMost(2.5f) * .065f
                scaleY = scaleX
                alpha = (1f - abs(distance) * .29f).coerceIn(.16f, 1f)
                cameraDistance = 12 * density
            }.clickable { choose(index) }, contentAlignment = Alignment.Center) {
                BasicText((if (pad) values[index].toString().padStart(2, '0') else values[index].toString()) + suffix,
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 23.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = if (abs(distance) < .5f) FontWeight.SemiBold else FontWeight.Normal),
                    maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 23.sp, stepSize = .5.sp))
            }
        }
    }
}

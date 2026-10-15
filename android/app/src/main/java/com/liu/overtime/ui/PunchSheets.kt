package com.liu.overtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.liu.overtime.domain.WorkZone
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun AddPunchSheet(
    backdrop: Backdrop,
    glass: Boolean,
    preferences: Preferences,
    now: ZonedDateTime,
    onDismiss: () -> Unit,
    onSave: (LocalDate, LocalTime) -> Unit,
) {
    var dateText by rememberSaveable { mutableStateOf(now.toLocalDate().toString()) }
    var timeText by rememberSaveable { mutableStateOf(now.toLocalTime().withSecond(0).withNano(0).toString()) }
    var mode by rememberSaveable { mutableIntStateOf(1) }
    var moving by remember { mutableStateOf(false) }
    val date = LocalDate.parse(dateText)
    val time = LocalTime.parse(timeText)
    val future = date.atTime(time).atZone(WorkZone).toInstant().isAfter(now.toInstant())
    val feedback = rememberPickerFeedback(preferences.pickerSound, preferences.pickerHaptics)
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 500 && configuration.screenWidthDp > 500
    GlassSheet("补录打卡", backdrop, glass, onDismiss, footer = {
        Text(if (future) "请选择已经发生的时间" else "北京时间 · 精确到分钟",
            fontSize = 13.sp, color = if (future) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Button(onClick = { onSave(date, time) }, enabled = !future && !moving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(22.dp)) {
            Text("保存打卡", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }) {
        val fields: @Composable () -> Unit = {
            PunchFields(date, time, mode, compact) { mode = it }
        }
        val wheels: @Composable () -> Unit = {
            if (mode == 0) DateWheels(date, now.toLocalDate(), feedback, { moving = it }, compact) { dateText = it.toString() }
            else TimeWheels(time, feedback, { moving = it }, compact) { timeText = it.toString() }
        }
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { fields() }
                Box(Modifier.weight(1.7f)) { wheels() }
            }
        } else {
            Text("选择实际打卡时间", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            fields()
            Spacer(Modifier.height(8.dp))
            wheels()
        }
    }
}

@Composable
private fun PunchFields(date: LocalDate, time: LocalTime, mode: Int, vertical: Boolean, onSelect: (Int) -> Unit) {
    val modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)).padding(4.dp)
    val fields = listOf("日期" to date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")),
        "时间" to time.format(DateTimeFormatter.ofPattern("HH:mm")))
    val field: @Composable (Int, String, String, Modifier) -> Unit = { index, label, value, itemModifier ->
        Column(itemModifier.heightIn(min = if (vertical) 48.dp else 0.dp).clip(RoundedCornerShape(14.dp))
            .background(if (mode == index) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { contentDescription = label }
            .selectable(selected = mode == index, role = Role.Tab) { onSelect(index) }
            .padding(horizontal = 8.dp, vertical = if (vertical) 6.dp else 10.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!vertical) {
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            BasicText(value, modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "punch-date-value" else "punch-time-value"),
                style = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = if (mode == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 17.sp, stepSize = .5.sp))
        }
    }
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fields.forEachIndexed { index, (label, value) -> field(index, label, value, Modifier.fillMaxWidth()) }
    } else Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        fields.forEachIndexed { index, (label, value) -> field(index, label, value, Modifier.weight(if (index == 0) 1.6f else 1f)) }
    }
}

@Composable
internal fun CutoffSheet(
    initial: LocalTime,
    backdrop: Backdrop,
    glass: Boolean,
    preferences: Preferences,
    onDismiss: () -> Unit,
    onSave: (LocalTime) -> Unit,
) {
    var timeText by rememberSaveable { mutableStateOf(initial.toString()) }
    var moving by remember { mutableStateOf(false) }
    val time = LocalTime.parse(timeText)
    val feedback = rememberPickerFeedback(preferences.pickerSound, preferences.pickerHaptics)
    val compact = LocalConfiguration.current.screenHeightDp < 500
    GlassSheet("工作日下班时间", backdrop, glass, onDismiss, footer = {
        Button(onClick = { onSave(time) }, enabled = !moving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(22.dp)) { Text("完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }) {
        Text("工作日从这个时间开始计算加班", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TimeWheels(time, feedback, { moving = it }, compact) { timeText = it.toString() }
    }
}

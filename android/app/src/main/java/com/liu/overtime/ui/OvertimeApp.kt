@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.liu.overtime.ui

import android.app.Activity
import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.liu.overtime.domain.*
import com.liu.overtime.BuildConfig
import com.liu.overtime.widget.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Green = Color(0xFF227762)
private val Mint = Color(0xFFCDF1DE)
private val Violet = Color(0xFFE5DFF9)
private val dayFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun OvertimeApp(vm: MainViewModel = viewModel(), todayRequest: Int = 0) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    val todayCalendar by vm.todayCalendar.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val dark = prefs.dark == "dark" || (prefs.dark == "system" && isSystemInDarkTheme())
    val colors = overtimeColorScheme(dark)
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var adding by rememberSaveable { mutableStateOf(false) }
        var selected by remember { mutableStateOf<LocalDate?>(null) }
        var deleting by remember { mutableStateOf<Pair<LocalDate, Int>?>(null) }
        var recovering by remember { mutableStateOf(false) }
        var choosingCutoff by rememberSaveable { mutableStateOf(false) }
        val modalVisible = adding || selected != null || deleting != null || recovering || choosingCutoff
        LaunchedEffect(todayRequest) {
            if (todayRequest > 0) {
                tab = 0; adding = false; selected = null; deleting = null; recovering = false; choosingCutoff = false
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
        SideEffect {
            (context as? Activity)?.let { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        val haptic = LocalHapticFeedback.current
        val backdrop = rememberLayerBackdrop()
        val scrollPositions = List(4) { rememberLazyListState() }
        val summaries = remember(state.records, month, calendar, prefs.cutoff) { Overtime.month(state.records, month, calendar.days, prefs.cutoff) }
        val today = state.records.find { it.date == now.toLocalDate() }
        val total = summaries.sumOf { it.seconds }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { vm.export(it, false) } }
        val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { vm.export(it, true) } }
        LaunchedEffect(vm) {
            vm.notices.collect { notice ->
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                val result = snackbar.showSnackbar(notice.text, if (notice.undo != null) "撤销" else null, withDismissAction = true,
                    duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) notice.undo?.let(vm::undo)
            }
        }
        BackHandler(tab != 0 && !modalVisible) { tab = 0 }
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().semantics { if (modalVisible) hideFromAccessibility() }) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(if (dark) Green.copy(alpha = .08f) else Mint.copy(alpha = .75f), Color.Transparent), Offset(size.width, size.height * .12f), size.width * .9f), size.width * .9f, Offset(size.width, size.height * .12f))
                    drawCircle(Brush.radialGradient(listOf(if (dark) Color(0xFF655383).copy(alpha = .04f) else Violet.copy(alpha = .65f), Color.Transparent), Offset(0f, size.height * .55f), size.width), size.width, Offset(0f, size.height * .55f))
                }
                LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("main-page"), state = scrollPositions[tab], contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("加班打卡 · " + listOf("记录今日出勤", "查看历史打卡", "统计加班时长", "按你的习惯来")[tab], color = colors.onSurfaceVariant, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(listOf("今天", "打卡记录", "时间统计", "偏好设置")[tab], fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            }
                        }
                    }
                    if (!state.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取本地记录…") }
                    state.error?.let { error -> item { GlassCard { Text(error, color = colors.error); TextButton(onClick = { tab = 3 }) { Text("前往恢复备份") } } } }
                    when (tab) {
                        0 -> {
                            item {
                                GlassCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(); Spacer(Modifier.width(8.dp)); Text(now.format(dayFormat), style = MaterialTheme.typography.labelLarge) }
                                    Spacer(Modifier.height(22.dp))
                                    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp)
                                    Text("北京时间 · 记录当下的努力", color = colors.onSurfaceVariant, fontSize = 12.sp)
                                    Spacer(Modifier.height(26.dp))
                                    Button(onClick = vm::punch, enabled = state.loaded && state.error == null && !busy, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(22.dp)) {
                                        Icon(Icons.Outlined.TouchApp, null); Spacer(Modifier.width(10.dp)); Text(if (busy) "正在保存…" else "记录打卡", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(if (today?.punches.isNullOrEmpty()) "今天还没有打卡，轻点一下开始记录" else "今天已记录 ${today!!.punches.size} 次 · 最近 ${today.punches.last().atZone(WorkZone).format(timeFormat)}", modifier = Modifier.align(Alignment.CenterHorizontally), color = colors.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Metric("${now.monthValue}月加班", Overtime.hours(Overtime.month(state.records, YearMonth.from(now), todayCalendar.days, prefs.cutoff).sumOf { it.seconds }), "小时", Modifier.weight(1f))
                                Metric("今日打卡", (today?.punches?.size ?: 0).toString(), "次", Modifier.weight(1f))
                            } }
                            item { SectionTitle("今天的时间线", "补卡") { adding = true } }
                            item {
                                GlassCard {
                                    if (today?.punches.isNullOrEmpty()) EmptyState(Icons.Outlined.WbSunny, "从容开始新的一天", "打卡后，时间线会出现在这里。")
                                    else today!!.punches.forEachIndexed { index, instant ->
                                        PunchRow(index, instant, busy) { deleting = today.date to index }
                                    }
                                }
                            }
                            item { Hint("自动保存至本机 · 无需登录", "建议定期导出 JSON 备份，换机也能继续记录。", Icons.Outlined.VerifiedUser) }
                            if (todayCalendar.days.isEmpty()) item { Text(todayCalendar.message, fontSize = 12.sp, color = colors.error) }
                        }
                        1 -> {
                            item { MonthSelector(month, { vm.month.value = month.minusMonths(1) }, { vm.month.value = month.plusMonths(1) }, { vm.month.value = YearMonth.now(WorkZone) }) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = { adding = true }, modifier = Modifier.weight(1f), enabled = !busy && state.error == null) { Icon(Icons.Outlined.Add, null); Text("补录打卡") }
                                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f), enabled = !busy && state.error == null) { Text("导入 JSON") }
                            } }
                            if (summaries.isEmpty()) item { GlassCard { EmptyState(Icons.Outlined.EventNote, "本月还没有记录", "可以直接打卡、补录，或导入网页端 JSON。") } }
                            items(summaries, key = { it.record.date.toString() }) { day ->
                                GlassCard(onClick = { selected = day.record.date }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) { Text(day.record.date.format(dayFormat), fontWeight = FontWeight.SemiBold); Text("${day.label} · ${day.record.punches.size} 次打卡", fontSize = 12.sp, color = colors.onSurfaceVariant) }
                                        Text(Overtime.duration(day.seconds), color = colors.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text(day.record.punches.joinToString("   ·   ") { it.atZone(WorkZone).format(DateTimeFormatter.ofPattern("HH:mm")) }, fontSize = 13.sp, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                        2 -> {
                            item { MonthSelector(month, { vm.month.value = month.minusMonths(1) }, { vm.month.value = month.plusMonths(1) }, { vm.month.value = YearMonth.now(WorkZone) }) }
                            item { GlassCard {
                                Text("本月累计加班", color = colors.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.Bottom) { Text(Overtime.hours(total), fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp); Text(" 小时", modifier = Modifier.padding(bottom = 12.dp), color = colors.onSurfaceVariant) }
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f)); Spacer(Modifier.height(18.dp))
                                Row { SmallStat("记录天数", "${summaries.size} 天", Modifier.weight(1f)); SmallStat("加班天数", "${summaries.count { it.seconds > 0 }} 天", Modifier.weight(1f)); SmallStat("日均加班", "${Overtime.hours(if (summaries.isEmpty()) 0 else total / summaries.size)} h", Modifier.weight(1f)) }
                            } }
                            item { GlassCard {
                                Text("本月节奏", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(18.dp))
                                MonthHeatmap(month, summaries)
                                Spacer(Modifier.height(12.dp)); Text("颜色越深，加班越久 · 灰色为无记录", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            } }
                            item { Hint(calendar.message, "工作日从 ${prefs.cutoff} 起计算；休息日取首末打卡间隔，不扣休息时间。", Icons.Outlined.Info) }
                            item { OutlinedButton(onClick = { csv.launch("overtime-$month.csv") }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = !busy && summaries.isNotEmpty()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("导出本月报表 CSV") } }
                        }
                        3 -> {
                            item { GlassCard {
                                Text("数据与备份", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
                                SettingsAction(Icons.Outlined.FileUpload, "导入 JSON", "合并所有月份，重复导入不会增加记录", !busy && state.error == null) { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                                SettingsAction(Icons.Outlined.FileDownload, "导出完整备份", "网页端兼容 · 所有月份与额外字段", !busy && state.loaded && state.error == null) { export.launch("overtime_log-${now.toLocalDate()}.json") }
                                SettingsAction(Icons.Outlined.Restore, "恢复最近副本", "保留最近 10 次修改前的本地副本", !busy) { recovering = true }
                            } }
                            item { GlassCard {
                                Text("计算与显示", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
                                SettingsAction(Icons.Outlined.Schedule, "工作日下班时间", "当前 ${prefs.cutoff} · 默认与网页端一致") {
                                    choosingCutoff = true
                                }
                                SettingsAction(Icons.Outlined.CalendarMonth, "更新节假日", if (refreshing) "正在更新…" else calendar.message, !refreshing) { vm.refreshHolidays() }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("液态玻璃", fontSize = 15.sp); Text("关闭可减少图形开销", fontSize = 12.sp, color = colors.onSurfaceVariant) }
                                    Switch(checked = prefs.glass, onCheckedChange = { enabled -> vm.updatePreferences { it.copy(glass = enabled) } })
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("外观", fontSize = 13.sp, color = colors.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key,label) ->
                                        FilterChip(selected = prefs.dark == key, onClick = { vm.updatePreferences { it.copy(dark = key) } }, label = { Text(label, fontSize = 12.sp) })
                                    }
                                }
                            } }
                            item { GlassCard {
                                Text("滚轮反馈", fontWeight = FontWeight.SemiBold)
                                PreferenceSwitch("刻度音效", "跟随系统静音与触摸音效设置", prefs.pickerSound) { enabled ->
                                    vm.updatePreferences { it.copy(pickerSound = enabled) }
                                }
                                PreferenceSwitch("轻触震动", "每经过一个刻度轻震动，遵循系统触感设置", prefs.pickerHaptics) { enabled ->
                                    vm.updatePreferences { it.copy(pickerHaptics = enabled) }
                                }
                            } }
                            item { GlassCard {
                                Text("桌面上的小帮手", fontWeight = FontWeight.SemiBold)
                                Text("每种尺寸都以大按钮打卡和今日记录为主。\n小尺寸看最近记录，大尺寸看今日时间线；点按记录可查看今日全部打卡。\n长按桌面 → 小组件 → 加班打卡。", fontSize = 13.sp, lineHeight = 21.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                run {
                                    val manager = AppWidgetManager.getInstance(context)
                                    if (manager.isRequestPinAppWidgetSupported) {
                                        val widgets = listOf("2×1" to QuickAttendanceWidget::class.java, "2×2" to SmallAttendanceWidget::class.java,
                                            "3×2" to TodayAttendanceWidget::class.java, "4×1" to StripAttendanceWidget::class.java,
                                            "4×2" to MediumAttendanceWidget::class.java, "4×3" to LargeAttendanceWidget::class.java)
                                        widgets.chunked(3).forEach { row ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                row.forEach { (label, type) ->
                                                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { manager.requestPinAppWidget(ComponentName(context, type), null, null) }) { Text(label) }
                                                }
                                            }
                                        }
                                    }
                                }
                                Text("也可在系统快捷设置中添加「打卡」磁贴。", fontSize = 12.sp, color = colors.onSurfaceVariant)
                            } }
                            item { Hint("加班打卡 ${BuildConfig.VERSION_NAME}", "所有记录保存在本机，北京时间。联网仅用于获取公开节假日。卸载会清除本地数据，请先导出备份。", Icons.Outlined.FavoriteBorder) }
                            item { GlassCard {
                                SettingsAction(Icons.Outlined.Code, "项目源码与说明", "加班打卡 · 网页端与 Android 原生应用") {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/NiceXHZ/calculate_overtime")))
                                }
                            } }
                        }
                    }
                }
            }
            val navShape = RoundedCornerShape(32.dp)
            val navModifier = if (prefs.glass && !lowRam && Build.VERSION.SDK_INT >= 31) Modifier.drawBackdrop(backdrop, { navShape }, effects = { blur(18.dp.toPx()); lens(18.dp.toPx(), 24.dp.toPx()) }, onDrawSurface = { drawRect(colors.surface.copy(alpha = if (dark) .92f else .56f)) })
                else Modifier.background(colors.surface.copy(alpha = .98f), navShape)
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().then(navModifier).border(1.dp, colors.surface.copy(alpha = .7f), navShape).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val tabs = listOf(Icons.Outlined.Home to "今天", Icons.Outlined.ReceiptLong to "记录", Icons.Outlined.BarChart to "统计", Icons.Outlined.Tune to "设置")
                tabs.forEachIndexed { index, (icon,label) ->
                    val tint by animateColorAsState(if (tab == index) colors.primary else colors.onSurfaceVariant, label = "tab")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(if (tab == index) colors.primary.copy(alpha = .10f) else Color.Transparent).selectable(selected = tab == index, role = Role.Tab) { tab = index }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(23.dp)); Spacer(Modifier.height(3.dp)); Text(label, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Medium)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp))
            }
            val glass = prefs.glass && !lowRam
            when {
                deleting != null -> {
                    val (date, index) = deleting!!
                    GlassConfirmation("删除这次打卡？", "将移除 $date 的第 ${index + 1} 次打卡。修改前副本会保留在本机。",
                        "删除打卡", destructive = true, backdrop = backdrop, glass = glass,
                        onDismiss = { deleting = null }, onConfirm = { vm.remove(date, index); deleting = null })
                }
                recovering -> GlassConfirmation("恢复最近副本？", "当前记录将回退到最近一次修改前。恢复前建议先导出当前完整备份。",
                    "恢复副本", backdrop = backdrop, glass = glass,
                    onDismiss = { recovering = false }, onConfirm = { vm.recover(); recovering = false })
                adding -> AddPunchSheet(backdrop, glass, prefs, now, onDismiss = { adding = false },
                    onSave = { d, t -> vm.add(d, t); adding = false })
                choosingCutoff -> CutoffSheet(prefs.cutoff, backdrop, glass, prefs,
                    onDismiss = { choosingCutoff = false },
                    onSave = { time -> vm.updatePreferences { it.copy(cutoff = time) }; choosingCutoff = false })
                selected != null -> {
                    val date = selected!!
                    GlassSheet(date.format(dayFormat), backdrop, glass, onDismiss = { selected = null }) {
                        val record = state.records.find { it.date == date }
                        if (record?.punches.isNullOrEmpty()) Text("当天已无打卡")
                        record?.punches?.forEachIndexed { index, instant ->
                            PunchRow(index, instant, busy) { deleting = date to index }
                        }
                    }
                }
            }
        }
        }

    }
}

@Composable private fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .2f
    Column(modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(colors.surface.copy(alpha = if (dark) .98f else .88f), colors.surface.copy(alpha = if (dark) .96f else .58f)))).border(1.dp, if (dark) colors.outlineVariant else colors.surface.copy(alpha = .8f), shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(22.dp), content = content)
}
@Composable private fun StatusDot() { Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
@Composable private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    GlassCard(modifier) { Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.Bottom) { Text(value, fontSize = 32.sp, fontWeight = FontWeight.Medium); Text(" $unit", fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable private fun SmallStat(label: String, value: String, modifier: Modifier) { Column(modifier) { Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) } }
@Composable private fun SectionTitle(title: String, action: String, onClick: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onClick = onClick) { Text(action) } } }
@Composable private fun EmptyState(icon: ImageVector, title: String, description: String) { Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .6f)); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Medium); Spacer(Modifier.height(6.dp)); Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Hint(title: String, text: String, icon: ImageVector) { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Column { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(text, fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun PunchRow(index: Int, instant: Instant, busy: Boolean, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), CircleShape), contentAlignment = Alignment.Center) { Text("${index+1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(14.dp)); Text(instant.atZone(WorkZone).format(timeFormat), fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "删除第 ${index+1} 次打卡", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}
@Composable private fun MonthSelector(month: YearMonth, previous: () -> Unit, next: () -> Unit, today: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = previous) { Icon(Icons.Outlined.ChevronLeft, "上个月") }; Text("${month.year}年 ${month.monthValue}月", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); TextButton(onClick = today) { Text("本月") }; IconButton(onClick = next) { Icon(Icons.Outlined.ChevronRight, "下个月") } }
}
@Composable private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp); Text(subtitle, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun MonthHeatmap(month: YearMonth, summaries: List<DaySummary>) {
    val days = summaries.associateBy { it.record.date.dayOfMonth }
    val offset = month.atDay(1).dayOfWeek.value - 1
    val tint = if (MaterialTheme.colorScheme.surface.luminance() < .2f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
    Row { listOf("一","二","三","四","五","六","日").forEach { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 9.dp, bottom = 8.dp)) } }
    for (week in 0 until (month.lengthOfMonth() + offset + 6) / 7) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (column in 0..6) {
                val day = week * 7 + column - offset + 1
                val valid = day in 1..month.lengthOfMonth()
                val summary = days[day]
                val color = if (!valid) Color.Transparent else if (summary == null) MaterialTheme.colorScheme.onSurface.copy(alpha = .06f) else tint.copy(alpha = (.12f + (summary.seconds / 3600f).coerceAtMost(8f) / 12f))
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(color).semantics { if (valid) contentDescription = "${month.atDay(day)} ${if (summary == null) "无记录" else Overtime.duration(summary.seconds)}" }, contentAlignment = Alignment.Center) { if (valid) Text(day.toString(), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(5.dp))
    }
}


@Composable private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

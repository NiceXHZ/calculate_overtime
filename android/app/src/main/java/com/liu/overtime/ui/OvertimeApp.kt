@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.liu.overtime.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.liu.overtime.shortcuts.ShortcutRequest
import com.liu.overtime.shortcuts.ShortcutAction
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val dayFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
internal fun OvertimeApp(vm: MainViewModel = viewModel(), todayRequest: Int = 0, shortcutRequest: ShortcutRequest? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    val todayCalendar by vm.todayCalendar.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val pinState by vm.widgetPin.state.collectAsStateWithLifecycle()
    val dark = prefs.dark == "dark" || (prefs.dark == "system" && isSystemInDarkTheme())
    val colors = overtimeColorScheme(dark)
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val activeTab = rememberUpdatedState(tab)
        var adding by rememberSaveable { mutableStateOf(false) }
        var selected by remember { mutableStateOf<LocalDate?>(null) }
        var deleting by remember { mutableStateOf<Triple<LocalDate, Int, Instant>?>(null) }
        var recovering by remember { mutableStateOf(false) }
        var choosingCutoff by rememberSaveable { mutableStateOf(false) }
        val otherModalVisible = adding || selected != null || deleting != null || recovering || choosingCutoff
        val pinHelpVisible = !otherModalVisible && pinState.outcome in setOf(PinOutcome.UNCONFIRMED, PinOutcome.UNAVAILABLE)
        val modalVisible = otherModalVisible || pinHelpVisible
        LaunchedEffect(todayRequest) {
            if (todayRequest > 0) {
                tab = 0; adding = false; selected = null; deleting = null; recovering = false; choosingCutoff = false
            }
        }
        LaunchedEffect(shortcutRequest) {
            shortcutRequest?.let { request ->
                tab = if (request.action == ShortcutAction.RECORDS) 1 else 0
                adding = request.action == ShortcutAction.ADD
                selected = null; deleting = null; recovering = false; choosingCutoff = false
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        LifecycleResumeEffect(pinState.request?.token, pinState.outcome, otherModalVisible) {
            val job = if (!otherModalVisible) scope.launch {
                if (pinState.outcome == PinOutcome.CONFIRMED) {
                    vm.widgetPin.dismiss()
                    scope.launch { snackbar.showSnackbar("${pinState.size} 小组件已添加到桌面") }
                } else if (pinState.request != null) {
                    vm.widgetPin.reconcile()
                    if (vm.widgetPin.state.value.outcome == PinOutcome.PENDING) {
                        // Give the returning launcher's callback a short grace period before showing help.
                        delay(maxOf(600L, vm.widgetPin.remainingWaitMillis()))
                        vm.widgetPin.reconcile(promptIfUnknown = true)
                    }
                }
            } else null
            onPauseOrDispose { job?.cancel() }
        }
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
        val density = LocalDensity.current
        var navHeight by remember { mutableIntStateOf(0) }
        val bottomSpace = if (navHeight > 0) with(density) { navHeight.toDp() } + 16.dp else 140.dp
        val backdrop = rememberLayerBackdrop()
        val scrollPositions = List(4) { rememberLazyListState() }
        val summary by vm.summaries.collectAsStateWithLifecycle()
        val summaries = if (summary.month == month) summary.days else emptyList()
        val today = remember(state.records, now.toLocalDate()) { state.records.find { it.date == now.toLocalDate() } }
        val currentMonthTotal by vm.currentMonthTotal.collectAsStateWithLifecycle()
        val total = summaries.sumOf { it.seconds }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { vm.export(it, false) } }
        var exportMonth by rememberSaveable { mutableStateOf(month.toString()) }
        val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { vm.export(it, true, YearMonth.parse(exportMonth)) } }
        LaunchedEffect(vm) {
            vm.notices.collect { notice ->
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                val result = snackbar.showSnackbar(notice.text, if (notice.undo != null) "撤销" else null, withDismissAction = true,
                    duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) notice.undo?.let { vm.undo(it, notice.undoRevision) }
            }
        }
        BackHandler(tab != 0 && !modalVisible) { tab = 0 }
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().semantics { if (modalVisible) hideFromAccessibility() }) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                WebScene(Modifier.fillMaxSize())
                Crossfade(tab, animationSpec = tween(GlassMotion.ContentMillis, easing = GlassMotion.Easing), label = "main-pages") { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxHeight().fillMaxWidth().transitionContent(page == activeTab.value).statusBarsPadding().testTag("main-page"), state = scrollPositions[page], contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottomSpace), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    StatusDot()
                                    Text("加班打卡", color = colors.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("/  " + listOf("记录今日出勤", "查看历史打卡", "统计加班时长", "按你的习惯来")[page], color = colors.onSurfaceVariant, fontSize = 11.sp)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(listOf("今天", "打卡记录", "时间统计", "偏好设置")[page], fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
                            }
                        }
                    }
                    if (!state.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取本地记录…") }
                    state.error?.let { error -> item { GlassCard { Text(error, color = colors.error); TextButton(onClick = { tab = 3 }) { Text("前往恢复备份") } } } }
                    when (page) {
                        0 -> {
                            item {
                                GlassCard(hero = true) {
                                    SoftLabel(now.format(dayFormat))
                                    Spacer(Modifier.height(16.dp))
                                    NumericValue(now.format(DateTimeFormatter.ofPattern("HH:mm")), large = true)
                                    Text("北京时间 · 记录当下的努力", color = colors.onSurfaceVariant, fontSize = 12.sp)
                                    Spacer(Modifier.height(22.dp))
                                    Button(onClick = vm::punch, enabled = state.loaded && state.error == null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(18.dp),
                                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp)) {
                                        Icon(Icons.Outlined.TouchApp, null); Spacer(Modifier.width(10.dp)); Text(if (busy) "正在保存…" else "记录打卡", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(if (today?.punches.isNullOrEmpty()) "今天还没有打卡，轻点一下开始记录" else "今天已记录 ${today.punches.size} 次 · 最近 ${today.punches.last().atZone(WorkZone).format(timeFormat)}", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
                                }
                            }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Metric("${now.monthValue}月加班", Overtime.hours(currentMonthTotal), "小时", Icons.Outlined.Schedule, Modifier.weight(1f))
                                Metric("今日打卡", (today?.punches?.size ?: 0).toString(), "次", Icons.Outlined.CheckCircleOutline, Modifier.weight(1f))
                            } }
                            item { SectionTitle("今天的时间线", "补卡") { adding = true } }
                            item {
                                GlassCard {
                                    if (today?.punches.isNullOrEmpty()) EmptyState(Icons.Outlined.WbSunny, "从容开始新的一天", "打卡后，时间线会出现在这里。")
                                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("today-punch-list")) {
                                        itemsIndexed(today.punches) { index, instant ->
                                            PunchRow(index, instant, busy) { deleting = Triple(today.date, index, instant) }
                                        }
                                    }
                                }
                            }
                            item { Hint("自动保存至本机 · 无需登录", "建议定期导出 JSON 备份，换机也能继续记录。", Icons.Outlined.VerifiedUser) }
                            if (todayCalendar.days.isEmpty()) item { Text(todayCalendar.message, fontSize = 12.sp, color = colors.error) }
                        }
                        1 -> {
                            item { MonthSelector(month, { vm.month.value = month.minusMonths(1) }, { vm.month.value = month.plusMonths(1) }, { vm.month.value = YearMonth.now(WorkZone) }) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = { adding = true }, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy && state.error == null) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); ActionLabel("补录打卡", Modifier.weight(1f, fill = false)) }
                                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(10.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy && state.error == null) { Icon(Icons.Outlined.FileUpload, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); ActionLabel("导入 JSON", Modifier.weight(1f, fill = false)) }
                            } }
                            if (summaries.isEmpty()) item { GlassCard { EmptyState(Icons.AutoMirrored.Outlined.EventNote, "本月还没有记录", "可以直接打卡、补录，或导入网页端 JSON。") } }
                            items(summaries, key = { it.record.date.toString() }) { day ->
                                GlassCard(onClick = { selected = day.record.date }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) { Text(day.record.date.format(dayFormat), fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text("${day.label} · ${day.record.punches.size} 次打卡", fontSize = 11.sp, color = colors.onSurfaceVariant) }
                                        Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        day.record.punches.take(8).forEach { SoftLabel(it.atZone(WorkZone).format(DateTimeFormatter.ofPattern("HH:mm"))) }
                                        if (day.record.punches.size > 8) SoftLabel("+${day.record.punches.size - 8}")
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                                    Spacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("当日加班", color = colors.onSurfaceVariant, fontSize = 11.sp)
                                        Text(Overtime.duration(day.seconds), color = colors.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        2 -> {
                            item { MonthSelector(month, { vm.month.value = month.minusMonths(1) }, { vm.month.value = month.plusMonths(1) }, { vm.month.value = YearMonth.now(WorkZone) }) }
                            item { GlassCard(hero = true) {
                                CardHeading("本月累计加班", "月度概览", Icons.Outlined.Schedule)
                                Spacer(Modifier.height(18.dp))
                                NumericValue(Overtime.hours(total), unit = "小时", large = true)
                                Spacer(Modifier.height(14.dp))
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f)); Spacer(Modifier.height(18.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SmallStat("记录天数", "${summaries.size} 天", Modifier.weight(1f)); SmallStat("加班天数", "${summaries.count { it.seconds > 0 }} 天", Modifier.weight(1f)); SmallStat("日均加班", "${Overtime.hours(if (summaries.isEmpty()) 0 else total / summaries.size)} h", Modifier.weight(1f)) }
                            } }
                            item { GlassCard {
                                CardHeading("本月节奏", "用颜色看见时间", Icons.Outlined.CalendarMonth); Spacer(Modifier.height(20.dp))
                                MonthHeatmap(month, summaries, now.toLocalDate()) { selected = it }
                                Spacer(Modifier.height(14.dp)); HeatLegend()
                                Spacer(Modifier.height(8.dp)); Text("点按有记录的日期，查看当天打卡", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            } }
                            item { Hint(calendar.message, "工作日从 ${prefs.cutoff} 起计算；休息日取首末打卡间隔，不扣休息时间。", Icons.Outlined.Info) }
                            item { OutlinedButton(onClick = { exportMonth = month.toString(); csv.launch("overtime-$month.csv") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = !busy && !refreshing && state.error == null && summaries.isNotEmpty()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("导出本月报表 CSV", fontSize = 14.sp) } }
                        }
                        3 -> {
                            item { GlassCard {
                                CardHeading("数据与备份", "安心记录，随时带走", Icons.Outlined.FolderOpen); Spacer(Modifier.height(10.dp))
                                SettingsAction(Icons.Outlined.FileUpload, "导入 JSON", "合并所有月份，重复导入不会增加记录", !busy && state.error == null) { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                                SettingsAction(Icons.Outlined.FileDownload, "导出完整备份", "网页端兼容 · 所有月份与额外字段", !busy && state.loaded && state.error == null) { export.launch("overtime_log-${now.toLocalDate()}.json") }
                                SettingsAction(Icons.Outlined.Restore, "恢复最近副本", "保留最近 10 次修改前的本地副本", !busy) { recovering = true }
                            } }
                            item { GlassCard {
                                CardHeading("计算与显示", "你的时间，你来定义", Icons.Outlined.Tune); Spacer(Modifier.height(10.dp))
                                SettingsAction(Icons.Outlined.Schedule, "工作日下班时间", "当前 ${prefs.cutoff} · 默认与网页端一致") {
                                    choosingCutoff = true
                                }
                                SettingsAction(Icons.Outlined.CalendarMonth, "更新节假日", if (refreshing) "正在更新…" else calendar.message, !refreshing) { vm.refreshHolidays() }
                                PreferenceSwitch("液态玻璃", "关闭可减少图形开销", prefs.glass) { enabled -> vm.updatePreferences { it.copy(glass = enabled) } }
                                Spacer(Modifier.height(12.dp))
                                Text("外观", fontSize = 13.sp, color = colors.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                AppearancePicker(prefs.dark) { key -> vm.updatePreferences { it.copy(dark = key) } }
                            } }
                            item { GlassCard {
                                CardHeading("滚轮反馈", "每一次拨动，都有回应", Icons.Outlined.TouchApp)
                                PreferenceSwitch("刻度音效", "跟随系统静音与触摸音效设置", prefs.pickerSound) { enabled ->
                                    vm.updatePreferences { it.copy(pickerSound = enabled) }
                                }
                                PreferenceSwitch("轻触震动", "每经过一个刻度轻震动，遵循系统触感设置", prefs.pickerHaptics) { enabled ->
                                    vm.updatePreferences { it.copy(pickerHaptics = enabled) }
                                }
                            } }
                            item { GlassCard {
                                CardHeading("桌面上的小帮手", "让记录更顺手", Icons.Outlined.Widgets)
                                PreferenceSwitch("快捷打卡震动", "小组件与磁贴保存成功后轻震，遵循系统设置", prefs.quickPunchHaptics) { enabled ->
                                    vm.updatePreferences { it.copy(quickPunchHaptics = enabled) }
                                }
                                Text("每种尺寸都以大按钮打卡和今日记录为主。\n小尺寸看最近记录，大尺寸看今日时间线；点按记录可查看今日全部打卡。\n长按桌面 → 小组件 → 加班打卡。", fontSize = 13.sp, lineHeight = 21.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                val widgets = listOf("2×1" to QuickAttendanceWidget::class.java, "2×2" to SmallAttendanceWidget::class.java,
                                    "3×2" to TodayAttendanceWidget::class.java, "4×1" to StripAttendanceWidget::class.java,
                                    "4×2" to MediumAttendanceWidget::class.java, "4×3" to LargeAttendanceWidget::class.java)
                                widgets.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { (label, type) ->
                                            OutlinedButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp), enabled = pinState.outcome != PinOutcome.PENDING,
                                                onClick = { vm.widgetPin.request(type, label) }) {
                                                Text(label, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 13.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                                if (pinState.outcome == PinOutcome.PENDING) Text("等待桌面确认…", fontSize = 12.sp, color = colors.onSurfaceVariant)
                                Text("也可在系统快捷设置中添加「打卡」磁贴。", fontSize = 12.sp, color = colors.onSurfaceVariant)
                                Text("长按应用图标可直接打卡、补录或查看记录；支持的桌面可拖出独立快捷图标。",
                                    fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp))
                            } }
                            item { Hint("加班打卡 ${BuildConfig.VERSION_NAME}", "所有记录保存在本机，北京时间。联网仅用于获取公开节假日。卸载会清除本地数据，请先导出备份。", Icons.Outlined.FavoriteBorder) }
                            item { GlassCard {
                                SettingsAction(Icons.Outlined.Code, "项目源码与说明", "加班打卡 · 网页端与 Android 原生应用") {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/NiceXHZ/calculate_overtime")))
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        android.widget.Toast.makeText(context, "未找到浏览器，请安装浏览器后重试", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } }
                        }
                    }
                }
                }
                }
            }
            val navShape = RoundedCornerShape(28.dp)
            val navModifier = if (prefs.glass && !lowRam && Build.VERSION.SDK_INT >= 31) Modifier.drawBackdrop(backdrop, { navShape }, effects = { blur(18.dp.toPx()); lens(18.dp.toPx(), 24.dp.toPx()) }, onDrawSurface = { drawRect(colors.surface.copy(alpha = if (dark) .92f else .56f)) })
                else Modifier.background(colors.surface.copy(alpha = .98f), navShape)
            Row(Modifier.align(Alignment.BottomCenter).onSizeChanged { navHeight = it.height }.navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 560.dp).fillMaxWidth()
                .shadow(14.dp, navShape, clip = false, ambientColor = colors.onSurface.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .06f))
                .then(navModifier).border(1.dp, if (dark) colors.outlineVariant.copy(alpha = .7f) else Color.White.copy(alpha = .95f), navShape).padding(5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val tabs = listOf(Icons.Outlined.Home to "今天", Icons.AutoMirrored.Outlined.ReceiptLong to "记录", Icons.Outlined.BarChart to "统计", Icons.Outlined.Tune to "设置")
                tabs.forEachIndexed { index, (icon,label) ->
                    val tint by animateColorAsState(if (tab == index) colors.primary else colors.onSurfaceVariant,
                        animationSpec = tween(GlassMotion.ContentMillis, easing = GlassMotion.Easing), label = "tab")
                    val selection by animateColorAsState(if (tab == index) colors.primaryContainer.copy(alpha = .85f) else Color.Transparent,
                        animationSpec = tween(GlassMotion.ContentMillis, easing = GlassMotion.Easing), label = "tab-selection")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(selection).selectable(selected = tab == index, role = Role.Tab) { tab = index }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)); Spacer(Modifier.height(4.dp)); Text(label, fontSize = 11.sp, color = tint, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSpace, start = 20.dp, end = 20.dp))
            }
            val glass = prefs.glass && !lowRam
            when {
                pinHelpVisible -> WidgetPinHelpSheet(pinState, backdrop, glass, onDismiss = vm.widgetPin::dismiss)
                deleting != null -> {
                    val (date, index, instant) = deleting!!
                    GlassConfirmation("删除这次打卡？", "将移除 $date 的第 ${index + 1} 次打卡。修改前副本会保留在本机。",
                        "删除打卡", destructive = true, backdrop = backdrop, glass = glass,
                        onDismiss = { deleting = null }, onConfirm = { vm.remove(date, index, instant) })
                }
                recovering -> GlassConfirmation("恢复最近副本？", "当前记录将回退到最近一次修改前。恢复前建议先导出当前完整备份。",
                    "恢复副本", backdrop = backdrop, glass = glass,
                    onDismiss = { recovering = false }, onConfirm = { vm.recover() })
                adding -> AddPunchSheet(backdrop, glass, prefs, now, onDismiss = { adding = false },
                    onSave = { d, t -> vm.add(d, t) })
                choosingCutoff -> CutoffSheet(prefs.cutoff, backdrop, glass, prefs,
                    onDismiss = { choosingCutoff = false },
                    onSave = { time -> vm.updatePreferences { it.copy(cutoff = time) } })
                selected != null -> {
                    val date = selected!!
                    val record = state.records.find { it.date == date }
                    GlassSheet(date.format(dayFormat), backdrop, glass, onDismiss = { selected = null }, listContent = { close ->
                        if (record?.punches.isNullOrEmpty()) item { Text("当天已无打卡") }
                        itemsIndexed(record?.punches.orEmpty()) { index, instant ->
                            PunchRow(index, instant, busy) { close { deleting = Triple(date, index, instant) } }
                        }
                    }) {}
                }
            }
        }
        }

    }
}

@Composable private fun StatusDot() { Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
@Composable private fun Metric(label: String, value: String, unit: String, icon: ImageVector, modifier: Modifier) {
    GlassCard(modifier, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp)); NumericValue(value, unit = unit)
    }
}
@Composable private fun SmallStat(label: String, value: String, modifier: Modifier) { Column(modifier) { Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(7.dp)); Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
@Composable private fun SectionTitle(title: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(action, fontSize = 13.sp) }
    }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp)); Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp)); Text(description, fontSize = 12.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Hint(title: String, text: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = .4f)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(text, fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun PunchRow(index: Int, instant: Instant, busy: Boolean, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Text("${index+1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.text.BasicText(instant.atZone(WorkZone).format(timeFormat), modifier = Modifier.weight(1f), maxLines = 1, softWrap = false,
            style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 21.sp, stepSize = 1.sp))
        IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "删除第 ${index+1} 次打卡", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
    }
}
@Composable private fun MonthSelector(month: YearMonth, previous: () -> Unit, next: () -> Unit, today: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(19.dp)).background(colors.surface.copy(alpha = .7f))
            .border(1.dp, colors.outlineVariant.copy(alpha = .6f), RoundedCornerShape(19.dp)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = previous) { Icon(Icons.Outlined.ChevronLeft, "上个月", Modifier.size(20.dp)) }
            androidx.compose.foundation.text.BasicText("${month.year}年 ${month.monthValue}月", modifier = Modifier.weight(1f), maxLines = 1, softWrap = false,
                style = LocalTextStyle.current.copy(color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontFeatureSettings = "tnum"),
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 17.sp, stepSize = .5.sp))
            IconButton(onClick = next) { Icon(Icons.Outlined.ChevronRight, "下个月", Modifier.size(20.dp)) }
        }
        TextButton(onClick = today, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("本月", fontSize = 13.sp) }
    }
}
@Composable private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            Spacer(Modifier.height(3.dp)); Text(subtitle, fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(17.dp))
    }
}

@Composable private fun heatColors(): List<Color> = if (MaterialTheme.colorScheme.surface.luminance() < .2f)
    listOf(Color(0xFF25392D), Color(0xFF36583F), Color(0xFF4B7855), Color(0xFF72A67F), Color(0xFFA7DCB3))
    else listOf(Color(0xFFEDF1ED), Color(0xFFD5E7DA), Color(0xFFAFD1B9), Color(0xFF6AAB87), Color(0xFF267251))

@Composable private fun HeatLegend() {
    val heat = heatColors()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("无记录", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        heat.forEach { Box(Modifier.size(12.dp).background(it, RoundedCornerShape(4.dp))) }
        Text("加班渐多", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun MonthHeatmap(month: YearMonth, summaries: List<DaySummary>, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val days = remember(summaries) { summaries.associateBy { it.record.date.dayOfMonth } }
    val offset = month.atDay(1).dayOfWeek.value - 1
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .2f
    val heat = heatColors()
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, label ->
            Box(Modifier.weight(1f).padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, color = if (i >= 5) colors.primary else colors.onSurfaceVariant)
            }
        }
    }
    for (week in 0 until (month.lengthOfMonth() + offset + 6) / 7) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (column in 0..6) {
                val day = week * 7 + column - offset + 1
                val date = if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
                val summary = days[day]
                val level = when {
                    summary == null -> 0
                    summary.seconds == 0L -> 1
                    summary.seconds < 7200 -> 2
                    summary.seconds < 14400 -> 3
                    else -> 4
                }
                val ink = if (level >= 3) { if (dark || level == 3) Color(0xFF123523) else Color.White }
                    else colors.onSurface
                val shape = RoundedCornerShape(10.dp)
                Box(Modifier.weight(1f).aspectRatio(1f).clip(shape).background(if (date == null) Color.Transparent else heat[level])
                    .then(if (date == today) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
                    .then(if (date != null && summary != null) Modifier.clickable(role = Role.Button) { onSelect(date) } else Modifier)
                    .semantics { if (date != null) contentDescription = "$date ${if (summary == null) "无记录" else Overtime.duration(summary.seconds)}${if (date == today) " 今天" else ""}" },
                    contentAlignment = Alignment.Center) {
                    if (date != null) Text(day.toString(), fontSize = 12.sp, fontWeight = if (date == today || summary != null) FontWeight.SemiBold else FontWeight.Normal, color = ink)
                }
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

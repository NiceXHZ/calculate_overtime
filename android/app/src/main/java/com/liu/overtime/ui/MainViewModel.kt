package com.liu.overtime.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liu.overtime.data.*
import com.liu.overtime.domain.*
import com.liu.overtime.widget.AttendanceWidget
import com.liu.overtime.widget.WidgetPinRequests
import com.liu.overtime.widget.QuickPunchFeedback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

data class Preferences(
    val cutoff: LocalTime = LocalTime.of(17, 30),
    val glass: Boolean = true,
    val dark: String = "system",
    val pickerSound: Boolean = true,
    val pickerHaptics: Boolean = true,
    val quickPunchHaptics: Boolean = true,
)
data class Notice(val text: String, val undo: Instant? = null, val undoRevision: Long? = null)
data class MonthSummary(val month: YearMonth, val days: List<DaySummary>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application = application
    val store = RecordStore.get(context)
    val state = store.state
    val widgetPin = WidgetPinRequests.get(context)
    private val shared = context.getSharedPreferences("preferences", 0)
    private val mutablePrefs = MutableStateFlow(Preferences(
        runCatching { LocalTime.parse(shared.getString("cutoff", "17:30")) }.getOrDefault(LocalTime.of(17,30)),
        shared.getBoolean("glass", true), shared.getString("dark", "system") ?: "system",
        shared.getBoolean("pickerSound", true), shared.getBoolean("pickerHaptics", true),
        shared.getBoolean(QuickPunchFeedback.PreferenceKey, true)))
    val preferences = mutablePrefs.asStateFlow()
    val month = MutableStateFlow(YearMonth.now(WorkZone))
    // No timer work while the UI has no lifecycle-active collectors.
    val clock = minuteClock { year ->
        val local = withContext(Dispatchers.IO) { holidays.read(year) }
        todayCalendar.value = local
        if (needsRefresh(local)) refreshYear(year)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), ZonedDateTime.now(WorkZone))
    val calendar = MutableStateFlow(HolidayCalendar(emptyMap(), "正在读取节假日…"))
    val todayCalendar = MutableStateFlow(HolidayCalendar(emptyMap(), "正在读取节假日…"))
    val refreshing = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    private val messages = kotlinx.coroutines.channels.Channel<Notice>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val notices = messages.receiveAsFlow()
    private val holidays = HolidayStore(context)
    val summaries = combine(state, month, calendar, preferences) { records, selectedMonth, holidays, prefs ->
        SummaryInput(records.records, selectedMonth, holidays.days, prefs.cutoff)
    }.distinctUntilChanged().mapLatest { input ->
        withContext(Dispatchers.Default) { MonthSummary(input.month, Overtime.month(input.records, input.month, input.holidays, input.cutoff)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), MonthSummary(month.value, emptyList()))
    val currentMonthTotal = combine(state, clock.map { YearMonth.from(it) }.distinctUntilChanged(), todayCalendar, preferences) { records, currentMonth, holidays, prefs ->
        SummaryInput(records.records, currentMonth, holidays.days, prefs.cutoff)
    }.distinctUntilChanged().mapLatest { input ->
        withContext(Dispatchers.Default) { Overtime.month(input.records, input.month, input.holidays, input.cutoff).sumOf { it.seconds } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), 0L)
    private val refreshMutex = Mutex()
    private val pendingYears = mutableSetOf<Int>()
    private val retryAfter = mutableMapOf<Int, Long>()
    private fun needsRefresh(calendar: HolidayCalendar) = calendar.updated == 0L || System.currentTimeMillis() - calendar.updated > 7 * 86400_000L
    private fun refreshYear(year: Int, notify: Boolean = false) {
        if (!notify && android.os.SystemClock.elapsedRealtime() < (retryAfter[year] ?: 0L)) return
        if (!pendingYears.add(year)) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                refreshMutex.withLock {
                    // Rapid month navigation must neither wait for old HTTP calls nor queue unlimited downloads.
                    if (year != month.value.year && year != LocalDate.now(WorkZone).year) return@withLock
                    val result = withContext(Dispatchers.IO) { holidays.refresh(year) }
                    if (result.refreshFailed) retryAfter[year] = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L
                    else retryAfter.remove(year)
                    if (month.value.year == year) calendar.value = result
                    if (year == LocalDate.now(WorkZone).year) todayCalendar.value = result
                    if (notify) messages.send(Notice(result.message))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (notify) messages.send(Notice(e.message ?: "节假日更新失败")) }
            finally { pendingYears.remove(year); refreshing.value = pendingYears.isNotEmpty() }
        }
    }
    init {
        viewModelScope.launch(Dispatchers.IO) { store.load() }
        viewModelScope.launch {
            month.map { it.year }.distinctUntilChanged().collectLatest { year ->
                val local = withContext(Dispatchers.IO) { holidays.read(year) }
                calendar.value = local
                if (year == LocalDate.now(WorkZone).year) todayCalendar.value = local
                if (needsRefresh(local)) refreshYear(year)
            }
        }
    }

    private fun action(block: suspend () -> Notice) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val notice = withContext(Dispatchers.IO) { block().also { AttendanceWidget.updateAll(context) } }
                messages.send(notice)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.send(Notice(e.message ?: "操作失败，请重试")) }
            finally { busy.value = false }
        }
    }
    fun punch() = action {
        val result = store.punch()
        Notice(if (result.added) "已记录 ${result.instant.atZone(WorkZone).toLocalTime().withNano(0)}" else RecordStore.DUPLICATE_NOTICE,
            if (result.added) result.instant else null, if (result.added) result.revision else null)
    }
    fun punchFromShortcut() {
        if (busy.value) messages.trySend(Notice("正在保存，请稍后再试"))
        else punch()
    }
    fun undo(instant: Instant, revision: Long? = null) = action { store.undoPunch(instant, revision); Notice("已撤销本次打卡") }
    fun add(date: LocalDate, time: LocalTime) = action {
        require(!date.isAfter(LocalDate.now(WorkZone))) { "不能补录未来日期" }
        val instant = date.atTime(time).atZone(WorkZone).toInstant()
        require(!instant.isAfter(Instant.now())) { "不能补录未来时间" }
        val result = store.punch(instant, guard = false)
        Notice("补卡已保存", result.instant, result.revision)
    }
    fun remove(date: LocalDate, index: Int, expected: Instant? = null) = action { store.remove(date, index, expected); Notice("已删除，修改前副本已保留") }
    fun import(uri: Uri) = action {
        val text = context.contentResolver.openInputStream(uri)?.use {
            val bytes = it.readLimited(RecordCodec.MAX_BYTES)
            bytes.toString(Charsets.UTF_8)
        } ?: error("无法打开文件")
        Notice("导入完成，新增 ${store.import(text)} 条打卡；原有记录已保留")
    }
    fun export(uri: Uri, csv: Boolean, exportMonth: YearMonth = month.value) = action {
        store.load()
        check(store.state.value.error == null) { store.state.value.error.orEmpty() }
        val text = if (csv) Overtime.csv(Overtime.month(store.state.value.records, exportMonth, holidays.read(exportMonth.year).days, preferences.value.cutoff)) else store.export()
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入所选位置")
        shared.edit().putLong("lastExport", System.currentTimeMillis()).apply()
        Notice(if (csv) "本月 CSV 已导出" else "完整 JSON 备份已导出，可直接在网页端导入")
    }
    fun recover() = action { store.recoverLatest(); Notice("已恢复最近可用的本地副本") }
    fun updatePreferences(value: Preferences) {
        val previous = preferences.value
        if (previous == value) return
        mutablePrefs.value = value
        shared.edit().putString("cutoff", value.cutoff.toString()).putBoolean("glass", value.glass).putString("dark", value.dark)
            .putBoolean("pickerSound", value.pickerSound).putBoolean("pickerHaptics", value.pickerHaptics)
            .putBoolean(QuickPunchFeedback.PreferenceKey, value.quickPunchHaptics).apply()
        if (previous.dark != value.dark) viewModelScope.launch(Dispatchers.IO) { AttendanceWidget.updateAll(context) }
    }
    fun updatePreferences(transform: (Preferences) -> Preferences) = updatePreferences(transform(preferences.value))
    fun refreshHolidays() {
        if (refreshing.value) return
        refreshYear(month.value.year, notify = true)
    }
}

private data class SummaryInput(val records: List<DayRecord>, val month: YearMonth, val holidays: Map<LocalDate, Holiday>, val cutoff: LocalTime)

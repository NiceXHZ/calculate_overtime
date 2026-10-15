package com.liu.overtime.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liu.overtime.data.*
import com.liu.overtime.domain.*
import com.liu.overtime.widget.AttendanceWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class Preferences(
    val cutoff: LocalTime = LocalTime.of(17, 30),
    val glass: Boolean = true,
    val dark: String = "system",
    val pickerSound: Boolean = true,
    val pickerHaptics: Boolean = true,
)
data class Notice(val text: String, val undo: Instant? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application = application
    val store = RecordStore.get(context)
    val state = store.state
    private val shared = context.getSharedPreferences("preferences", 0)
    private val mutablePrefs = MutableStateFlow(Preferences(
        runCatching { LocalTime.parse(shared.getString("cutoff", "17:30")) }.getOrDefault(LocalTime.of(17,30)),
        shared.getBoolean("glass", true), shared.getString("dark", "system") ?: "system",
        shared.getBoolean("pickerSound", true), shared.getBoolean("pickerHaptics", true)))
    val preferences = mutablePrefs.asStateFlow()
    val month = MutableStateFlow(YearMonth.now(WorkZone))
    val clock = MutableStateFlow(ZonedDateTime.now(WorkZone))
    val calendar = MutableStateFlow(HolidayCalendar(emptyMap(), "正在读取节假日…"))
    val todayCalendar = MutableStateFlow(HolidayCalendar(emptyMap(), "正在读取节假日…"))
    val refreshing = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    private val messages = kotlinx.coroutines.channels.Channel<Notice>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val notices = messages.receiveAsFlow()
    private val holidays = HolidayStore(context)
    init {
        viewModelScope.launch(Dispatchers.IO) { store.load() }
        viewModelScope.launch(Dispatchers.IO) {
            val year = LocalDate.now(WorkZone).year
            todayCalendar.value = holidays.read(year)
        }
        viewModelScope.launch {
            while (isActive) {
                val previousYear = clock.value.year
                clock.value = ZonedDateTime.now(WorkZone)
                if (previousYear != clock.value.year) {
                    todayCalendar.value = withContext(Dispatchers.IO) { holidays.read(clock.value.year) }
                }
                delay(1000)
            }
        }
        viewModelScope.launch {
            month.map { it.year }.distinctUntilChanged().collectLatest { year ->
                val local = withContext(Dispatchers.IO) { holidays.read(year) }
                calendar.value = local
                if (year == LocalDate.now(WorkZone).year) todayCalendar.value = local
                if (local.updated == 0L || System.currentTimeMillis() - local.updated > 7 * 86400_000L) {
                    refreshing.value = true
                    try {
                        val updated = withContext(Dispatchers.IO) { holidays.refresh(year) }
                        calendar.value = updated
                        if (year == LocalDate.now(WorkZone).year) todayCalendar.value = updated
                        withContext(Dispatchers.IO) { AttendanceWidget.updateAll(context) }
                    }
                    finally { refreshing.value = false }
                }
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
            } catch (e: Exception) { messages.send(Notice(e.message ?: "操作失败，请重试")) }
            finally { busy.value = false }
        }
    }
    fun punch() = action {
        val result = store.punch()
        Notice(if (result.added) "已记录 ${result.instant.atZone(WorkZone).toLocalTime().withNano(0)}" else RecordStore.DUPLICATE_NOTICE, if (result.added) result.instant else null)
    }
    fun undo(instant: Instant) = action { store.undoPunch(instant); Notice("已撤销本次打卡") }
    fun add(date: LocalDate, time: LocalTime) = action {
        require(!date.isAfter(LocalDate.now(WorkZone))) { "不能补录未来日期" }
        val instant = date.atTime(time).atZone(WorkZone).toInstant()
        require(!instant.isAfter(Instant.now())) { "不能补录未来时间" }
        store.punch(instant, guard = false)
        Notice("补卡已保存", instant)
    }
    fun remove(date: LocalDate, index: Int) = action { store.remove(date, index); Notice("已删除，修改前副本已保留") }
    fun import(uri: Uri) = action {
        val text = context.contentResolver.openInputStream(uri)?.use {
            val bytes = it.readLimited(RecordCodec.MAX_BYTES)
            bytes.toString(Charsets.UTF_8)
        } ?: error("无法打开文件")
        Notice("导入完成，新增 ${store.import(text)} 条打卡；原有记录已保留")
    }
    fun export(uri: Uri, csv: Boolean) = action {
        val text = if (csv) Overtime.csv(Overtime.month(store.state.value.records, month.value, calendar.value.days, preferences.value.cutoff)) else store.export()
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入所选位置")
        shared.edit().putLong("lastExport", System.currentTimeMillis()).apply()
        Notice(if (csv) "本月 CSV 已导出" else "完整 JSON 备份已导出，可直接在网页端导入")
    }
    fun recover() = action { store.recoverLatest(); Notice("已恢复最近可用的本地副本") }
    fun updatePreferences(value: Preferences) {
        mutablePrefs.value = value
        shared.edit().putString("cutoff", value.cutoff.toString()).putBoolean("glass", value.glass).putString("dark", value.dark)
            .putBoolean("pickerSound", value.pickerSound).putBoolean("pickerHaptics", value.pickerHaptics).apply()
        viewModelScope.launch(Dispatchers.IO) { AttendanceWidget.updateAll(context) }
    }
    fun updatePreferences(transform: (Preferences) -> Preferences) = updatePreferences(transform(preferences.value))
    fun refreshHolidays() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val year = month.value.year
            try {
                val result = withContext(Dispatchers.IO) { holidays.refresh(year) }
                if (month.value.year == year) calendar.value = result
                if (year == LocalDate.now(WorkZone).year) todayCalendar.value = result
                withContext(Dispatchers.IO) { AttendanceWidget.updateAll(context) }
                messages.send(Notice(result.message))
            } finally { refreshing.value = false }
        }
    }
}

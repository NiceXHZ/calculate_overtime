package com.liu.overtime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import com.liu.overtime.MainActivity
import com.liu.overtime.R
import com.liu.overtime.data.*
import com.liu.overtime.domain.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

open class AttendanceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) = refresh(context)
    private fun refresh(context: Context) {
        val pending = goAsync()
        widgetScope.launch { try { updateAll(context) } finally { pending.finish() } }
    }

    companion object {
        fun updateAll(context: Context) {
            // A launcher refresh failure must never turn a persisted punch into a reported failure.
            runCatching { updateAllInternal(context) }.onFailure { android.util.Log.w("AttendanceWidget", "Widget refresh failed", it) }
        }
        private fun updateAllInternal(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val classes = listOf(QuickAttendanceWidget::class.java, SmallAttendanceWidget::class.java,
                TodayAttendanceWidget::class.java, StripAttendanceWidget::class.java,
                MediumAttendanceWidget::class.java, LargeAttendanceWidget::class.java)
            val widgetIds = classes.map { manager.getAppWidgetIds(ComponentName(context, it)) }
            if (widgetIds.all { it.isEmpty() }) return
            val store = RecordStore.get(context)
            store.load()
            val state = store.state.value
            val now = ZonedDateTime.now(WorkZone)
            val today = state.records.find { it.date == now.toLocalDate() }
            val prefs = context.getSharedPreferences("preferences", 0)
            val dark = when (prefs.getString("dark", "system")) {
                "dark" -> true
                "light" -> false
                else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            val widgetData = WidgetData(now, today?.punches.orEmpty().takeLast(19), state.error)
            val defaults = listOf(130f to 60f, 130f to 180f, 210f to 180f, 280f to 60f, 280f to 180f, 280f to 270f)
            widgetIds.forEachIndexed { index, ids ->
                ids.forEach { id ->
                    runCatching {
                        val options = manager.getAppWidgetOptions(id)
                        fun view(width: Float, height: Float) = WidgetViews.create(context, id, width, height, widgetData, dark)
                        val sizes = WidgetViews.sizesFor(options, android.util.SizeF(defaults[index].first, defaults[index].second))
                        val views = if (android.os.Build.VERSION.SDK_INT >= 31) RemoteViews(sizes.associateWith { view(it.width,it.height) })
                            else if (sizes.size == 1) view(sizes.first().width, sizes.first().height)
                            else RemoteViews(view(sizes.last().width,sizes.last().height),view(sizes.first().width,sizes.first().height))
                        manager.updateAppWidget(id, views)
                    }.onFailure { android.util.Log.w("AttendanceWidget", "Unable to update widget $id", it) }
                }
            }
        }
    }
}
class QuickAttendanceWidget : AttendanceWidget()
class TodayAttendanceWidget : AttendanceWidget()
class StripAttendanceWidget : AttendanceWidget()
class SmallAttendanceWidget : AttendanceWidget()
class MediumAttendanceWidget : AttendanceWidget()
class LargeAttendanceWidget : AttendanceWidget()

class PunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.liu.overtime.PUNCH") return
        val pending = goAsync()
        widgetScope.launch {
            try { recordFromOutside(context) } finally { pending.finish() }
        }
    }
}

class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        widgetScope.launch { try { AttendanceWidget.updateAll(context) } finally { pending.finish() } }
    }
}

suspend fun recordFromOutside(context: Context) {
    val message = try {
        val result = RecordStore.get(context).punch()
        AttendanceWidget.updateAll(context)
        if (result.added) "打卡已保存 · ${result.instant.atZone(WorkZone).format(DateTimeFormatter.ofPattern("HH:mm:ss"))}" else RecordStore.DUPLICATE_NOTICE
    } catch (e: Exception) { "打卡未保存：${e.message}" }
    withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}

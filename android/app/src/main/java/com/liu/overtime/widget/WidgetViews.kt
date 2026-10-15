package com.liu.overtime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.StaticLayout
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.liu.overtime.MainActivity
import com.liu.overtime.R
import com.liu.overtime.domain.WorkZone
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** The newest time leads, earlier times stay readable, and the action has its own hit area. */
data class WidgetData(val now: ZonedDateTime, val punches: List<Instant>, val error: String?)

enum class WidgetLayout(val resource: Int) {
    QUICK(R.layout.widget_quick), SQUARE(R.layout.widget_small), TODAY(R.layout.widget_today),
    STRIP(R.layout.widget_strip), DASHBOARD(R.layout.widget_medium), TIMELINE(R.layout.widget_large)
}

object WidgetViews {
    internal fun sizesFor(options: Bundle, fallback: SizeF): List<SizeF> {
        if (Build.VERSION.SDK_INT >= 31) {
            val exact = androidx.core.os.BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct().take(16)
            if (exact.isNotEmpty()) return exact
        }
        // Range bounds describe portrait and landscape together; min width + min height is not a real size.
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallback.width.toInt()).coerceAtLeast(1)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth).coerceAtLeast(minWidth)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, fallback.height.toInt()).coerceAtLeast(1)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight).coerceAtLeast(minHeight)
        return listOf(SizeF(minWidth.toFloat(), maxHeight.toFloat()), SizeF(maxWidth.toFloat(), minHeight.toFloat())).distinct()
    }

    fun layoutFor(width: Float, height: Float): WidgetLayout = when {
        height < 100f -> if (width >= 250f) WidgetLayout.STRIP else WidgetLayout.QUICK
        width < 180f -> WidgetLayout.SQUARE
        width < 250f -> WidgetLayout.TODAY
        height < 210f -> WidgetLayout.DASHBOARD
        else -> WidgetLayout.TIMELINE
    }

    fun create(context: Context, id: Int, width: Float, height: Float, data: WidgetData, dark: Boolean): RemoteViews {
        val kind = layoutFor(width, height)
        val views = RemoteViews(context.packageName, kind.resource)
        val primary = Color.parseColor(if (dark) "#F5FAFC" else "#182B32")
        val secondary = Color.parseColor(if (dark) "#CDD9DF" else "#465D64")
        val buttonText = Color.parseColor(if (dark) "#102C20" else "#FFFFFF")
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val paint = TextPaint().apply { this.typeface = typeface; fontFeatureSettings = "tnum" }
        fun pixels(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        fun lineHeight(value: String, size: Float, available: Float): Float {
            paint.textSize = pixels(size)
            val builder = StaticLayout.Builder.obtain(value, 0, value.length, paint, (available*density).toInt().coerceAtLeast(1))
                .setIncludePad(false).setMaxLines(1)
            if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
            return ceil(builder.build().height / density)
        }
        fun fit(value: String, preferred: Float, available: Float, maxHeight: Float = Float.MAX_VALUE): Float {
            var low = 1f
            var high = preferred
            repeat(12) {
                val candidate = (low + high) / 2
                paint.textSize = pixels(candidate)
                if (paint.measureText(value) <= available.coerceAtLeast(1f) * density &&
                    (maxHeight == Float.MAX_VALUE || lineHeight(value, candidate, available) <= maxHeight)) low = candidate else high = candidate
            }
            return low
        }
        fun text(target: RemoteViews, view: Int, value: String, preferred: Float, available: Float, color: Int = primary, maxHeight: Float = Float.MAX_VALUE): Float {
            val size = fit(value, preferred, available, maxHeight)
            target.setTextViewText(view, value)
            target.setTextColor(view, color)
            target.setTextViewTextSize(view, TypedValue.COMPLEX_UNIT_SP, size)
            return lineHeight(value, size, available)
        }
        fun layoutWidth(view: Int, dp: Float) {
            if (Build.VERSION.SDK_INT >= 31) views.setViewLayoutWidth(view, dp, TypedValue.COMPLEX_UNIT_DIP)
        }
        fun layoutHeight(view: Int, dp: Float) {
            if (Build.VERSION.SDK_INT >= 31) views.setViewLayoutHeight(view, dp, TypedValue.COMPLEX_UNIT_DIP)
        }
        views.setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background_light)
        views.setInt(R.id.widget_punch, "setBackgroundResource", if (dark) R.drawable.widget_button_dark else R.drawable.widget_button_light)
        val punches = data.punches.filter { it.atZone(WorkZone).toLocalDate() == data.now.toLocalDate() }.sortedDescending()
        val latest = punches.firstOrNull()?.atZone(WorkZone)
        val secondsFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
        val latestDescription = if (data.error != null) "本地记录读取失败，打开应用恢复" else
            latest?.let { "最近打卡 ${it.format(secondsFormat)}，点按查看今日全部记录" } ?: "今天还没有打卡，点按查看今日记录"
        val horizontal = kind == WidgetLayout.QUICK || kind == WidgetLayout.STRIP
        if (horizontal) {
            val compact = kind == WidgetLayout.QUICK
            val pad = if (height < 52f) 4f else 6f
            views.setViewPadding(R.id.widget_root, (pad*density).toInt(), (pad*density).toInt(), (pad*density).toInt(), (pad*density).toInt())
            val buttonWidth = if (compact) (width * .44f).coerceAtLeast(48f) else (width * .36f).coerceIn(88f, 124f)
            layoutWidth(R.id.widget_punch, buttonWidth)
            val contentWidth = width - 2*pad - if (Build.VERSION.SDK_INT >= 31) buttonWidth else if (compact) 52f else 96f
            val title = if (data.error != null) "需要恢复" else "最近打卡"
            val value = if (data.error != null) "读取失败" else latest?.format(if (compact) DateTimeFormatter.ofPattern("HH:mm") else secondsFormat) ?: "未打卡"
            val mainId = if (compact) R.id.widget_status else R.id.widget_detail
            val titleId = if (compact) R.id.widget_date else R.id.widget_status
            val titleVisible = height >= 52f && !(height < 70f && context.resources.configuration.fontScale > 1.3f)
            views.setViewVisibility(titleId, if (titleVisible) View.VISIBLE else View.GONE)
            val titleHeight = text(views, titleId, if (compact) "最近" else title, 12f, contentWidth - 4, secondary)
            val contentHeight = height-2*pad
            text(views, mainId, value, if (compact) 22f else 26f, contentWidth - 4,
                maxHeight = contentHeight - if (titleVisible) titleHeight else 0f)
            text(views, R.id.widget_punch, if (data.error == null) "打卡" else "恢复", if (height < 52) 18f else 22f,
                buttonWidth - 8, buttonText, maxHeight = contentHeight-4)
            views.setContentDescription(mainId, latestDescription)
        } else {
            val pad = if (height < 128f) 6f else if (height < 155f || width < 180f) 8f else 12f
            val innerWidth = width - 2*pad
            views.setViewPadding(R.id.widget_root, (pad*density).toInt(), (pad*density).toInt(), (pad*density).toInt(), (pad*density).toInt())
            val dateText = data.now.format(DateTimeFormatter.ofPattern(if (width < 180) "M/d" else "M/d E", Locale.CHINA))
            paint.textSize = pixels(12f)
            val dateWidth = ceil(paint.measureText(dateText) / density)
            val headerVisible = height >= 155 && !(height < 160 && context.resources.configuration.fontScale > 1.3f)
            views.setViewVisibility(R.id.widget_header, if (headerVisible) View.VISIBLE else View.GONE)
            val measuredHeader = maxOf(text(views, R.id.widget_date, if (width < 180) "最近打卡" else "今日记录", 13f, innerWidth-dateWidth-6, secondary),
                text(views, R.id.widget_status, dateText, 12f, dateWidth+1, secondary))
            val headerHeight = if (headerVisible) measuredHeader else 0f
            val hasTime = data.error == null && latest != null
            val secondText = if (hasTime) latest.format(DateTimeFormatter.ofPattern(":ss")) else ""
            paint.textSize = pixels(17f)
            val secondWidth = if (hasTime) ceil(paint.measureText(secondText)/density) + 2 else 0f
            val mainText = if (data.error != null) "读取失败" else latest?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "未打卡"
            val mainFont = if (!hasTime) 23f else if (height < 155) 28f else if (width < 180) 30f else if (height >= 210) 42f else 36f
            val buttonHeight = if (Build.VERSION.SDK_INT < 31) 48f else if (height < 128f) 44f else if (height >= 210) 56f else 48f
            val heroBudget = (height-2*pad-headerHeight-buttonHeight-8).coerceAtLeast(18f)
            val heroHeight = text(views, R.id.widget_detail, mainText, mainFont, innerWidth-secondWidth, maxHeight = heroBudget)
            views.setViewVisibility(R.id.widget_seconds, if (hasTime) View.VISIBLE else View.GONE)
            text(views, R.id.widget_seconds, secondText, 17f, secondWidth.coerceAtLeast(1f), secondary)
            layoutHeight(R.id.widget_punch, buttonHeight)
            text(views, R.id.widget_punch, if (data.error == null) "打卡" else "恢复", 22f, innerWidth-12, buttonText, maxHeight = buttonHeight-4)
            val availableHeight = (height - 2*pad - headerHeight - heroHeight - buttonHeight - 8f).coerceAtLeast(0f)
            val columns = if (kind == WidgetLayout.TIMELINE || width < 250) 1
                else if (width >= 280 && context.resources.configuration.fontScale <= 1.3f) 3 else 2
            val cellWidth = (innerWidth - (columns-1)*6f) / columns
            val historyFont = if (height < 155) 16f else if (width >= 250f && height >= 210f) 20f else 17f
            val chipPadding = if (height < 155) 1f else 2f
            val rowHeight = lineHeight("00:00:00", fit("00:00:00", historyFont, cellWidth-12f), cellWidth-12f) + 2*chipPadding + 2
            val capacity = (availableHeight / rowHeight).toInt().coerceIn(0, 6) * columns
            val previous = punches.drop(1).take(capacity)
            views.removeAllViews(R.id.widget_records)
            if (previous.isNotEmpty() && data.error == null) {
                previous.chunked(columns).forEach { times ->
                    val row = RemoteViews(context.packageName, R.layout.widget_record_row)
                    val targets = listOf(R.id.record_left, R.id.record_right, R.id.record_third)
                    targets.forEachIndexed { index, target ->
                        val instant = times.getOrNull(index)
                        if (instant != null) {
                            val value = instant.atZone(WorkZone).format(secondsFormat)
                            text(row, target, value, historyFont, cellWidth-12, secondary)
                            row.setViewPadding(target,(6*density).toInt(),(chipPadding*density).toInt(),(6*density).toInt(),(chipPadding*density).toInt())
                            row.setInt(target, "setBackgroundResource", if (dark) R.drawable.widget_record_chip_dark else R.drawable.widget_record_chip_light)
                            row.setContentDescription(target, "今日打卡 $value，点按查看全部")
                        } else row.setViewVisibility(target, if (index >= columns) View.GONE else View.INVISIBLE)
                    }
                    row.setViewVisibility(R.id.record_gap, if (columns < 2) View.GONE else View.VISIBLE)
                    row.setViewVisibility(R.id.record_gap2, if (columns < 3) View.GONE else View.VISIBLE)
                    views.addView(R.id.widget_records, row)
                }
            } else if (availableHeight >= lineHeight("点按时间查看记录",12f,innerWidth-12)+6f) {
                val row = RemoteViews(context.packageName, R.layout.widget_record_row)
                row.setViewVisibility(R.id.record_right, View.GONE)
                row.setViewVisibility(R.id.record_third, View.GONE)
                row.setViewVisibility(R.id.record_gap, View.GONE)
                row.setViewVisibility(R.id.record_gap2, View.GONE)
                val hint = if (data.error != null) "打开应用恢复" else if (latest == null) "点按下方开始记录" else "点按时间查看记录"
                text(row, R.id.record_left, hint, 12f, innerWidth-12, secondary)
                views.addView(R.id.widget_records, row)
            }
            views.setContentDescription(R.id.widget_detail, latestDescription)
            views.setContentDescription(R.id.widget_hero, latestDescription)
            views.setContentDescription(R.id.widget_records, "今日更早的打卡记录，点按查看全部")
        }
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_TODAY, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val punch = PendingIntent.getBroadcast(context, id, Intent(context, PunchReceiver::class.java).setAction("com.liu.overtime.PUNCH"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Only the dedicated button saves a punch. Reading a timestamp must never create a record.
        views.setOnClickPendingIntent(R.id.widget_root, open)
        views.setOnClickPendingIntent(R.id.widget_punch, if (data.error == null) punch else open)
        if (kind == WidgetLayout.QUICK) views.setOnClickPendingIntent(R.id.widget_status, open)
        else views.setOnClickPendingIntent(R.id.widget_detail, open)
        return views
    }
}

package com.liu.overtime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.liu.overtime.domain.WorkZone
import com.liu.overtime.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLayoutTest {
    private val date = LocalDate.of(2026, 10, 15)
    private val punches = listOf(9 to 1, 12 to 0, 13 to 5, 19 to 10)
        .map { (h,m) -> date.atTime(h,m).atZone(WorkZone).toInstant() }
    private val data = WidgetData(date.atTime(19,10).atZone(WorkZone), punches, null)

    private fun context(fontScale: Float = 1f): Context {
        val application = RuntimeEnvironment.getApplication()
        val config = Configuration(application.resources.configuration).apply { this.fontScale = fontScale }
        return ContextThemeWrapper(application.createConfigurationContext(config), android.R.style.Theme_Material_Light)
    }
    private fun render(context: Context, name: String, width: Int, height: Int, data: WidgetData, dark: Boolean): ViewGroup {
        val view = WidgetViews.create(context, 9, width.toFloat(), height.toFloat(), data, dark).apply(context, null) as ViewGroup
        val density = context.resources.displayMetrics.density
        val w = (width*density).toInt(); val h = (height*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0,0,w,h)
        val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/screenshots/widgets-$name-${if (dark) "dark" else "light"}.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        return view
    }
    private fun bounds(root: ViewGroup, child: View) = Rect().also {
        child.getDrawingRect(it); root.offsetDescendantRectToMyCoords(child,it)
    }
    private fun validate(root: ViewGroup, name: String): List<String> {
        val problems = mutableListOf<String>()
        val density = root.resources.displayMetrics.density
        val button = root.findViewById<TextView>(R.id.widget_punch)
        if (bounds(root, button).bottom > root.height) problems += "$name button overflows"
        if (button.height < 30*density-1) problems += "$name button too short"
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView && view.text.isNotEmpty()) {
                val textBounds = bounds(root, view)
                if (textBounds.top < 0 || textBounds.bottom > root.height || textBounds.left < 0 || textBounds.right > root.width)
                    problems += "$name outside: ${view.text} $textBounds"
                val layout = view.layout
                if (layout != null && layout.height > view.height-view.paddingTop-view.paddingBottom+1)
                    problems += "$name vertical clip: ${view.text} layout=${layout.height} available=${view.height-view.paddingTop-view.paddingBottom}"
                if (layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 || layout.getLineWidth(it) > view.width-view.paddingLeft-view.paddingRight+1 })
                    problems += "$name truncated: ${view.text}"
                if (view.id == R.id.record_left || view.id == R.id.record_right || view.id == R.id.record_third) {
                    if (view.text.contains(':') && view.textSize/density < 14f) problems += "$name record text smaller than 14 dp"
                    if (textBounds.bottom > bounds(root,button).top) problems += "$name record overlaps action"
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return problems
    }

    @Test fun regularAndLargeFontsAcrossLauncherSizes() {
        val presets = listOf(Triple("2x1",130,60), Triple("2x2",130,180), Triple("3x2",210,180),
            Triple("4x1",280,60), Triple("4x2",280,180), Triple("4x3",280,270),
            Triple("square-110",110,110), Triple("square-130",130,130), Triple("square-150",150,150),
            Triple("today-min",180,128), Triple("dashboard-min",250,128),
            Triple("quick-min",110,40), Triple("strip-min",250,40))
        val problems = mutableListOf<String>()
        for (scale in listOf(1f,1.5f)) for (dark in listOf(false,true)) for ((name,width,height) in presets) {
            val label = "$name-fs${(scale*100).toInt()}"
            val view = render(context(scale), label, width, height, data, dark)
            problems += validate(view, "$label-$dark")
            val button = view.findViewById<TextView>(R.id.widget_punch)
            assertEquals("打卡",button.text.toString())
            assertEquals(android.graphics.Color.parseColor(if (dark) "#102C20" else "#FFFFFF"), button.currentTextColor)
            val latest = view.findViewById<TextView>(if (height < 100 && width < 250) R.id.widget_status else R.id.widget_detail)
            assertTrue("$label accessible full timestamp", latest.contentDescription.contains("19:10:00"))
            if (height >= 100) {
                assertTrue("$label full-width action", button.width >= view.width*.75f)
                assertTrue("$label 44dp minimum action",button.height >= 44*view.resources.displayMetrics.density-1)
                val time = view.findViewById<TextView>(R.id.widget_detail)
                val seconds = view.findViewById<TextView>(R.id.widget_seconds)
                assertEquals("$label timestamp baseline",bounds(view,time).top+time.baseline,bounds(view,seconds).top+seconds.baseline)
                if (name == "square-130" && scale == 1f) {
                    assertNotNull("A regular 2x2 should also show an earlier record",view.findViewById<TextView>(R.id.record_left))
                    assertEquals("13:05:00",view.findViewById<TextView>(R.id.record_left).text.toString())
                }
            }
        }
        assertTrue(problems.joinToString("\n"),problems.isEmpty())
    }

    @Test fun emptySingleManyAndErrorStatesFit() {
        val many = (0..15).map { date.atTime(it, 10).atZone(WorkZone).toInstant() }
        val states = mapOf("empty" to data.copy(punches=emptyList()), "single" to data.copy(punches=punches.takeLast(1)),
            "many" to data.copy(punches=many), "error" to data.copy(error="unreadable"))
        val problems = mutableListOf<String>()
        for ((name,value) in states) for (dark in listOf(false,true)) for ((width,height) in listOf(130 to 130,280 to 180,280 to 270)) {
            val label = "$name-${width}x$height"
            val view = render(context(), label,width,height,value,dark)
            problems += validate(view, label)
            if (name=="error") assertEquals("恢复",view.findViewById<TextView>(R.id.widget_punch).text.toString())
        }
        assertTrue(problems.joinToString("\n"),problems.isEmpty())
    }

    @Test fun readingQuickTimestampOpensTodayInsteadOfPunching() {
        val context = context()
        val view = render(context,"quick-tap",130,60,data,false)
        assertTrue(view.findViewById<View>(R.id.widget_status).performClick())
        val intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("Timestamp should open the application",intent)
        assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_SHOW_TODAY,false))
        assertEquals(MainActivity::class.java.name,intent.component!!.className)
    }

    @Test fun launcherBoundsPreservePortraitHeightAndExactSizesWin() {
        val options = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,130)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,280)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,60)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)
        }
        val fallback = android.util.SizeF(110f,110f)
        assertEquals(listOf(android.util.SizeF(130f,180f),android.util.SizeF(280f,60f)),WidgetViews.sizesFor(options,fallback))
        options.putParcelableArrayList(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES,
            arrayListOf(android.util.SizeF(147f,153f),android.util.SizeF(310f,72f),android.util.SizeF(0f,0f)))
        assertEquals(listOf(android.util.SizeF(147f,153f),android.util.SizeF(310f,72f)),WidgetViews.sizesFor(options,fallback))
        assertEquals(listOf(fallback),WidgetViews.sizesFor(android.os.Bundle(),fallback))
    }

    @Test fun textContrastStaysStrongAcrossThemes() {
        val context = context()
        for (dark in listOf(false,true)) {
            val view = render(context,"contrast",280,180,data,dark)
            val background = android.graphics.Color.parseColor(if (dark) "#242A30" else "#F1F5F6")
            val primary = view.findViewById<TextView>(R.id.widget_detail).currentTextColor
            val secondary = view.findViewById<TextView>(R.id.widget_date).currentTextColor
            val button = view.findViewById<TextView>(R.id.widget_punch).currentTextColor
            val actionBackground = android.graphics.Color.parseColor(if (dark) "#B4EDCF" else "#196B51")
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(primary,background)>=7)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(secondary,background)>=4.5)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(button,actionBackground)>=4.5)
        }
    }

    @Test @Config(sdk=[28]) fun legacyAndroidStillFits() {
        val problems = mutableListOf<String>()
        for ((width,height) in listOf(130 to 60,130 to 130,210 to 180,280 to 60,280 to 180,280 to 270)) {
            val view = render(context(),"legacy-${width}x$height",width,height,data,false)
            problems += validate(view,"legacy-${width}x$height")
        }
        assertTrue(problems.joinToString("\n"),problems.isEmpty())
    }

    @Test fun allSixProvidersDeclareConventionalGridSizes() {
        val context = RuntimeEnvironment.getApplication()
        val definitions = listOf(Triple(R.xml.widget_quick_info,2,1),Triple(R.xml.widget_small_info,2,2),
            Triple(R.xml.widget_today_info,3,2),Triple(R.xml.widget_strip_info,4,1),
            Triple(R.xml.widget_medium_info,4,2),Triple(R.xml.widget_large_info,4,3))
        definitions.forEach { (resource,columns,rows) ->
            context.resources.getXml(resource).use { xml ->
                while (xml.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) xml.next()
                val ns = "http://schemas.android.com/apk/res/android"
                assertEquals(columns,xml.getAttributeIntValue(ns,"targetCellWidth",0))
                assertEquals(rows,xml.getAttributeIntValue(ns,"targetCellHeight",0))
            }
        }
        assertEquals(WidgetLayout.SQUARE,WidgetViews.layoutFor(130f,130f))
        assertEquals(WidgetLayout.SQUARE,WidgetViews.layoutFor(110f,110f))
        assertEquals(WidgetLayout.STRIP,WidgetViews.layoutFor(280f,60f))
        assertEquals(WidgetLayout.TIMELINE,WidgetViews.layoutFor(280f,270f))
    }
}

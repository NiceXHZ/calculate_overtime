package com.liu.overtime.data

import android.content.Context
import android.util.AtomicFile
import com.liu.overtime.domain.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class HolidayCalendar(val days: Map<LocalDate, Holiday>, val message: String, val updated: Long = 0)

class HolidayStore(private val context: Context) {
    companion object { private val cacheLock = Any() }
    private fun cache(year: Int) = AtomicFile(File(context.filesDir, "holidays-$year.json"))
    private fun parse(text: String, year: Int): Map<LocalDate, Holiday> {
        val root = BackupJson.parseToJsonElement(text).jsonObject
        val days = root["days"]?.jsonArray ?: error("节假日数据无效")
        require(days.isNotEmpty()) { "节假日数据为空" }
        return days.associate { item ->
            val obj = item.jsonObject
            LocalDate.parse(obj.getValue("date").jsonPrimitive.content) to Holiday(
                obj.getValue("isOffDay").jsonPrimitive.boolean,
                obj.getValue("name").jsonPrimitive.content + if (obj.getValue("isOffDay").jsonPrimitive.boolean) "" else " · 调休上班"
            )
        }.filterKeys { it.year == year }
    }

    fun read(year: Int): HolidayCalendar {
        val cached = synchronized(cacheLock) { runCatching { cache(year).openRead().bufferedReader().use { parse(it.readText(), year) } }.getOrNull() }
        if (cached != null) {
            val updated = cache(year).baseFile.lastModified()
            val stale = System.currentTimeMillis() - updated > 7 * 86400_000L
            return HolidayCalendar(cached, if (stale) "节假日缓存待更新，继续使用已保存数据" else "已应用中国法定节假日与调休", updated)
        }
        val bundled = runCatching { context.assets.open("holidays-$year.json").bufferedReader().use { parse(it.readText(), year) } }.getOrNull()
        return if (bundled != null) HolidayCalendar(bundled, "已应用内置 $year 年节假日；可联网更新")
        else HolidayCalendar(emptyMap(), "$year 年节假日未就绪，暂按周一至周五计算，请联网更新")
    }

    fun refresh(year: Int): HolidayCalendar {
        val urls = listOf("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json")
        var last: Exception? = null
        for (url in urls) try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            val text = try {
                require(connection.responseCode == 200) { "节假日服务暂不可用" }
                connection.inputStream.use { stream ->
                    val bytes = stream.readLimited(1024 * 1024)
                    bytes.toString(Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
            parse(text, year)
            synchronized(cacheLock) {
                val file = cache(year)
                val output = file.startWrite()
                try { output.write(text.toByteArray()); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
            return read(year)
        } catch (e: Exception) { last = e }
        val old = read(year)
        return old.copy(message = "更新失败，${old.message}。${last?.message.orEmpty()}")
    }
}

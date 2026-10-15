package com.liu.overtime.data

import android.content.Context
import android.util.AtomicFile
import com.liu.overtime.domain.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth

data class HolidayCalendar(val days: Map<LocalDate, Holiday>, val message: String, val updated: Long = 0, val refreshFailed: Boolean = false)

class HolidayStore(private val context: Context) {
    companion object { private val cacheLock = Any() }
    private fun cache(year: Int) = AtomicFile(File(context.filesDir, "holidays-$year.json"))
    internal fun parse(text: String, year: Int): Map<LocalDate, Holiday> {
        val root = BackupJson.parseToJsonElement(text).jsonObject
        require(root["year"]?.jsonPrimitive?.intOrNull == year) { "节假日年份不匹配" }
        val days = root["days"]?.jsonArray ?: error("节假日数据无效")
        require(days.isNotEmpty()) { "节假日数据为空" }
        val result = days.associate { item ->
            val obj = item.jsonObject
            require(!obj.getValue("isOffDay").jsonPrimitive.isString) { "节假日类型无效" }
            LocalDate.parse(obj.getValue("date").jsonPrimitive.content) to Holiday(
                obj.getValue("isOffDay").jsonPrimitive.boolean,
                obj.getValue("name").jsonPrimitive.content + if (obj.getValue("isOffDay").jsonPrimitive.boolean) "" else " · 调休上班"
            )
        }
        require(result.keys.any { it.year == year }) { "缺少对应年份的节假日" }
        return result
    }

    private fun saved(year: Int): Pair<Map<LocalDate, Holiday>, Long>? {
        val cached = synchronized(cacheLock) {
            runCatching {
                val file = cache(year)
                val days = file.openRead().use { parse(it.readLimited(1024 * 1024).toString(Charsets.UTF_8), year) }
                days to file.baseFile.lastModified()
            }.getOrNull()
        }
        return cached ?: runCatching {
            context.assets.open("holidays-$year.json").use { parse(it.readLimited(1024 * 1024).toString(Charsets.UTF_8), year) } to 0L
        }.getOrNull()
    }

    fun read(year: Int): HolidayCalendar {
        val primary = saved(year)
        // The next year's notice may amend the preceding December.
        val days = (primary?.first.orEmpty() + saved(year + 1)?.first.orEmpty()).filterKeys { it.year == year }
        if (primary == null) return HolidayCalendar(days, if (days.isEmpty())
            "$year 年节假日未就绪，暂按周一至周五计算，请联网更新" else "$year 年节假日资料不完整，请联网更新")
        val updated = primary.second
        val stale = System.currentTimeMillis() - updated > 7 * 86400_000L
        val message = if (updated == 0L) "已应用内置 $year 年节假日；可联网更新"
            else if (stale) "节假日缓存待更新，继续使用已保存数据" else "已应用中国法定节假日与调休"
        return HolidayCalendar(days, message, updated)
    }

    private fun download(year: Int) {
        val urls = listOf("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json")
        var last: Exception? = null
        for (url in urls) try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            val text = try {
                require(connection.responseCode == 200) { "节假日服务暂不可用" }
                connection.inputStream.use { it.readLimited(1024 * 1024).toString(Charsets.UTF_8) }
            } finally { connection.disconnect() }
            parse(text, year)
            synchronized(cacheLock) {
                val file = cache(year)
                val output = file.startWrite()
                try { output.write(text.toByteArray()); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
            return
        } catch (e: Exception) { last = e }
        throw last ?: IllegalStateException("节假日服务暂不可用")
    }

    fun refresh(year: Int): HolidayCalendar {
        return try {
            download(year)
            if (YearMonth.now(WorkZone) >= YearMonth.of(year, 12)) {
                // A missing future notice must not discard a valid current-year calendar.
                runCatching { download(year + 1) }
            }
            read(year)
        } catch (e: Exception) {
            val old = read(year)
            old.copy(message = "更新失败，${old.message}。${e.message.orEmpty()}", refreshFailed = true)
        }
    }
}

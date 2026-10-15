package com.liu.overtime.domain

import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

val WorkZone: ZoneId = ZoneId.of("Asia/Shanghai")
val BackupJson = Json { prettyPrint = true }
private val isoPattern = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})")
private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
private val exportTime = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS]XXX")

data class DayRecord(val date: LocalDate, val punches: List<Instant>, val extra: JsonObject = JsonObject(emptyMap()))
data class Holiday(val off: Boolean, val name: String)
data class DaySummary(val record: DayRecord, val seconds: Long, val label: String, val off: Boolean)

object RecordCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun decode(text: String): List<DayRecord> {
        require(text.toByteArray().size <= MAX_BYTES) { "文件超过 8 MB，请拆分后导入" }
        val root = BackupJson.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject ?: error("JSON 顶层必须是日期对象")
        return root.map { (key, value) ->
            require(datePattern.matches(key)) { "日期须使用 YYYY-MM-DD：$key" }
            val date = LocalDate.parse(key)
            val obj = value as? JsonObject ?: error("$key 必须是对象")
            val raw = obj["entries"] ?: error("$key 缺少 entries")
            val values = if (raw is JsonArray) raw.toList() else listOf(raw)
            val punches = values.map { entry ->
                val primitive = entry as? JsonPrimitive ?: error("$key 的打卡格式无效")
                val instant = if (primitive.isString) {
                    require(isoPattern.matches(primitive.content)) { "$key 的时间必须包含秒和时区" }
                    OffsetDateTime.parse(primitive.content).toInstant()
                } else {
                    val millis = primitive.longOrNull ?: error("时间戳必须是整数毫秒")
                    require(millis in -9007199254740991L..9007199254740991L) { "时间戳超出安全范围" }
                    // A seconds timestamp must never silently turn into a date in 1970.
                    val value = Instant.ofEpochMilli(millis)
                    if (value.atZone(WorkZone).toLocalDate() != date) {
                        val secondsDay = runCatching { Instant.ofEpochSecond(millis).atZone(WorkZone).toLocalDate() }.getOrNull()
                        require(secondsDay != date) { "时间戳须使用毫秒，不能使用秒" }
                    }
                    value
                }
                require(instant.atZone(WorkZone).toLocalDate() == date) { "$key 包含跨日打卡（按北京时间）" }
                instant
            }.sorted()
            DayRecord(date, punches, JsonObject(obj.filterKeys { it != "entries" }))
        }.sortedBy { it.date }
    }

    fun encode(records: List<DayRecord>): String = BackupJson.encodeToString(JsonObject.serializer(), JsonObject(
        records.sortedBy { it.date }.associate { record ->
            record.date.toString() to JsonObject(record.extra + ("entries" to JsonArray(record.punches.sorted().map {
                val local = it.atZone(WorkZone)
                // Historical Shanghai offsets include seconds, which the shared ISO format cannot represent.
                if (local.offset.totalSeconds % 60 != 0) JsonPrimitive(it.toEpochMilli())
                else JsonPrimitive(exportTime.format(local))
            })))
        }
    ))

    /** Idempotent merge, preserving duplicate counts already present inside either file. */
    fun merge(existing: List<DayRecord>, incoming: List<DayRecord>): List<DayRecord> {
        val existingByDate = existing.associateBy { it.date }
        val incomingByDate = incoming.associateBy { it.date }
        return (existingByDate.keys + incomingByDate.keys).sorted().map { day ->
            val a = existingByDate[day]
            val b = incomingByDate[day]
            if (a == null) return@map b!!
            if (b == null) return@map a
            val countsA = a.punches.groupingBy { it }.eachCount()
            val countsB = b.punches.groupingBy { it }.eachCount()
            val punches = (countsA.keys + countsB.keys).flatMap { instant ->
                List(maxOf(countsA[instant] ?: 0, countsB[instant] ?: 0)) { instant }
            }.sorted()
            DayRecord(day, punches, JsonObject(b.extra + a.extra))
        }
    }
}

object Overtime {
    fun summarize(record: DayRecord, holidays: Map<LocalDate, Holiday>, cutoff: LocalTime = LocalTime.of(17, 30)): DaySummary {
        val holiday = holidays[record.date]
        val off = holiday?.off ?: (record.date.dayOfWeek.value >= 6)
        val label = holiday?.name ?: if (off) "周末" else "工作日"
        val last = record.punches.maxOrNull()
        val seconds = if (last == null) 0 else if (off) {
            if (record.punches.size < 2) 0 else Duration.between(record.punches.minOrNull(), last).seconds
        } else {
            Duration.between(record.date.atTime(cutoff).atZone(WorkZone).toInstant(), last).seconds.coerceAtLeast(0)
        }
        return DaySummary(record, seconds, label, off)
    }

    fun month(records: List<DayRecord>, month: YearMonth, holidays: Map<LocalDate, Holiday>, cutoff: LocalTime) =
        records.filter { YearMonth.from(it.date) == month && it.punches.isNotEmpty() }
            .map { summarize(it, holidays, cutoff) }.sortedByDescending { it.record.date }

    fun duration(seconds: Long): String = "${seconds / 3600}小时${seconds % 3600 / 60}分"
    fun hours(seconds: Long): String = "%.1f".format(java.util.Locale.ROOT, seconds / 3600.0)

    fun csv(days: List<DaySummary>): String = "\uFEFF日期,类型,打卡次数,首次打卡,末次打卡,加班秒数,加班小时\r\n" + days.sortedBy { it.record.date }.joinToString("\r\n") {
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        listOf(it.record.date.toString(), it.label, it.record.punches.size.toString(),
            it.record.punches.minOrNull()?.atZone(WorkZone)?.toLocalTime()?.toString().orEmpty(),
            it.record.punches.maxOrNull()?.atZone(WorkZone)?.toLocalTime()?.toString().orEmpty(),
            it.seconds.toString(), "%.4f".format(java.util.Locale.ROOT, it.seconds / 3600.0)).joinToString(",", transform = ::cell)
    }
}

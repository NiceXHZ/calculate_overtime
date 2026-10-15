package com.liu.overtime

import com.liu.overtime.domain.*
import java.time.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OvertimeTest {
    private fun record(date: String, vararg times: String) = DayRecord(LocalDate.parse(date), times.map { LocalDate.parse(date).atTime(LocalTime.parse(it)).atZone(WorkZone).toInstant() })
    private fun seconds(record: DayRecord, holidays: Map<LocalDate, Holiday> = emptyMap()) = Overtime.summarize(record, holidays).seconds

    @Test fun weekdayUsesLatestPunchAfterCutoff() {
        assertEquals(5400, seconds(record("2026-09-10", "19:00", "09:00", "18:00")))
        assertEquals(0, seconds(record("2026-09-10", "17:29")))
        assertEquals(0, seconds(record("2026-09-10", "17:30")))
        assertEquals(1, seconds(record("2026-09-10", "17:30:01")))
    }
    @Test fun weekendNeedsTwoPunchesAndDoesNotDeductLunch() {
        assertEquals(0, seconds(record("2026-09-12", "09:00")))
        assertEquals(32400, seconds(record("2026-09-12", "18:00", "12:00", "13:00", "09:00")))
    }
    @Test fun holidaysAndMakeupDaysOverrideWeekdays() {
        val holiday = LocalDate.parse("2026-10-01") to Holiday(true, "国庆节")
        assertEquals(32400, seconds(record("2026-10-01", "09:00", "18:00"), mapOf(holiday)))
        val makeup = LocalDate.parse("2026-10-10") to Holiday(false, "调休上班")
        assertEquals(1800, seconds(record("2026-10-10", "09:00", "18:00"), mapOf(makeup)))
    }
    @Test fun customCutoffAndMonthSelection() {
        val r = record("2026-09-10", "18:00")
        assertEquals(3600, Overtime.summarize(r, emptyMap(), LocalTime.of(17,0)).seconds)
        val records = listOf(r, record("2026-10-01", "18:00"), record("2026-09-11"))
        assertEquals(1, Overtime.month(records, YearMonth.of(2026,9), emptyMap(), LocalTime.of(17,30)).size)
    }
    @Test fun mixedInputsRoundTripPreservesPrecisionDuplicatesAndMetadata() {
        val json = """{"2026-09-10":{"note":"测试","nested":{"a":1},"entries":["2026-09-10T19:00:00.123+08:00",1789002000000,"2026-09-10T19:00:00.123+08:00"]},"2026-09-11":{"entries":[]}}"""
        val decoded = RecordCodec.decode(json)
        assertEquals(decoded, RecordCodec.decode(RecordCodec.encode(decoded)))
        assertEquals(3, decoded.first().punches.size)
        assertEquals("测试", decoded.first().extra["note"]!!.jsonPrimitive.content)
        assertEquals(2, decoded.size)
    }
    @Test fun scalarAndUtcAccepted() {
        val data = RecordCodec.decode("""{"2026-09-10":{"entries":"2026-09-10T01:00:00Z"}}""")
        assertEquals(LocalTime.of(9,0), data.single().punches.single().atZone(WorkZone).toLocalTime())
    }
    @Test fun mergeIsIdempotentAndPreservesDuplicateMultiplicity() {
        val a = record("2026-09-10", "09:00", "09:00")
        val b = record("2026-09-10", "09:00", "18:00")
        val merged = RecordCodec.merge(listOf(a), listOf(b))
        assertEquals(3, merged.single().punches.size)
        assertEquals(merged, RecordCodec.merge(merged, listOf(b)))
        assertEquals(merged, RecordCodec.merge(merged, merged))
    }
    @Test fun rejectsMalformedDatesSecondsAndCrossDay() {
        listOf("[]", "null", """{"2026-02-30":{"entries":[]}}""", """{"2026-09-10":{}}""",
            """{"2026-09-10":{"entries":1789002000}}""",
            """{"2026-09-10":{"entries":"2026-09-11T09:00:00+08:00"}}""",
            """{"2026-09-10":{"entries":"2026-09-10T09:00:00"}}""",
            """{"2026-09-10":{"entries":1.5}}""",
            """{"2026-09-10":{"entries":null}}""").forEach {
            assertTrue("Should reject $it", runCatching { RecordCodec.decode(it) }.isFailure)
        }
    }
    @Test fun csvEscapesLabelsAndIncludesBom() {
        val day = Overtime.summarize(record("2026-09-10", "18:00"), mapOf(LocalDate.parse("2026-09-10") to Holiday(false, "含\"引号,名称")))
        val csv = Overtime.csv(listOf(day))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"含\"\"引号,名称\""))
    }
}

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

    @Test fun bomAndEpochMillisecondsAreValidButInvalidIsoNeverNormalizes() {
        val decoded = RecordCodec.decode("\uFEFF" + """{"1970-01-01":{"entries":[0,1,999]}}""")
        assertEquals(listOf(0L, 1L, 999L), decoded.single().punches.map { it.toEpochMilli() })
        for (stamp in listOf("2026-09-10T24:00:00+08:00", "2026-09-11T12:60:00+08:00", "2026-09-11T12:00:60+08:00")) {
            assertTrue(runCatching { RecordCodec.decode("""{"2026-09-11":{"entries":"$stamp"}}""") }.isFailure)
        }
    }

    @Test fun largeMergePreservesEveryDateAndExistingMetadataWins() {
        val start = LocalDate.of(2000, 1, 1)
        val existing = (0 until 10_000).map { index ->
            val date = start.plusDays(index.toLong())
            DayRecord(date, listOf(date.atTime(9,0).atZone(WorkZone).toInstant()), buildJsonObject { put("note", "local") })
        }
        val incoming = existing.drop(5000).map { it.copy(punches = it.punches + it.punches, extra = buildJsonObject { put("note", "incoming"); put("new", true) }) }
        val begin = System.nanoTime()
        val merged = RecordCodec.merge(existing, incoming)
        println("merge 10000 dates: ${(System.nanoTime() - begin) / 1_000_000.0} ms")
        assertEquals(10_000, merged.size)
        assertEquals(15_000, merged.sumOf { it.punches.size })
        assertEquals("local", merged.last().extra["note"]!!.jsonPrimitive.content)
        assertEquals(true, merged.last().extra["new"]!!.jsonPrimitive.boolean)
        assertEquals(merged, RecordCodec.merge(merged, incoming))
    }

    @Test fun csvFirstLastRemainCorrectEvenWithAnUnsortedRecord() {
        val csv = Overtime.csv(listOf(Overtime.summarize(record("2026-09-10", "19:00", "09:00", "18:00"), emptyMap())))
        assertTrue(csv.contains("\"09:00\",\"19:00\",\"5400\""))
    }

    @Test fun historicShanghaiOffsetDoesNotLoseSecondsOnBackupRoundTrip() {
        val original = listOf(record("1900-01-01", "00:00:00", "09:00:00"))
        assertEquals(original, RecordCodec.decode(RecordCodec.encode(original)))
    }
}

package com.liu.overtime

import com.liu.overtime.data.HolidayStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HolidayStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clearCache() {
        context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("holidays-") }.forEach { it.delete() }
    }
    @Test fun rejectsWrongYearsEmptyTargetsAndInvalidTypes() {
        val store = HolidayStore(context)
        for (text in listOf(
            """{"year":2025,"days":[{"date":"2026-01-01","isOffDay":true,"name":"元旦"}]}""",
            """{"year":2026,"days":[{"date":"2025-12-31","isOffDay":true,"name":"元旦"}]}""",
            """{"year":2026,"days":[{"date":"2026-01-01","isOffDay":"true","name":"元旦"}]}""",
            """{"year":2026,"days":[]}"""
        )) assertTrue(text, runCatching { store.parse(text, 2026) }.isFailure)
    }
    @Test fun nextYearsNoticeOverridesDecemberButNeverLeaksOtherYears() {
        File(context.filesDir, "holidays-2026.json").writeText("""{"year":2026,"days":[{"date":"2026-12-31","isOffDay":false,"name":"普通工作日"}]}""")
        File(context.filesDir, "holidays-2027.json").writeText("""{"year":2027,"days":[{"date":"2026-12-31","isOffDay":true,"name":"元旦调整"},{"date":"2027-01-01","isOffDay":true,"name":"元旦"}]}""")
        val calendar = HolidayStore(context).read(2026)
        assertEquals(true, calendar.days[LocalDate.of(2026,12,31)]?.off)
        assertTrue(calendar.days.keys.all { it.year == 2026 })
    }
    @Test fun corruptCacheFallsBackToBundledCalendarAndMissingYearsAreExplicit() {
        File(context.filesDir, "holidays-2026.json").writeText("broken")
        assertTrue(HolidayStore(context).read(2026).days.isNotEmpty())
        val unavailable = HolidayStore(context).read(2035)
        assertTrue(unavailable.days.isEmpty())
        assertTrue(unavailable.message.contains("暂按"))
    }
}

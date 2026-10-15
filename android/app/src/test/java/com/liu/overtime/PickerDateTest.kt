package com.liu.overtime

import com.liu.overtime.ui.changePickerDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PickerDateTest {
    @Test fun shortMonthsAndLeapYearChangesKeepAValidDate() {
        assertEquals(LocalDate.of(2026, 2, 28), changePickerDate(LocalDate.of(2026, 1, 31), month = 2))
        assertEquals(LocalDate.of(2024, 2, 29), changePickerDate(LocalDate.of(2024, 1, 31), month = 2))
        assertEquals(LocalDate.of(2025, 2, 28), changePickerDate(LocalDate.of(2024, 2, 29), year = 2025))
        assertEquals(LocalDate.of(2026, 4, 30), changePickerDate(LocalDate.of(2026, 3, 31), month = 4))
        assertEquals(LocalDate.of(2025, 12, 31), changePickerDate(LocalDate.of(2026, 12, 31), year = 2025))
    }
}

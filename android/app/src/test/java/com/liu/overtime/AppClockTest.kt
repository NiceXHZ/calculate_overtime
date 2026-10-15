package com.liu.overtime

import com.liu.overtime.domain.WorkZone
import com.liu.overtime.ui.minuteClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AppClockTest {
    @Test fun clockStopsWithoutSubscribersAndRefreshesOnResume() = runTest {
        val start = Instant.parse("2026-12-31T15:59:30Z")
        var reads = 0
        val years = mutableListOf<Int>()
        fun now(): ZonedDateTime { reads++; return start.plusMillis(testScheduler.currentTime).atZone(WorkZone) }
        val state = minuteClock(::now) { years += it }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(0), start.atZone(WorkZone))
        runCurrent()
        assertEquals(0, reads)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf(2026, 2027), years)
        first.cancel(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, reads)
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        runCurrent()
        assertEquals(3, reads)
        assertEquals(start.plusMillis(testScheduler.currentTime), state.value.toInstant())
        second.cancel()
    }
}

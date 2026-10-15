package com.liu.overtime.ui

import com.liu.overtime.domain.WorkZone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.ZonedDateTime

/** Cold flow: restarting after backgrounding immediately re-reads the wall clock. */
internal fun minuteClock(
    now: () -> ZonedDateTime = { ZonedDateTime.now(WorkZone) },
    onYear: suspend (Int) -> Unit = {},
) = flow {
    var year: Int? = null
    while (currentCoroutineContext().isActive) {
        val current = now()
        if (year != current.year) {
            onYear(current.year)
            year = current.year
        }
        emit(current)
        delay(60_000 - Math.floorMod(current.toInstant().toEpochMilli(), 60_000L))
    }
}

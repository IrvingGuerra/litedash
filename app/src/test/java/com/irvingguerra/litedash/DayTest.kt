package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/** [Day] debe dar lo mismo que java.time (que en las pruebas sí existe). */
class DayTest {
    private fun Day.local() = LocalDate.of(year, month, day)

    @Test
    fun epochDayMatchesJavaTime() {
        var date = LocalDate.of(1899, 12, 25)
        val end = LocalDate.of(2101, 3, 5)
        while (date <= end) {
            val day = Day.ofEpochDay(date.toEpochDay())
            assertEquals(date, day.local())
            assertEquals(date.toEpochDay(), day.epochDay)
            date = date.plusDays(1)
        }
    }

    @Test
    fun monthsLikeLocalDate() {
        for (start in listOf(Day(2026, 1, 31), Day(2024, 2, 29), Day(2026, 12, 15), Day(2025, 8, 31))) {
            for (m in -30L..30L) assertEquals("$start + $m meses", start.local().plusMonths(m), start.plusMonths(m).local())
        }
    }

    @Test
    fun daysAndText() {
        assertEquals(LocalDate.of(2026, 11, 3).plusDays(400), Day(2026, 11, 3).plusDays(400).local())
        assertEquals(17L, Day(2026, 11, 3).daysUntil(Day(2026, 11, 20)))
        assertEquals(-2L, Day(2026, 11, 3).daysUntil(Day(2026, 11, 1)))
        assertEquals("2026-01-05", Day(2026, 1, 5).toString())
        assertEquals(Day(2026, 11, 20), Day.parse("2026-11-20"))
        assertThrows(IllegalArgumentException::class.java) { Day.parse("2026-02-30") }
        assertThrows(IllegalArgumentException::class.java) { Day.parse("20-1-1") }
    }
}

package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTest {
    private val today = Day(2026, 11, 9)

    @Test
    fun onTimeMovesOnePeriod() {
        assertEquals(Day(2027, 5, 1), nextDue(Day(2026, 11, 1), 6, today))
        assertEquals(Day(2027, 11, 20), nextDue(Day(2026, 11, 20), 12, today))
    }

    @Test
    fun longOverdueLandsAfterToday() {
        // Vencido hace más de un periodo: no sigue vencido tras "Hecho".
        assertEquals(Day(2027, 1, 15), nextDue(Day(2025, 1, 15), 6, today))
        // Hoy no cuenta como "después de hoy".
        assertEquals(Day(2027, 2, 9), nextDue(Day(2026, 5, 9), 3, today))
    }

    @Test
    fun keepsTheDayOfMonth() {
        // Desde el 31 de enero cada mes: no se queda en el 28 de febrero para siempre.
        assertEquals(Day(2026, 11, 30), nextDue(Day(2026, 1, 31), 1, Day(2026, 10, 31)))
        assertEquals(Day(2026, 12, 31), nextDue(Day(2026, 1, 31), 1, Day(2026, 11, 30)))
    }
}

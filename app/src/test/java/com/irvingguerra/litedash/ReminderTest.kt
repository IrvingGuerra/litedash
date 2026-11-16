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

    @Test
    fun casillasAntesDelDiaUno() {
        // Noviembre de 2026 empieza en domingo: con semana de domingo (México) ninguna, de lunes (España) seis.
        assertEquals(0, leadingBlanks(Day(2026, 11, 1), java.util.Calendar.SUNDAY))
        assertEquals(6, leadingBlanks(Day(2026, 11, 1), java.util.Calendar.MONDAY))
        // Enero de 1970 empezó en jueves.
        assertEquals(4, leadingBlanks(Day(1970, 1, 1), java.util.Calendar.SUNDAY))
        assertEquals(3, leadingBlanks(Day(1970, 1, 1), java.util.Calendar.MONDAY))
        // Diciembre de 2026 empieza en martes.
        assertEquals(2, leadingBlanks(Day(2026, 12, 1), java.util.Calendar.SUNDAY))
        assertEquals(1, leadingBlanks(Day(2026, 12, 1), java.util.Calendar.MONDAY))
    }
}

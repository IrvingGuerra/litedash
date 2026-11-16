package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

/** Amanecer y atardecer contra la calculadora de la NOAA. */
class DaylightTest {
    private fun day(y: Int, m: Int, d: Int, zone: String) =
        GregorianCalendar(TimeZone.getTimeZone(zone)).apply { clear(); set(y, m - 1, d, 12, 0) }

    @Test
    fun mexicoCityAtSummerSolstice() {
        // NOAA: 05:59 y 19:17 (UTC−6: sin horario de verano desde 2022).
        val (rise, set) = Daylight.sunTimes(day(2026, 6, 21, "America/Mexico_City"), 19.43, -99.13)
        assertEquals(5 * 60 + 59.0, rise, 3.0)
        assertEquals(19 * 60 + 17.0, set, 3.0)
    }

    @Test
    fun mexicoCityAtWinterSolstice() {
        // NOAA: 07:08 y 18:05.
        val (rise, set) = Daylight.sunTimes(day(2026, 12, 21, "America/Mexico_City"), 19.43, -99.13)
        assertEquals(7 * 60 + 8.0, rise, 3.0)
        assertEquals(18 * 60 + 5.0, set, 3.0)
    }
}

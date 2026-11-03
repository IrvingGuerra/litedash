package com.irvingguerra.litedash

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Un día del calendario, sin hora (recordatorios y estadísticas). java.time no existe en
 * Android 6 y traerlo con desugaring pesa ~200 KB: esto es lo poco que se usa, con la misma
 * aritmética que LocalDate (las pruebas lo comparan contra java.time).
 */
data class Day(val year: Int, val month: Int, val day: Int) : Comparable<Day> {
    init {
        require(month in 1..12 && day in 1..lengthOf(year, month)) { "Fecha inválida: $year-$month-$day" }
    }

    /** Días desde el 1970-01-01. */
    val epochDay: Long get() = epochDayOf(year, month, day)

    fun plusDays(days: Long) = ofEpochDay(epochDay + days)

    /** Como LocalDate: el 31 de enero más un mes es el 28 (o 29) de febrero. */
    fun plusMonths(months: Long): Day {
        val total = year * 12L + (month - 1) + months
        val y = total.floorDiv(12L).toInt()
        val m = total.mod(12L).toInt() + 1
        return Day(y, m, minOf(day, lengthOf(y, m)))
    }

    fun isAfter(other: Day) = this > other

    /** Días que faltan hasta [other] (negativo si ya pasó). */
    fun daysUntil(other: Day) = other.epochDay - epochDay

    /** La medianoche de este día en la zona del equipo, para DateFormat. */
    fun toDate(): Date = GregorianCalendar(year, month - 1, day).time

    override fun compareTo(other: Day) = epochDay.compareTo(other.epochDay)

    /** "2026-11-03", como LocalDate.toString(). */
    override fun toString() = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

    companion object {
        fun today(): Day {
            val now = GregorianCalendar()
            return Day(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        }

        /** Lee "aaaa-mm-dd"; falla si no es una fecha válida (como LocalDate.parse). */
        fun parse(text: String): Day {
            val parts = text.split('-')
            require(parts.size == 3 && parts[0].length == 4 && parts[1].length == 2 && parts[2].length == 2) { "Fecha inválida: $text" }
            return Day(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

        // Calendario gregoriano proléptico (el de java.time), por el algoritmo de días civiles.
        fun ofEpochDay(epochDay: Long): Day {
            val z = epochDay + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return Day(y, m, d)
        }

        private fun epochDayOf(year: Int, month: Int, day: Int): Long {
            val y = year.toLong() - if (month <= 2) 1 else 0
            val era = y.floorDiv(400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        private fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        private fun lengthOf(year: Int, month: Int) = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

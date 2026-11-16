package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class WeatherTest {
    private val hour = 3_600_000L
    private val fetchedAt = 1_000_000_000_000L
    private val forecast = Forecast(
        temperature = 20.4, code = 0, day = true, fetchedAt = fetchedAt,
        days = listOf(
            ForecastDay("2026-11-16", 3, 25.0, 14.6),
            ForecastDay("2026-11-17", 61, 22.0, 13.0),
            ForecastDay("2026-11-18", 0, 24.0, 12.0),
            ForecastDay("2026-11-19", 95, 21.0, 11.0),
        ),
    )

    @Test
    fun recienBajadoSeVeComoAhora() {
        val w = forecast.at(fetchedAt + hour, Day(2026, 11, 16), fahrenheit = false)!!
        assertEquals(20, w.temperature)
        assertEquals(0, w.code)
        assertEquals(WeatherDay("2026-11-16", 3, 25, 15), w.today)
        assertEquals(listOf("2026-11-17", "2026-11-18", "2026-11-19"), w.next.map { it.date })
    }

    @Test
    fun enFahrenheitSeRedondeaAlFinal() {
        // 20.4 °C = 68.7 °F → 69 (redondeando primero en °C saldría 68).
        assertEquals(69, forecast.at(fetchedAt, Day(2026, 11, 16), fahrenheit = true)!!.temperature)
    }

    @Test
    fun laTemperaturaViejaNoSeVeComoActual() {
        // Sin red, horas después: solo vale lo pronosticado para hoy (con su cielo).
        val w = forecast.at(fetchedAt + CURRENT_MAX_AGE_MS, Day(2026, 11, 16), fahrenheit = false)!!
        assertNull(w.temperature)
        assertEquals(3, w.code)
        assertEquals(25, w.today!!.max)
    }

    @Test
    fun dosDiasDespuesHoyEsOtroDia() {
        // Lo guardado de hace dos días: "hoy" es el 18, no el primero de la lista, y siguen los de después.
        val w = forecast.at(fetchedAt + 48 * hour, Day(2026, 11, 18), fahrenheit = false)!!
        assertNull(w.temperature)
        assertEquals("2026-11-18", w.today!!.date)
        assertEquals(listOf("2026-11-19"), w.next.map { it.date })
    }

    @Test
    fun sinNadaVigenteNoHayClima() {
        assertNull(forecast.at(fetchedAt + 5 * 24 * hour, Day(2026, 11, 21), fahrenheit = false))
    }

    @Test
    fun cambiaAlVencerOAMedianoche() {
        val now = GregorianCalendar(2026, Calendar.NOVEMBER, 16, 23, 0).timeInMillis
        val midnight = GregorianCalendar(2026, Calendar.NOVEMBER, 17, 0, 0).timeInMillis
        assertEquals(midnight, nextMidnight(now))
        // Recién bajado a las 23:00: lo primero que cambia es el día.
        assertEquals(midnight, forecast.copy(fetchedAt = now).nextChange(now))
        // Bajado a las 20:30: la temperatura vence a las 23:30, antes que el día.
        val early = now - 150 * 60_000L
        assertEquals(early + CURRENT_MAX_AGE_MS, forecast.copy(fetchedAt = early).nextChange(now))
        // Ya vencido: solo la medianoche.
        assertEquals(midnight, forecast.copy(fetchedAt = now - 5 * hour).nextChange(now))
    }

    @Test
    fun cielos() {
        assertEquals(Sky.CLEAR, skyOf(0))
        assertEquals(Sky.PARTLY, skyOf(2))
        assertEquals(Sky.FOG, skyOf(48))
        assertEquals(Sky.RAIN, skyOf(61))
        assertEquals(Sky.RAIN, skyOf(81))
        assertEquals(Sky.SNOW, skyOf(86))
        assertEquals(Sky.STORM, skyOf(95))
        assertEquals(Sky.CLOUDY, skyOf(999))
    }
}

package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

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

class WeatherHoursTest {
    private val hour = 3_600_000L
    private val start = 1_000_000_000_000L - 1_000_000_000_000L % hour
    private val forecast = Forecast(
        temperature = 20.4, code = 0, day = true, fetchedAt = start + 30 * 60_000L,
        days = listOf(ForecastDay("2026-11-16", 3, 25.0, 14.6)),
        hours = ForecastHours(start, (0 until 48).map { ForecastHour(10.0 + it, if (it % 2 == 0) 1 else 61, it < 18) }),
        lat = 19.43, lon = -99.13,
    )

    @Test
    fun sinRedTomaLaTemperaturaDeSuHora() {
        // Cinco horas y media después ya no vale la de ahora: sale la de la hora 5.
        val w = forecast.at(start + 5 * hour + 30 * 60_000L, Day(2026, 11, 16), fahrenheit = false)!!
        assertEquals(15, w.temperature)
        assertEquals(61, w.code)
        assertEquals(WeatherDay("2026-11-16", 3, 25, 15), w.today)
        // De noche y en °F, igual desde la de esa hora: 30 °C = 86 °F.
        val night = forecast.at(start + 20 * hour, Day(2026, 11, 16), fahrenheit = true)!!
        assertEquals(86, night.temperature)
        assertFalse(night.day)
    }

    @Test
    fun recienBajadoMandaLaDeAhora() {
        assertEquals(20, forecast.at(start + hour, Day(2026, 11, 16), fahrenheit = false)!!.temperature)
    }

    @Test
    fun fueraDeLasHorasGuardadasNoHayTemperatura() {
        assertNull(forecast.hourAt(start - 1))
        assertEquals(57.0, forecast.hourAt(start + 48 * hour - 1)!!.temperature, 0.0)
        assertNull(forecast.hourAt(start + 48 * hour))
        // Ni la hora ni el día de hoy: nada.
        assertNull(forecast.at(start + 48 * hour, Day(2026, 11, 18), fahrenheit = false))
    }

    @Test
    fun cambiaAlCambiarDeHora() {
        val now = start + 5 * hour + 10 * 60_000L
        assertEquals(minOf(start + 6 * hour, nextMidnight(now)), forecast.nextChange(now))
    }

    @Test
    fun soloCuentaLoPedidoCerca() {
        assertTrue(forecast.isNear(19.43, -99.13))
        assertTrue(forecast.isNear(19.70, -99.13)) // ~30 km al norte
        assertFalse(forecast.isNear(19.93, -99.13)) // ~56 km
        assertFalse(forecast.isNear(20.67, -103.35)) // Guadalajara
        // Guardado por una versión que no anotaba el lugar: se usa como antes.
        assertTrue(forecast.copy(lat = null, lon = null).isNear(20.67, -103.35))
    }

    @Test
    fun horaLocalDelLugarAMilisegundos() {
        // 2026-11-22 00:00 en CDMX (UTC−6) = 06:00 UTC.
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, 22, 6, 0) }.timeInMillis
        assertEquals(utc, localTimeToMillis("2026-11-22T00:00", -6 * 3600))
    }

    private fun response(hourTemps: String, time0: String = "2026-11-16T00:00") = org.json.JSONObject(
        """{"utc_offset_seconds":-21600,
        "current":{"temperature_2m":20.4,"weather_code":0,"is_day":1},
        "daily":{"time":["2026-11-16"],"weather_code":[3],"temperature_2m_max":[25.0],"temperature_2m_min":[14.6]},
        "hourly":{"time":["$time0","2026-11-16T01:00","2026-11-16T02:00"],"temperature_2m":$hourTemps,
        "weather_code":[0,1,2],"is_day":[0,0,0]}}""",
    )

    @Test
    fun unaHoraSinDatoCortaLasHorasPeroNoElClima() {
        val f = parseForecast(response("[15.0,null,14.0]"), 20.0, -100.0, 1_000L)
        assertEquals(20.4, f.temperature, 0.0)
        assertEquals(1, f.hours!!.list.size)
        // Sin ninguna hora con dato: sin horas, pero el clima de ahora sí llega.
        assertNull(parseForecast(response("[null,null,null]"), 20.0, -100.0, 1_000L).hours)
    }

    @Test(expected = org.json.JSONException::class)
    fun unFormatoRaroEsRespuestaMalaNoCierre() {
        // "2026-11" es más corto de lo esperado: antes salía StringIndexOutOfBoundsException y cerraba la app.
        parseForecast(response("[15.0,16.0,14.0]", time0 = "2026-11"), 20.0, -100.0, 1_000L)
    }
}

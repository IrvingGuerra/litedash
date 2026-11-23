package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GaugeSpecTest {
    private val metric = UnitChoice(miles = false, fahrenheit = false)
    private val imperial = UnitChoice(miles = true, fahrenheit = true)
    private fun spec(signal: String) = gaugeOf(signal)!!

    @Test
    fun lasRevolucionesVanEnMiles() {
        val rpm = spec(Signal.RPM)
        assertEquals((0..8).map { "$it" }, (0..rpm.ticks).map { rpm.label(it, metric, Locale.US) })
        assertEquals(0.8125f, rpm.redFrom, 0.0001f)
    }

    @Test
    fun elTurboEmpiezaEnNegativo() {
        val boost = spec(Signal.BOOST)
        // −15…25 psi, de 5 en 5 (sin "-0").
        assertEquals(listOf("-15", "-10", "-5", "0", "5", "10", "15", "20", "25"), (0..boost.ticks).map { boost.label(it, metric, Locale.US) })
        assertEquals(0f, boost.fraction(-15f / 0.145f), 0.0001f)
        assertEquals(1f, boost.fraction(25f / 0.145f), 0.0001f)
        // Sin zona roja propia: el último 20 %.
        assertEquals(0.8f, boost.redFrom, 0f)
    }

    @Test
    fun laTemperaturaEnFahrenheit() {
        val coolant = spec(Signal.COOLANT)
        assertEquals("104", coolant.label(0, imperial, Locale.US))
        assertEquals("266", coolant.label(coolant.ticks, imperial, Locale.US))
        assertEquals("40", coolant.label(0, metric, Locale.US))
    }
}

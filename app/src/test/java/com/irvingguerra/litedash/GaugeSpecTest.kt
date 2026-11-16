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
        // -100…170 kPa en psi: la mitad (35 kPa) son 5 psi y el final, 25.
        assertEquals("5", boost.label(4, metric, Locale.US))
        assertEquals("25", boost.label(8, metric, Locale.US))
        assertEquals(0f, boost.fraction(-100f), 0f)
        assertEquals(1f, boost.fraction(170f), 0f)
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

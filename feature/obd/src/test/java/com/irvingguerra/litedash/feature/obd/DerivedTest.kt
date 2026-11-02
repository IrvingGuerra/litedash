package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DerivedTest {
    @Test
    fun consumoConMafYVelocidad() {
        // 5 g/s a 100 km/h ≈ 1.66 L/h → ~60 km/l (en carretera, motor chico).
        val kml = consumption(5f, 100f)!!
        assertEquals(60.2f, kml, 0.5f)
        // Más aire a la misma velocidad = más gasolina = menos km/l.
        assert(consumption(15f, 100f)!! < kml)
    }

    @Test
    fun consumoParadoNoSeCalcula() {
        assertNull(consumption(3f, 0f))
        assertNull(consumption(0f, 80f))
    }

    @Test
    fun turboUsaLaPresionDelAireSiLaHay() {
        val boost = DerivedSignals.first { it.signal == Signal.BOOST }
        assertEquals(60f, boost.compute(mapOf(Signal.MAP to 138f, Signal.BARO to 78f))!!, 0.01f)
        // Sin presión del aire se usa la del nivel del mar.
        assertEquals(36.7f, boost.compute(mapOf(Signal.MAP to 138f))!!, 0.01f)
        assertNull(boost.compute(mapOf(Signal.BARO to 78f)))
    }

    @Test
    fun alertas() {
        assertEquals(EngineAlert.HOT, engineAlert(112f, 14f, 800f))
        // Sin rpm solo cuenta el calor.
        assertNull(engineAlert(95f, 11f, null))
        assertEquals(EngineAlert.NOT_CHARGING, engineAlert(90f, 12.2f, 850f))
        assertEquals(EngineAlert.OVERCHARGING, engineAlert(90f, 15.8f, 2000f))
        assertEquals(EngineAlert.LOW_BATTERY, engineAlert(null, 11.5f, 0f))
        assertNull(engineAlert(90f, 14.1f, 800f))
        assertNull(engineAlert(null, 12.5f, 0f))
    }
}

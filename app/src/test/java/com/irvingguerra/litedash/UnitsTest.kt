package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test
    fun automaticoSigueLaReglaDeSiempre() {
        assertEquals(UnitChoice(miles = false, fahrenheit = false), resolveUnits(SpeedUnits.AUTO, TempUnits.AUTO, autoImperial = false))
        assertEquals(UnitChoice(miles = true, fahrenheit = true), resolveUnits(SpeedUnits.AUTO, TempUnits.AUTO, autoImperial = true))
    }

    @Test
    fun loElegidoGanaPorSeparado() {
        assertEquals(UnitChoice(miles = true, fahrenheit = false), resolveUnits(SpeedUnits.IMPERIAL, TempUnits.CELSIUS, autoImperial = false))
        assertEquals(UnitChoice(miles = false, fahrenheit = true), resolveUnits(SpeedUnits.METRIC, TempUnits.FAHRENHEIT, autoImperial = true))
        assertEquals(UnitChoice(miles = false, fahrenheit = true), resolveUnits(SpeedUnits.METRIC, TempUnits.AUTO, autoImperial = true))
    }

    @Test
    fun guardadoDesconocidoEsAutomatico() {
        assertEquals(SpeedUnits.AUTO, SpeedUnits.fromStored(null))
        assertEquals(TempUnits.AUTO, TempUnits.fromStored("xyz"))
        assertEquals(TempUnits.FAHRENHEIT, TempUnits.fromStored("fahrenheit"))
    }

    @Test
    fun climaSeConvierte() {
        val w = Weather(20, 0, true, listOf(WeatherDay("2026-11-03", 0, 30, 10)), 0L, fahrenheit = false)
        val f = w.inUnits(true)
        assertEquals(68, f.temperature)
        assertEquals(86, f.days[0].max)
        assertEquals(50, f.days[0].min)
        assertEquals(w, f.inUnits(false))
    }
}

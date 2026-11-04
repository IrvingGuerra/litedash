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
    fun climaSeConvierteDesdeLosDecimales() {
        val forecast = Forecast(20.4, 0, true, listOf(ForecastDay("2026-11-03", 0, 30.0, 9.6)), 0L)
        val f = forecast.inUnits(true)
        // 20.4 °C = 68.7 °F → 69 (con el °C ya redondeado salía 68).
        assertEquals(69, f.temperature)
        assertEquals(86, f.days[0].max)
        assertEquals(49, f.days[0].min)
        val c = forecast.inUnits(false)
        assertEquals(20, c.temperature)
        assertEquals(10, c.days[0].min)
    }
}

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
    fun radiosEnMillasRedondeados() {
        assertEquals(listOf(6, 15, 30, 60, 125), listOf(10, 25, 50, 100, 200).map(::roundMiles))
        assertEquals(12, roundMiles(20))
        assertEquals(20, roundMiles(32))
    }
}

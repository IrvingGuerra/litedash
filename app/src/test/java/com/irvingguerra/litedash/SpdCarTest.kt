package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cómo se leen los datos de una pantalla SPD y qué se descarta. */
class SpdCarTest {
    @Test
    fun speedKeepsZeroAndDropsNonsense() {
        assertEquals(0f, spdSpeed(0f))
        assertEquals(6.516f, spdSpeed(6.516f))
        assertNull(spdSpeed(-1f))
        assertNull(spdSpeed(401f))
        assertNull(spdSpeed(Float.NaN))
    }

    @Test
    fun fuelIsPercentOfTheTank() {
        // 14 L de un tanque de 40 L (lo visto en el MINI).
        assertEquals(35f, spdFuel(14, 40)!!, 0.001f)
        assertEquals(100f, spdFuel(45, 40))
        // Sin capacidad o sin litros no se adivina.
        assertNull(spdFuel(14, null))
        assertNull(spdFuel(14, 0))
        assertNull(spdFuel(0, 40))
    }

    @Test
    fun voltageComesInTenths() {
        assertEquals(12.3f, spdVolts(123)!!, 0.001f)
        assertNull(spdVolts(0))
        assertNull(spdVolts(321))
    }

    @Test
    fun givesOnlyCheckedSignals() {
        assertTrue(SpdCar.gives(Signal.SPEED))
        assertTrue(SpdCar.gives(Signal.FUEL))
        assertTrue(SpdCar.gives(Signal.VOLTAGE))
        assertFalse(SpdCar.gives(Signal.RPM))
    }
}

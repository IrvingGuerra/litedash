package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.tpms.PressureUnit
import com.irvingguerra.litedash.feature.tpms.TireSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TiresPartsTest {
    // ---- Pastillas bajo el auto ----

    @Test
    fun pillsApartStayOnTheirWheel() =
        assertArrayEquals(intArrayOf(50, 250), spreadPills(floatArrayOf(100f, 300f), intArrayOf(100, 100), 600, 4))

    @Test
    fun pillsThatCollideMoveRight() =
        assertArrayEquals(intArrayOf(50, 154), spreadPills(floatArrayOf(100f, 120f), intArrayOf(100, 100), 600, 4))

    @Test
    fun pillsNeverLeaveOnTheRight() =
        assertArrayEquals(intArrayOf(396, 500), spreadPills(floatArrayOf(560f, 580f), intArrayOf(100, 100), 600, 4))

    @Test
    fun firstPillNeverStartsLeftOfZero() =
        assertArrayEquals(intArrayOf(0, 104), spreadPills(floatArrayOf(10f, 20f), intArrayOf(100, 100), 600, 4))

    // ---- La muestra más cercana (gráfica de 24 h) ----

    private val samples = listOf(TireSample(1_000, 200f), TireSample(2_000, 210f), TireSample(5_000, 220f))

    @Test
    fun nearestPicksTheClosest() {
        assertEquals(2_000L, samples.nearest(2_400)?.atMillis)
        assertEquals(5_000L, samples.nearest(4_000)?.atMillis)
    }

    @Test
    fun nearestOutsideTakesTheEdges() {
        assertEquals(1_000L, samples.nearest(0)?.atMillis)
        assertEquals(5_000L, samples.nearest(9_000)?.atMillis)
    }

    @Test
    fun nearestTieTakesTheOlder() = assertEquals(1_000L, samples.nearest(1_500)?.atMillis)

    @Test
    fun nearestOfNothingIsNull() = assertNull(emptyList<TireSample>().nearest(10))

    // ---- Calibrar ----

    @Test
    fun stepMovesOneShownUnit() {
        assertEquals(1f, PressureUnit.PSI.fromKpa(stepKpa(0f, PressureUnit.PSI, 1, -35f..35f)), 1e-3f)
        assertEquals(-0.1f, PressureUnit.BAR.fromKpa(stepKpa(0f, PressureUnit.BAR, -1, -35f..35f)), 1e-3f)
        assertEquals(5f, stepKpa(2f, PressureUnit.KPA, 1, -35f..35f), 1e-3f)
    }

    @Test
    fun stepStaysInRange() = assertEquals(35f, stepKpa(35f, PressureUnit.KPA, 1, -35f..35f), 1e-3f)

    @Test
    fun stepBackToZeroIsExactZero() = assertEquals(0f, stepKpa(PressureUnit.PSI.toKpa(1f), PressureUnit.PSI, -1, -35f..35f), 0f)

    @Test
    fun offsetHasItsSign() {
        assertEquals("+5 kPa", offsetText(5f, PressureUnit.KPA))
        assertEquals("−5 kPa", offsetText(-5f, PressureUnit.KPA))
        assertEquals("−0.1 bar", offsetText(-10f, PressureUnit.BAR))
    }

    @Test
    fun offsetZeroHasNoSign() {
        assertEquals("0 kPa", offsetText(0f, PressureUnit.KPA))
        // Menos de medio psi se ve como 0: sin signo.
        assertEquals("0 psi", offsetText(1f, PressureUnit.PSI))
    }

    // ---- Señal de un sensor ----

    @Test
    fun signalInWords() {
        assertEquals(R.string.tires_signal_strong, signalText(-60))
        assertEquals(R.string.tires_signal_medium, signalText(-80))
        assertEquals(R.string.tires_signal_weak, signalText(-95))
    }
}

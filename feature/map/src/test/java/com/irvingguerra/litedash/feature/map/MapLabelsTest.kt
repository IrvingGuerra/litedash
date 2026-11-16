package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLabelsTest {
    private fun line(vararg xy: Float) = Shape(xy, intArrayOf(0))

    @Test
    fun longestStraightSkipsZeroLengthAndStopsAtBends() {
        // Un pedazo de largo cero al inicio, un tramo recto largo y luego una vuelta de 90°.
        val shape = line(0f, 0f, 0f, 0f, 100f, 0f, 200f, 2f, 200f, 50f)
        assertEquals(1 to 3, longestStraight(shape))
        assertNull(longestStraight(line(5f, 5f)))
    }

    @Test
    fun labelsDoNotOverlapOrRepeatClose() {
        val run = { name: String, y: Double -> LabelRun(name, RoadTier.PRIMARY, 0.0, y, 10.0, y, 60f) }
        val runs = listOf(run("Reforma", 1.0), run("Reforma", 1.02), run("Juárez", 1.01), run("Insurgentes", 3.0))
        // Una tesela = 100 px; sin girar ni inclinar.
        val layout = LabelLayout(10)
        val screen = ScreenPoint { x, y, out ->
            out[0] = (x * 100).toFloat()
            out[1] = (y * 100).toFloat()
            out[2] = 1f
            true
        }
        layout.place(runs, 1000f, 600f, 0f, 14f, 1f, screen)
        // La segunda Reforma está pegada a la primera y Juárez se le encima: solo quedan dos.
        assertEquals(listOf("Reforma", "Insurgentes"), layout.placed.map { it.name })
        assertTrue(layout.placed.all { it.degrees in -90f..90f })
        // Otra vez, ya sin Insurgentes: no queda nada de la vez anterior.
        layout.place(runs.take(3), 1000f, 600f, 0f, 14f, 1f, screen)
        assertEquals(listOf("Reforma"), layout.placed.map { it.name })
        // A lo más los que caben en el acomodo.
        val many = (0 until 20).map { run("Calle $it", it * 0.3) }
        layout.place(many, 1000f, 6000f, 0f, 14f, 1f, screen)
        assertEquals(10, layout.placed.size)
    }
}

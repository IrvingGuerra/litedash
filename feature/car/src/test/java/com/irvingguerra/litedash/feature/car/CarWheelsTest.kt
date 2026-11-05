package com.irvingguerra.litedash.feature.car

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWheelsTest {
    // Dos cuadros; en el 1 la delantera izquierda pisa en (0.25, 0.5) y se ve.
    private val json = """{"version":2,"frames":2,"aspect":2.0,
        "fl":[0.1,0.9,0, 0.25,0.5,1],"fr":[0.2,0.9,1, 0.3,0.6,0],
        "rl":[0.8,0.8,0, 0.7,0.7,1],"rr":[0.9,0.8,1, 0.6,0.8,0]}"""

    @Test
    fun spotsInWideBox() {
        // Caja más ancha que el auto (2:1 en 400×100): el auto mide 200×100 y va centrado (x desde 100).
        val s = CarWheels.parse(json, 1)!!.spots(1, Size(400f, 100f))
        assertEquals(Offset(150f, 50f), s.points[0])
        assertTrue(s.visible[0])
        assertFalse(s.visible[1])
    }

    @Test
    fun spotsInTallBox() {
        // Caja más alta: el auto mide 200×100 y va centrado en vertical (y desde 50).
        val s = CarWheels.parse(json, 1)!!.spots(1, Size(200f, 200f))
        assertEquals(Offset(50f, 100f), s.points[0])
    }

    @Test
    fun frameWrapsAround() {
        val w = CarWheels.parse(json, 1)!!
        assertEquals(w.spots(0, Size(200f, 100f)).points, w.spots(2, Size(200f, 100f)).points)
    }

    @Test
    fun brokenFileIsNull() {
        assertNull(CarWheels.parse("""{"version":2,"frames":2,"aspect":2.0,"fl":[0.1]}""", 1))
        assertNull(CarWheels.parse("no es json", 1))
        // De otro giro (el paquete trae 24 cuadros y esto es para 1): no sirve.
        assertNull(CarWheels.parse(json, 24))
        // Uno de la versión anterior se vuelve a bajar.
        assertNull(CarWheels.parse(json.replace("\"version\":2,", ""), 1))
    }
}

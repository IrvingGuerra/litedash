package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeLookTest {
    @Test
    fun losAcomodosDeAntesSeLeenIgual() {
        // Hasta la 1.4.38 un medidor guardaba solo su señal y el velocímetro nada.
        assertEquals(GaugeLook(), GaugeLook.parse("rpm"))
        assertEquals("rpm", signalOf("rpm"))
        assertEquals(GaugeLook(), GaugeLook.parse(""))
        assertEquals("", signalOf(""))
    }

    @Test
    fun loDeFabricaNoSeEscribe() {
        assertEquals("rpm", GaugeLook().encode("rpm"))
        assertEquals("", GaugeLook().encode())
    }

    @Test
    fun idaYVuelta() {
        val look = GaugeLook(GaugeStyle.NEEDLE, GaugeColor.DYNAMIC, max = 240, ticks = false, numbers = true, redline = true)
        val data = look.encode("coolant_temp")
        assertEquals("coolant_temp", signalOf(data))
        assertEquals(look, GaugeLook.parse(data))
        // El velocímetro no tiene señal: lo primero ya es una opción.
        val speed = GaugeLook(GaugeStyle.RING, max = 320).encode()
        assertEquals("", signalOf(speed))
        assertEquals(GaugeLook(GaugeStyle.RING, max = 320), GaugeLook.parse(speed))
    }

    @Test
    fun estilosElegantes() {
        // 1.4.41: degradado, neón y cuadros, con sus colores en degradado.
        for (style in listOf(GaugeStyle.GRADIENT, GaugeStyle.NEON, GaugeStyle.BLOCKS)) {
            for (color in listOf(GaugeColor.PURPLE, GaugeColor.CYAN)) {
                val look = GaugeLook(style, color, max = 240)
                assertEquals(look, GaugeLook.parse(look.encode()))
            }
        }
        assertEquals("style=gradient;color=purple", GaugeLook(GaugeStyle.GRADIENT, GaugeColor.PURPLE).encode())
        assertEquals(GaugeColor.CYAN, GaugeStyle.NEON.suggestedColor())
    }

    @Test
    fun marcasDelVelocimetro() {
        assertEquals(10, ticksFor(200f, miles = false))
        assertEquals(8, ticksFor(320f, miles = false))
        assertEquals(12, ticksFor(120f, miles = true))
    }
}

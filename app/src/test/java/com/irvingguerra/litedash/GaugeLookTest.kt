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
        val data = look.encode("motor_temp")
        assertEquals("motor_temp", signalOf(data))
        assertEquals(look, GaugeLook.parse(data))
        // El velocímetro no tiene señal: lo primero ya es una opción.
        val speed = GaugeLook(GaugeStyle.RING, max = 320).encode()
        assertEquals("", signalOf(speed))
        assertEquals(GaugeLook(GaugeStyle.RING, max = 320), GaugeLook.parse(speed))
    }

    @Test
    fun marcasDelVelocimetro() {
        assertEquals(10, ticksFor(200f, imperial = false))
        assertEquals(8, ticksFor(320f, imperial = false))
        assertEquals(12, ticksFor(120f, imperial = true))
    }
}

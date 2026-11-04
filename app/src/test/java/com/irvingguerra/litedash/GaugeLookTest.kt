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

    @Test
    fun elTopeSeGuardaEnKmh() {
        // 1.4.62: se guarda en km/h y en mph se ve su pareja (antes cambiaba al pasar a mph).
        val look = GaugeLook(max = 240)
        assertEquals("maxkmh=240", look.encode())
        assertEquals(240, look.speedMax(miles = false))
        assertEquals(160, look.speedMax(miles = true))
        assertEquals(240, speedMaxToStore(160, miles = true))
        assertEquals(null, speedMaxToStore(120, miles = true))
        assertEquals(200, GaugeLook().speedMax(miles = false))
        assertEquals(120, GaugeLook().speedMax(miles = true))
    }

    @Test
    fun topesViejosSeMigran() {
        // Hasta la 1.4.61 "max" iba en la unidad que se veía.
        assertEquals("style=ring;maxkmh=240", migrateSpeedMax("style=ring;max=160", miles = true))
        assertEquals("maxkmh=160", migrateSpeedMax("max=160", miles = false))
        assertEquals("", migrateSpeedMax("max=120", miles = true))
        assertEquals(null, migrateSpeedMax("maxkmh=240", miles = true))
        // Antes de migrar se lee lo más probable.
        assertEquals(320, GaugeLook.parse("max=320").max)
    }

    @Test
    fun carretera() {
        assertEquals(RoadLook(), RoadLook.parse(""))
        assertEquals("", RoadLook().encode())
        val road = RoadLook(RoadSpeed.GAUGE, GaugeLook(GaugeStyle.NEON, GaugeColor.CYAN, max = 240))
        assertEquals(road, RoadLook.parse(road.encode()))
        assertEquals(RoadSpeed.HIDDEN, RoadLook.parse(RoadLook(RoadSpeed.HIDDEN).encode()).speed)
    }
}

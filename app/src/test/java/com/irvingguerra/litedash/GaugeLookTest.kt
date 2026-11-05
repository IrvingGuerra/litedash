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
        val speed = GaugeLook(GaugeStyle.GRADIENT, max = 320).encode()
        assertEquals("", signalOf(speed))
        assertEquals(GaugeLook(GaugeStyle.GRADIENT, max = 320), GaugeLook.parse(speed))
    }

    @Test
    fun estilosElegantes() {
        // Degradado y los aros de la Esfera (neón y cuadros), con sus colores en degradado.
        for (look in listOf(GaugeLook(GaugeStyle.GRADIENT), GaugeLook(rim = GaugeRim.NEON), GaugeLook(rim = GaugeRim.BLOCKS))) {
            for (color in listOf(GaugeColor.PURPLE, GaugeColor.CYAN)) {
                val colored = look.copy(color = color, max = 240)
                assertEquals(colored, GaugeLook.parse(colored.encode()))
            }
        }
        assertEquals("style=gradient;color=purple", GaugeLook(GaugeStyle.GRADIENT, GaugeColor.PURPLE).encode())
        assertEquals("rim=neon;color=cyan", GaugeLook(color = GaugeColor.CYAN, rim = GaugeRim.NEON).encode())
        assertEquals(GaugeColor.CYAN, GaugeLook(rim = GaugeRim.NEON).suggestedColor())
        assertEquals(GaugeColor.PURPLE, GaugeLook(rim = GaugeRim.BLOCKS).suggestedColor())
        // El aro solo cuenta en la Esfera.
        assertEquals(null, GaugeLook(GaugeStyle.NEEDLE, rim = GaugeRim.NEON).suggestedColor())
    }

    @Test
    fun losEstilosQuitadosPasanALaEsfera() {
        // 1.4.77: Arco (el de fábrica) y Anillo → Esfera lisa; Neón y Cuadros → Esfera con ese aro. Nada más cambia.
        assertEquals(GaugeLook(), GaugeLook.parse("style=arc"))
        assertEquals(GaugeLook(max = 240), GaugeLook.parse("style=ring;maxkmh=240"))
        assertEquals(GaugeLook(color = GaugeColor.CYAN, rim = GaugeRim.NEON), GaugeLook.parse("style=neon;color=cyan"))
        assertEquals(GaugeLook(GaugeStyle.DIAL, GaugeColor.PURPLE, numbers = true, rim = GaugeRim.BLOCKS), GaugeLook.parse("rpm;style=blocks;color=purple;numbers=1"))
        assertEquals("rpm;rim=blocks;color=purple;numbers=1", GaugeLook.parse("rpm;style=blocks;color=purple;numbers=1").encode("rpm"))
        // La Esfera de antes ("style=dial") se lee igual y ya no se escribe.
        assertEquals(GaugeLook(), GaugeLook.parse("style=dial"))
    }

    @Test
    fun esferaYAlFrente() {
        // Esfera y "Al frente": de fábrica nada (no se escribe).
        val car = GaugeLook(GaugeStyle.DIAL, front = GaugeFront.CAR)
        assertEquals("front=car", car.encode())
        assertEquals(car, GaugeLook.parse(car.encode()))
        assertEquals(GaugeFront.NONE, GaugeLook.parse("style=dial").front)
        assertEquals(null, GaugeLook().suggestedColor())
        // La imagen se recuerda aunque se cambie a nada; "car=1" de la 1.4.74 sigue siendo el auto.
        val image = GaugeLook(front = GaugeFront.IMAGE, image = "abc123")
        assertEquals("front=image;img=abc123", image.encode())
        assertEquals(image, GaugeLook.parse(image.encode()))
        assertEquals("img=abc123", image.copy(front = GaugeFront.NONE).encode())
        assertEquals(GaugeFront.CAR, GaugeLook.parse("car=1").front)
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
        val road = RoadLook(RoadSpeed.GAUGE, GaugeLook(color = GaugeColor.CYAN, max = 240, rim = GaugeRim.NEON))
        assertEquals(road, RoadLook.parse(road.encode()))
        assertEquals(RoadSpeed.HIDDEN, RoadLook.parse(RoadLook(RoadSpeed.HIDDEN).encode()).speed)
        // Datos del viaje: de fábrica solo la distancia; vacío también se guarda.
        val trip = RoadLook(trip = setOf(RoadTrip.TIME, RoadTrip.HEADING))
        assertEquals(trip, RoadLook.parse(trip.encode()))
        assertEquals(emptySet<RoadTrip>(), RoadLook.parse(RoadLook(trip = emptySet()).encode()).trip)
        assertEquals(setOf(RoadTrip.DISTANCE), RoadLook.parse("speed=gauge").trip)
    }
}

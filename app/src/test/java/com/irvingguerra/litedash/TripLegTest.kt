package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLegTest {
    private val second = 1_000L
    private val hour = 3_600_000L

    /** El reloj de arranque nunca vale 0 en un equipo (0 = viaje sin empezar). */
    private val T0 = 60_000L

    /** Un viaje que avanza [meters] cada segundo, [points] veces, desde [start]. */
    private fun drive(leg: TripLeg, start: Long, points: Int, meters: Double): TripLeg =
        (1..points).fold(leg) { l, i -> l.moved(start + i * second, meters) }

    @Test
    fun elPrimerPuntoEmpiezaElViaje() {
        val leg = TripLeg().moved(10 * second, 500.0)
        // El primero no suma metros (no hay de dónde) y arranca el reloj.
        assertEquals(0.0, leg.meters, 0.0)
        assertEquals(10 * second, leg.startedAt)
        assertEquals(0L, leg.movingMs)
    }

    @Test
    fun cuentaMetrosYTiempoEnMovimiento() {
        // 15 m/s = 54 km/h durante 2 minutos.
        val leg = drive(TripLeg().moved(T0, 0.0), T0, 120, 15.0)
        assertEquals(1_800.0, leg.meters, 0.001)
        assertEquals(120 * second, leg.movingMs)
        assertEquals(54.0, leg.averageKmh()!!, 0.001)
    }

    @Test
    fun loParadoNoCuentaParaElPromedio() {
        val moving = drive(TripLeg().moved(T0, 0.0), T0, 120, 15.0)
        // Una hora en un semáforo (o estacionado con la app cerrada): el siguiente punto apenas se movió.
        val after = moving.moved(moving.lastAt + hour, 5.0)
        assertEquals(120 * second, after.movingMs)
        assertEquals(54.0, after.averageKmh()!!, 0.5)
        assertFalse(after.over(after.lastAt))
    }

    @Test
    fun manejarConOtraAppAlFrenteSiCuenta() {
        val moving = drive(TripLeg().moved(T0, 0.0), T0, 60, 15.0)
        // 10 minutos sin puntos (Waze al frente) y 9 km más adelante: se avanzó, así que ese rato sí cuenta.
        val after = moving.moved(moving.lastAt + 10 * 60 * second, 9_000.0)
        assertEquals(60 * second + 10 * 60 * second, after.movingMs)
    }

    @Test
    fun trasMuchoRatoSinAvanzarEmpiezaOtroViaje() {
        val moving = drive(TripLeg().moved(T0, 0.0), T0, 120, 15.0)
        assertFalse(moving.over(moving.lastAt + TRIP_IDLE_MS))
        assertTrue(moving.over(moving.lastAt + TRIP_IDLE_MS + 1))
        // A la mañana siguiente, el primer punto empieza en cero (aunque el auto se haya movido de lugar).
        val next = moving.moved(moving.lastAt + 12 * hour, 300.0)
        assertEquals(0.0, next.meters, 0.0)
        assertEquals(0L, next.movingMs)
        assertEquals(moving.lastAt + 12 * hour, next.startedAt)
    }

    @Test
    fun sinPromedioHastaTenerAlgoQuePromediar() {
        assertNull(TripLeg().averageKmh())
        // Medio minuto: todavía no.
        assertNull(drive(TripLeg().moved(T0, 0.0), T0, 30, 15.0).averageKmh())
    }

    @Test
    fun puntosCardinales() {
        assertEquals(0, compassIndex(0f))
        assertEquals(0, compassIndex(359f))
        assertEquals(0, compassIndex(22.4f))
        assertEquals(1, compassIndex(22.5f))
        assertEquals(2, compassIndex(90f))
        assertEquals(4, compassIndex(180f))
        assertEquals(7, compassIndex(337f))
        // Un rumbo fuera de 0–360 también cae en su lugar.
        assertEquals(6, compassIndex(-90f))
        assertEquals(2, compassIndex(450f))
    }
}

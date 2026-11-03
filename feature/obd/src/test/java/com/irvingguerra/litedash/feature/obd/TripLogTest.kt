package com.irvingguerra.litedash.feature.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLogTest {
    @Test
    fun viajeDeDiezMinutosA60() {
        val r = TripRecorder(start = 0)
        // 10 minutos a 60 km/h, una lectura por segundo, con 2 L/h de gasolina.
        for (s in 1..600) r.add(s * 1000L, speedKmh = 60f, rpm = 2000f + s, coolant = 90f, maf = null, fuelRate = 2f)
        val t = r.summary()!!
        assertEquals(10f, t.km, 0.05f)
        assertEquals(60f, t.avgKmh, 0.5f)
        assertEquals(2600f, t.maxRpm, 0.1f)
        assertEquals(0.333f, t.liters!!, 0.01f)
        assertEquals(30f, t.kmPerLiter!!, 0.5f)
    }

    @Test
    fun loParadoNoBajaElPromedioYLosHuecosNoCuentan() {
        val r = TripRecorder(start = 0)
        for (s in 1..300) r.add(s * 1000L, 0f, 800f, null, maf = 3f, fuelRate = null)
        // El adaptador se calló un minuto: ese tiempo no suma distancia.
        for (s in 360..960) r.add(s * 1000L, 60f, 2000f, null, maf = 3f, fuelRate = null)
        val t = r.summary()!!
        assertEquals(60f, t.avgKmh, 0.5f)
        assertEquals(10f, t.km, 0.05f)
        assertTrue(t.liters!! > 0f)
        assertNull(t.maxCoolant)
    }

    @Test
    fun prenderYApagarNoEsViaje() {
        val r = TripRecorder(start = 0)
        for (s in 1..30) r.add(s * 1000L, 0f, 800f, 80f, null, null)
        assertNull(r.summary())
    }

    @Test
    fun csvDeIdaYVuelta() {
        val t = TripSummary(1_700_000_000_000, 1234, 12.34f, 118f, 47f, 5200f, null, null)
        val back = TripSummary.fromCsv(t.toCsv())!!
        assertEquals(t.start, back.start)
        assertEquals(t.km, back.km, 0.01f)
        assertNull(back.maxCoolant)
        assertNull(back.kmPerLiter)
    }

    @Test
    fun ceroACienEntreLecturas() {
        val a = AccelTimer()
        a.update(0, 0f)
        assertEquals(AccelState.Ready, a.state)
        // Arranca entre la lectura de 0 y la de 11 km/h y llega a 100 entre la de 91 y la de 101.
        a.update(1000, 11f)
        assertTrue(a.state is AccelState.Running)
        var t = 1000L
        var v = 11f
        while (v < 110f) { t += 500; v += 10f; a.update(t, v) }
        val done = a.state as AccelState.Done
        // Arrancó a ~91 ms (pasó por 1 km/h) y por 100 km/h a 5450 ms (entre 91 y 101).
        assertEquals(5.36f, done.seconds, 0.02f)
        // Para otra vuelta hay que pararse.
        a.update(t + 500, 50f)
        assertTrue(a.state is AccelState.Done)
        a.update(t + 9000, 0f)
        assertEquals(AccelState.Ready, a.state)
    }

    @Test
    fun siSeDetieneAntesVuelveAEstarListo() {
        val a = AccelTimer()
        a.update(0, 0f)
        a.update(1000, 20f)
        a.update(2000, 0f)
        assertEquals(AccelState.Ready, a.state)
    }
}

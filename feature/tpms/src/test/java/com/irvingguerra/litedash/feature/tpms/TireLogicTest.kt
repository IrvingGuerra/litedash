package com.irvingguerra.litedash.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TireLogicTest {
    private val hour = 3_600_000L
    private val now = 10 * 24 * hour

    private fun reading(id: String, kpa: Float?, at: Long = now, tempC: Float? = 30f, lost: Boolean = false, leak: Boolean = false, lowBattery: Boolean = false, pct: Int? = null) =
        TireReading(id, kpa, tempC, pct, null, lowBattery, leak, lost, "test", null, null, at)

    private fun kinds(alerts: List<TireAlert>, w: Wheel) = alerts.filter { it.wheel == w }.map { it.kind to it.severity }

    @Test
    fun todoBienSinAlertas() {
        val r = Wheel.ROAD.associateWith { reading(it.key, 230f) }
        assertTrue(tireAlerts(r, emptyMap(), TireLimits(), now).isEmpty())
    }

    @Test
    fun bajaYMuyBaja() {
        val limits = TireLimits(recommendedFrontKpa = 230f, recommendedRearKpa = 250f, lowPercent = 20)
        val a = tireAlerts(
            mapOf(
                Wheel.FRONT_LEFT to reading("a", 185f),  // 80.4 % de 230: bien
                Wheel.FRONT_RIGHT to reading("b", 180f), // 78 %: baja
                Wheel.REAR_LEFT to reading("c", 190f),   // 76 % de 250 (trasera): baja
                Wheel.REAR_RIGHT to reading("d", 100f),  // 40 %: peligro
            ),
            emptyMap(), limits, now,
        )
        assertTrue(kinds(a, Wheel.FRONT_LEFT).isEmpty())
        assertEquals(listOf(TireAlertKind.LOW to TireSeverity.WARNING), kinds(a, Wheel.FRONT_RIGHT))
        assertEquals(listOf(TireAlertKind.LOW to TireSeverity.WARNING), kinds(a, Wheel.REAR_LEFT))
        assertEquals(listOf(TireAlertKind.LOW to TireSeverity.DANGER), kinds(a, Wheel.REAR_RIGHT))
        // Lo más grave primero.
        assertEquals(Wheel.REAR_RIGHT, a.first().wheel)
    }

    @Test
    fun fugaRapidaPorHistorialYPorBandera() {
        val hist = listOf(TireSample(now - 4 * 60_000, 230f), TireSample(now - 2 * 60_000, 222f))
        val a = tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 215f)), mapOf(Wheel.FRONT_LEFT to hist), TireLimits(), now)
        assertEquals(listOf(TireAlertKind.FAST_LEAK to TireSeverity.DANGER), kinds(a, Wheel.FRONT_LEFT))
        // Lo de hace más de 5 min no cuenta.
        val old = listOf(TireSample(now - 6 * 60_000, 240f))
        assertTrue(tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 225f)), mapOf(Wheel.FRONT_LEFT to old), TireLimits(), now).isEmpty())
        val flag = tireAlerts(mapOf(Wheel.REAR_LEFT to reading("c", 230f, leak = true)), emptyMap(), TireLimits(), now)
        assertEquals(TireAlertKind.FAST_LEAK, flag.single().kind)
    }

    @Test
    fun fugaLenta() {
        // Tres días bajando de 240 a 215 kPa, una muestra cada 30 min.
        val hist = (0..144).map { i -> TireSample(now - 72 * hour + i * 30 * 60_000L, 240f - 25f * i / 144f) }
        val a = tireAlerts(mapOf(Wheel.REAR_RIGHT to reading("d", 215f)), mapOf(Wheel.REAR_RIGHT to hist), TireLimits(), now)
        assertEquals(listOf(TireAlertKind.SLOW_LEAK to TireSeverity.WARNING), kinds(a, Wheel.REAR_RIGHT))
        // Poco historial (12 h): no se dice nada aunque baje.
        val short = hist.filter { now - it.atMillis <= 12 * hour }
        assertTrue(tireAlerts(mapOf(Wheel.REAR_RIGHT to reading("d", 215f)), mapOf(Wheel.REAR_RIGHT to short), TireLimits(), now).isEmpty())
    }

    @Test
    fun calorPilaYPerdido() {
        val a = tireAlerts(
            mapOf(
                Wheel.FRONT_LEFT to reading("a", 230f, tempC = 80f),
                Wheel.FRONT_RIGHT to reading("b", 230f, lowBattery = true),
                Wheel.REAR_LEFT to reading("c", 230f, pct = 8),
                Wheel.REAR_RIGHT to reading("d", 100f, at = now - 11 * 60_000L),
                Wheel.SPARE to reading("e", 230f, lost = true),
            ),
            emptyMap(), TireLimits(), now,
        )
        assertEquals(listOf(TireAlertKind.HIGH_TEMP to TireSeverity.DANGER), kinds(a, Wheel.FRONT_LEFT))
        assertEquals(listOf(TireAlertKind.LOW_BATTERY to TireSeverity.WARNING), kinds(a, Wheel.FRONT_RIGHT))
        assertEquals(listOf(TireAlertKind.LOW_BATTERY to TireSeverity.WARNING), kinds(a, Wheel.REAR_LEFT))
        // Perdida: solo LOST (su presión vieja no da "baja").
        assertEquals(listOf(TireAlertKind.LOST to TireSeverity.WARNING), kinds(a, Wheel.REAR_RIGHT))
        assertEquals(listOf(TireAlertKind.LOST to TireSeverity.WARNING), kinds(a, Wheel.SPARE))
        assertTrue(a.all { it.message.isNotBlank() })
    }

    @Test
    fun todosViejosNoEsPerdido() {
        // El auto estuvo apagado: nadie actualiza, no se avisa de sensores perdidos.
        val r = Wheel.ROAD.associateWith { reading(it.key, 230f, at = now - 2 * hour) }
        assertTrue(tireAlerts(r, emptyMap(), TireLimits(), now).isEmpty())
    }

    @Test
    fun historialUnaMuestraCada30sYSieteDias() {
        val h = TireHistory()
        assertTrue(h.add(Wheel.FRONT_LEFT, 0L, 230f))
        assertTrue(!h.add(Wheel.FRONT_LEFT, 29_000L, 231f))
        assertTrue(h.add(Wheel.FRONT_LEFT, 30_000L, 229.4f))
        assertEquals(listOf(230f, 229.4f), h.samples(Wheel.FRONT_LEFT).map { it.kpa })
        // 8 días después: lo de hace más de 7 días se va.
        val t = 8 * 24 * hour
        h.add(Wheel.FRONT_LEFT, t, 200f)
        assertEquals(listOf(t), h.samples(Wheel.FRONT_LEFT).map { it.atMillis })
        assertTrue(h.samples(Wheel.REAR_LEFT).isEmpty())
    }

    @Test
    fun historialLlenoNoCrece() {
        val h = TireHistory()
        repeat(TireHistory.CAPACITY + 50) { h.add(Wheel.REAR_RIGHT, it * 30_000L, 200f + it % 10) }
        val s = h.samples(Wheel.REAR_RIGHT)
        assertTrue(s.size <= TireHistory.CAPACITY)
        assertEquals((TireHistory.CAPACITY + 49) * 30_000L, s.last().atMillis)
    }

    @Test
    fun historialSeGuardaYSeLee() {
        val h = TireHistory()
        for (i in 0 until 100) {
            h.add(Wheel.FRONT_LEFT, 1_700_000_000_000L + i * 30_000L, 220f + i / 10f)
            h.add(Wheel.SPARE, 1_700_000_000_000L + i * 30_000L, 150f)
        }
        val bytes = h.encode()
        val back = TireHistory.decode(bytes)
        assertEquals(h.samples(Wheel.FRONT_LEFT), back.samples(Wheel.FRONT_LEFT))
        assertEquals(h.samples(Wheel.SPARE), back.samples(Wheel.SPARE))
        // Dañado o vacío: empieza limpio.
        assertTrue(TireHistory.decode(bytes.copyOf(20)).samples(Wheel.FRONT_LEFT).isEmpty())
        assertTrue(TireHistory.decode(null).all().isEmpty())
    }

    @Test
    fun identificarPorCambioOSensorNuevo() {
        val t0 = 1_000L
        val idf = SensorIdentifier(mapOf("a" to 230f, "b" to 228f), t0)
        assertNull(idf.check(mapOf("a" to reading("a", 233f, at = t0 + 5_000), "b" to reading("b", 228f, at = t0 + 5_000)), t0 + 5_000))
        assertEquals("b", idf.check(mapOf("a" to reading("a", 233f, at = t0 + 9_000), "b" to reading("b", 220f, at = t0 + 9_000)), t0 + 9_000))
        assertEquals("c", idf.check(mapOf("c" to reading("c", 200f, at = t0 + 9_000)), t0 + 9_000))
        // Ya pasaron los 120 s.
        assertNull(idf.check(mapOf("b" to reading("b", 100f, at = t0 + 121_000)), t0 + 121_000))
    }

    @Test
    fun demoConFugaLenta() {
        val demo = DemoTpms(Random(1))
        val first = demo.tick(0L)
        assertEquals(4, first.size)
        assertTrue(first.all { it.pressureKpa!! in 215f..225f && it.tempC!! in 25f..35f })
        val later = demo.tick(30 * 60_000L).first { it.reportedWheel == Wheel.REAR_RIGHT }
        assertEquals(190f, later.pressureKpa!!, 1f)
        // Al llegar a 150 vuelve a empezar.
        val reset = demo.tick(71 * 60_000L).first { it.reportedWheel == Wheel.REAR_RIGHT }
        assertEquals(220f, reset.pressureKpa!!, 1f)
    }

    @Test
    fun unidades() {
        assertEquals(33.4f, PressureUnit.PSI.fromKpa(230f), 0.05f)
        assertEquals(2.3f, PressureUnit.BAR.fromKpa(230f), 0.001f)
        assertEquals(230f, PressureUnit.PSI.toKpa(PressureUnit.PSI.fromKpa(230f)), 0.001f)
        assertEquals("33", PressureUnit.PSI.format(230f))
        assertEquals("2.3", PressureUnit.BAR.format(230f))
        assertEquals("tire_pressure_rr", TireSignals.pressure(Wheel.REAR_RIGHT))
        assertEquals("tire_temp_spare", TireSignals.temp(Wheel.SPARE))
        assertTrue(TireSignals.isTire("tire_temp_fl"))
    }
}

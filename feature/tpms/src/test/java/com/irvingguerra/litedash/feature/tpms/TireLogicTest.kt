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

    private val w = Wheel.FRONT_LEFT

    private fun historyOf(samples: List<TireSample>) = TireHistory().also { h -> samples.forEach { h.add(w, it.atMillis, it.kpa, it.tempC) } }

    /** Lo mismo que arma el repositorio: las muestras de los últimos 5 min y la fuga lenta. */
    private fun trend(hist: List<TireSample>) = historyOf(hist).let { TireTrend(it.recent(w, now), it.slowLeak(w, now)) }

    private fun slowLeak(hist: List<TireSample>) = historyOf(hist).slowLeak(w, now)

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
        val a = tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 215f)), mapOf(Wheel.FRONT_LEFT to trend(hist)), TireLimits(), now)
        assertEquals(listOf(TireAlertKind.FAST_LEAK to TireSeverity.DANGER), kinds(a, Wheel.FRONT_LEFT))
        // Lo de hace más de 5 min no cuenta.
        val old = listOf(TireSample(now - 6 * 60_000, 240f))
        assertTrue(tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 225f)), mapOf(Wheel.FRONT_LEFT to trend(old)), TireLimits(), now).isEmpty())
        val flag = tireAlerts(mapOf(Wheel.REAR_LEFT to reading("c", 230f, leak = true)), emptyMap(), TireLimits(), now)
        assertEquals(TireAlertKind.FAST_LEAK, flag.single().kind)
    }

    @Test
    fun fugaLenta() {
        // Tres días bajando de 240 a 215 kPa, una muestra cada 30 min.
        val hist = (0..144).map { i -> TireSample(now - 72 * hour + i * 30 * 60_000L, 240f - 25f * i / 144f) }
        val a = tireAlerts(mapOf(Wheel.REAR_RIGHT to reading("d", 215f)), mapOf(Wheel.REAR_RIGHT to trend(hist)), TireLimits(), now)
        assertEquals(listOf(TireAlertKind.SLOW_LEAK to TireSeverity.WARNING), kinds(a, Wheel.REAR_RIGHT))
        // Poco historial (12 h): no se dice nada aunque baje.
        val short = hist.filter { now - it.atMillis <= 12 * hour }
        assertTrue(tireAlerts(mapOf(Wheel.REAR_RIGHT to reading("d", 215f)), mapOf(Wheel.REAR_RIGHT to trend(short)), TireLimits(), now).isEmpty())
    }

    @Test
    fun enfriarseNoEsFuga() {
        // Rodó: 264 kPa a 50 °C (230 kPa en frío). Estacionado, el aire baja a 25 °C y la presión a 237: mismo aire.
        assertEquals(230f, coldKpa(264f, 50f), 1f)
        val hot = listOf(TireSample(now - 4 * 60_000, 264f, 50f), TireSample(now - 2 * 60_000, 250f, 38f))
        assertTrue(tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 237f, tempC = 25f)), mapOf(Wheel.FRONT_LEFT to trend(hot)), TireLimits(), now).isEmpty())
        // Sin temperatura en la lectura de ahora no se corrige: esa misma bajada sí avisa (como antes).
        val a = tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 237f, tempC = null)), mapOf(Wheel.FRONT_LEFT to trend(hot)), TireLimits(), now)
        assertEquals(TireAlertKind.FAST_LEAK, a.single().kind)
        // Una fuga de verdad sigue avisando aunque la llanta esté caliente.
        val leak = listOf(TireSample(now - 4 * 60_000, 264f, 50f))
        val b = tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 245f, tempC = 50f)), mapOf(Wheel.FRONT_LEFT to trend(leak)), TireLimits(), now)
        assertEquals(TireAlertKind.FAST_LEAK, b.single().kind)
        // Un receptor sin dato manda 205 °C (o −50): esa muestra no se mezcla con las corregidas.
        val absurd = listOf(TireSample(now - 4 * 60_000, 250f, 205f), TireSample(now - 2 * 60_000, 240f, 30f))
        assertTrue(tireAlerts(mapOf(Wheel.FRONT_LEFT to reading("a", 236f, tempC = 30f)), mapOf(Wheel.FRONT_LEFT to trend(absurd)), TireLimits(), now).isEmpty())
    }

    @Test
    fun rodarEnCalienteYHoyEnFrioNoEsFugaLenta() {
        // Hace 3 días rodó con las llantas a 55 °C (≈ 268 kPa); hoy amaneció a 18 °C (≈ 227 kPa): mismo aire.
        val hot = (0..12).map { i -> TireSample(now - 72 * hour + i * 30 * 60_000L, 268f, 55f) }
        val cold = (0..4).map { i -> TireSample(now - 40 * 60_000L + i * 10 * 60_000L, 227f, 18f) }
        assertTrue(!slowLeak(hot + cold))
        // Sin temperaturas eso sí parecía fuga (lo que reportó el usuario).
        assertTrue(slowLeak((hot + cold).map { it.copy(tempC = null) }))
        // Las muestras viejas sin temperatura no se mezclan con las nuevas.
        assertTrue(!slowLeak(hot.map { it.copy(tempC = null) } + cold))
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
    }

    @Test
    fun todosViejosNoEsPerdido() {
        // El auto estuvo apagado: nadie actualiza, no se avisa de sensores perdidos.
        val r = Wheel.ROAD.associateWith { reading(it.key, 230f, at = now - 2 * hour) }
        assertTrue(tireAlerts(r, emptyMap(), TireLimits(), now).isEmpty())
    }

    @Test
    fun todosViejosSoloBajaYFugaLenta() {
        // Lo último que se supo hace 2 h: una llanta ponchada, caliente y con la bandera de fuga.
        val r = Wheel.ROAD.associateWith { reading(it.key, 230f, at = now - 2 * hour) } +
            (Wheel.REAR_LEFT to reading("rl", 120f, at = now - 2 * hour, tempC = 90f, leak = true, lowBattery = true))
        val a = tireAlerts(r, emptyMap(), TireLimits(), now)
        assertEquals(listOf(TireAlert(Wheel.REAR_LEFT, TireAlertKind.LOW, TireSeverity.DANGER)), a)
        // La fuga lenta sí (ya venía del historial).
        val slow = tireAlerts(r, mapOf(Wheel.FRONT_LEFT to TireTrend(emptyList(), slowLeak = true)), TireLimits(), now)
        assertEquals(listOf(TireAlertKind.SLOW_LEAK to TireSeverity.WARNING), kinds(slow, Wheel.FRONT_LEFT))
    }

    @Test
    fun historialSeIntercambiaYSeCorta() {
        val h = TireHistory()
        h.add(Wheel.FRONT_LEFT, now, 200f, 20f)
        h.add(Wheel.REAR_RIGHT, now, 240f, 20f)
        val before = h.changes
        h.swap(Wheel.FRONT_LEFT, Wheel.REAR_RIGHT)
        assertEquals(240f, h.samples(Wheel.FRONT_LEFT).single().kpa)
        assertEquals(200f, h.samples(Wheel.REAR_RIGHT).single().kpa)
        // Con una vacía, la otra queda vacía.
        h.swap(Wheel.FRONT_LEFT, Wheel.SPARE)
        assertTrue(h.samples(Wheel.FRONT_LEFT).isEmpty())
        assertEquals(240f, h.samples(Wheel.SPARE).single().kpa)
        h.cut(Wheel.SPARE)
        assertTrue(h.samples(Wheel.SPARE).isEmpty())
        assertEquals(200f, h.samples(Wheel.REAR_RIGHT).single().kpa)
        // Cada cambio se tiene que guardar.
        assertEquals(before + 3, h.changes)
        h.cut(Wheel.SPARE)
        assertEquals(before + 3, h.changes)
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
    fun historialColaConTemperaturaYCopia() {
        val h = TireHistory()
        h.add(Wheel.FRONT_LEFT, 0L, 240f, 41.4f)
        h.add(Wheel.FRONT_LEFT, 60_000L, 230f)
        h.add(Wheel.FRONT_LEFT, 120_000L, 225f, -5f)
        assertEquals(listOf(TireSample(60_000L, 230f, null), TireSample(120_000L, 225f, -5f)), h.samples(Wheel.FRONT_LEFT, 60_000L))
        assertEquals(41f, h.samples(Wheel.FRONT_LEFT).first().tempC!!, 0.01f)
        assertTrue(h.samples(Wheel.FRONT_LEFT, 200_000L).isEmpty())
        assertTrue(h.samples(Wheel.REAR_LEFT, 0L).isEmpty())
        // La copia para guardar no cambia con lo que entra después.
        assertTrue(h.dirty)
        val changes = h.changes
        val copy = h.snapshot()
        // Copiar no es guardar: sigue pendiente hasta que la escritura salga bien.
        assertTrue(h.dirty)
        h.add(Wheel.FRONT_LEFT, 180_000L, 220f)
        // Lo que entró mientras se escribía sigue pendiente.
        h.saved(changes)
        assertTrue(h.dirty)
        h.saved(h.changes)
        assertTrue(!h.dirty)
        assertEquals(3, copy.samples(Wheel.FRONT_LEFT).size)
        assertEquals(h.samples(Wheel.FRONT_LEFT).take(3), copy.samples(Wheel.FRONT_LEFT))
    }

    @Test
    fun historialSeGuardaYSeLee() {
        val h = TireHistory()
        for (i in 0 until 100) {
            h.add(Wheel.FRONT_LEFT, 1_700_000_000_000L + i * 30_000L, 220f + i / 10f, if (i % 2 == 0) 20f + i % 30 else null)
            h.add(Wheel.SPARE, 1_700_000_000_000L + i * 30_000L, 150f)
        }
        val bytes = h.encode()
        val back = TireHistory.decode(bytes)
        assertEquals(h.samples(Wheel.FRONT_LEFT), back.samples(Wheel.FRONT_LEFT))
        assertEquals(h.samples(Wheel.SPARE), back.samples(Wheel.SPARE))
        // El formato de antes (TPH1, sin temperatura) se sigue leyendo.
        val v1 = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).use { o ->
                o.writeInt(0x54504831); o.writeByte(1); o.writeUTF("fl"); o.writeInt(2)
                o.writeInt(1_700_000_000); o.writeShort(2300); o.writeInt(1_700_000_030); o.writeShort(2290)
            }
        }.toByteArray()
        assertEquals(listOf(TireSample(1_700_000_000_000L, 230f), TireSample(1_700_000_030_000L, 229f)), TireHistory.decode(v1).samples(Wheel.FRONT_LEFT))
        // Dañado o vacío: empieza limpio.
        assertTrue(TireHistory.decode(bytes.copyOf(20)).samples(Wheel.FRONT_LEFT).isEmpty())
        assertTrue(TireHistory.decode(null).samples(Wheel.FRONT_LEFT).isEmpty())
    }

    @Test
    fun relojQueRegresaNoBorraElHistorial() {
        // Se guardó con la hora buena; la pantalla (sin pila de reloj) arranca 400 días atrás.
        val good = 1_780_000_000_000L
        val h = TireHistory()
        h.add(w, good, 230f, 25f)
        h.add(w, good + 30_000L, 231f, 25f)
        val boot = good - 400 * 24 * hour
        assertTrue(h.add(w, boot, 228f, 20f))
        // Un regreso chico (< 30 s) no es otra sesión: solo espera su turno.
        assertTrue(!h.add(w, boot + 10_000L, 226f))
        assertTrue(h.add(w, boot + 30_000L, 227f, 20f))
        // Nada se borró, y en la hora de ahora lo de esta sesión es "ahora" y lo de antes va antes, separado por más que
        // la ventana de la fuga rápida (que solo ve esta sesión).
        val gap = TireHistory.SESSION_GAP_S * 1000L
        assertEquals(listOf(boot - gap - 30_000L, boot - gap, boot, boot + 30_000L), h.samples(w).map { it.atMillis })
        assertEquals(listOf(228f, 227f), h.recent(w, boot + 30_000L).map { it.kpa })
        // La red corrige la hora: de nuevo tal cual, sin perder nada.
        val fixed = good + 2 * hour
        assertTrue(h.add(w, fixed, 229f))
        assertEquals(5, h.samples(w).size)
        assertEquals(fixed, h.samples(w).last().atMillis)
        assertEquals(5, TireHistory.decode(h.encode()).samples(w).size)
    }

    @Test
    fun sinRedCadaArranqueSigueDondeIba() {
        // Pantalla que nunca recibe la hora: cada arranque empieza en la misma fecha vieja.
        val boot = 1_420_070_400_000L
        val first = TireHistory()
        repeat(10) { first.add(w, boot + it * 30_000L, 230f) }
        val again = TireHistory.decode(first.encode())
        repeat(4) { assertTrue(again.add(w, boot + it * 30_000L, 225f)) }
        assertEquals(14, again.samples(w).size)
        assertEquals(boot + 90_000L, again.samples(w).last().atMillis)
        assertEquals(List(4) { 225f }, again.recent(w, boot + 90_000L).takeLast(4).map { it.kpa })
        // Lo del viaje anterior sigue a la vista, antes del hueco de sesión.
        val gap = TireHistory.SESSION_GAP_S * 1000L
        assertEquals(230f, again.samples(w, boot - gap, boot - gap).single().kpa)
    }

    @Test
    fun sinRelojLaFugaRapidaNoCruzaSesiones() {
        // Ayer: viaje con las llantas calientes (240 kPa, sin temperatura, como el OBD de fábrica). Hoy la pantalla
        // arranca otra vez en su fecha vieja con las llantas frías (222): no es fuga rápida.
        val boot = 1_420_070_400_000L
        val yesterday = TireHistory()
        repeat(20) { yesterday.add(w, boot + it * 30_000L, 240f) }
        val today = TireHistory.decode(yesterday.encode())
        assertTrue(today.add(w, boot, 222f))
        val recent = today.recent(w, boot)
        assertEquals(listOf(222f), recent.map { it.kpa })
        val drop = pressureDrop(recent, 222f, null) ?: 0f
        assertTrue("caída $drop", drop < FAST_LEAK_KPA)
        // La fuga lenta sigue viendo el historial de antes.
        assertEquals(21, today.samples(w).size)
    }

    @Test
    fun loDelFuturoNoEntraEnLasAlertas() {
        // Muestras guardadas con un reloj adelantado (o antes de que regresara) no cuentan como de ahora.
        val future = listOf(TireSample(now - 2 * 60_000, 230f), TireSample(now + 10 * 60_000, 260f))
        val h = historyOf(future)
        assertEquals(listOf(now - 2 * 60_000), h.recent(w, now).map { it.atMillis })
        assertTrue(h.samples(w, 0, now).none { it.atMillis > now })
        // Tres días que en verdad son "mañana": no es fuga lenta.
        val ahead = (0..144).map { i -> TireSample(now + i * 30 * 60_000L, 240f - 25f * i / 144f) }
        assertTrue(!slowLeak(ahead))
    }

    @Test
    fun historialCreceSegunSeLlenaYSeGuardaDelTamanoJusto() {
        val h = TireHistory()
        repeat(1_000) { h.add(Wheel.REAR_LEFT, it * 30_000L, 200f) }
        assertEquals(1_000, h.samples(Wheel.REAR_LEFT).size)
        // 4 (formato) + 1 (llantas) + 2 + 2 ("rl") + 4 (cuántas) + 7 por muestra.
        assertEquals(4 + 1 + 2 + 2 + 4 + 7 * 1_000, h.encode().size)
        assertEquals(h.samples(Wheel.REAR_LEFT), h.snapshot().samples(Wheel.REAR_LEFT))
    }

    @Test
    fun archivoConConteoImposibleEmpiezaVacio() {
        val bytes = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).use { o ->
                o.writeInt(0x54504832); o.writeByte(1); o.writeUTF("fl"); o.writeInt(Int.MAX_VALUE)
                o.writeInt(1_700_000_000); o.writeShort(2300); o.writeByte(20)
            }
        }.toByteArray()
        assertTrue(TireHistory.decode(bytes).samples(Wheel.FRONT_LEFT).isEmpty())
    }

    @Test
    fun limiteDeArranquesDeBluetooth() {
        var t = 0L
        val l = StartLimiter { t }
        repeat(StartLimiter.MAX_STARTS) { assertTrue(l.canStart()); l.started(); t += 1_000 }
        assertTrue(!l.canStart())
        // Hay que esperar a que el primero salga de la ventana (+ medio segundo).
        assertEquals(StartLimiter.WINDOW_MS - t + 500, l.waitMs())
        t += l.waitMs()
        assertTrue(l.canStart())
    }

    @Test
    fun identificarPorCambioTambienSiApareceNuevo() {
        val t0 = 1_000L
        val idf = SensorIdentifier(mapOf("a" to 230f, "b" to 228f), t0)
        assertNull(idf.check(mapOf("a" to reading("a", 233f, at = t0 + 5_000), "b" to reading("b", 228f, at = t0 + 5_000)), t0 + 5_000))
        assertEquals("b", idf.check(mapOf("a" to reading("a", 233f, at = t0 + 9_000), "b" to reading("b", 220f, at = t0 + 9_000)), t0 + 9_000))
        // Uno nuevo (otro auto que pasó) no basta: tiene que cambiar desde que se oyó.
        assertNull(idf.check(mapOf("c" to reading("c", 200f, at = t0 + 9_000)), t0 + 9_000))
        assertNull(idf.check(mapOf("c" to reading("c", 203f, at = t0 + 10_000)), t0 + 10_000))
        assertEquals("c", idf.check(mapOf("c" to reading("c", 192f, at = t0 + 11_000)), t0 + 11_000))
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

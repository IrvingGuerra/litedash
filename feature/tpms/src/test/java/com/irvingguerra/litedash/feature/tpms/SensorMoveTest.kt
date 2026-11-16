package com.irvingguerra.litedash.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorMoveTest {
    private val fl = Wheel.FRONT_LEFT
    private val fr = Wheel.FRONT_RIGHT
    private val rl = Wheel.REAR_LEFT

    private fun sysgration(id: String, w: Wheel) =
        TireReading(id, 230f, 25f, 90, null, false, false, false, "Sysgration", w, -60, 0L)

    @Test
    fun acomodoSoloConLlantasAbiertas() {
        val r = sysgration("A1", fl)
        assertEquals(fl, autoAssignTarget(emptyMap(), r, emptySet(), watching = true))
        // El vecino de al lado con la pantalla cerrada no entra.
        assertNull(autoAssignTarget(emptyMap(), r, emptySet(), watching = false))
    }

    @Test
    fun quitarSeQueda() {
        // El usuario lo quitó: aunque siga diciendo su llanta y esté libre, no vuelve solo.
        assertNull(autoAssignTarget(emptyMap(), sysgration("A1", fl), setOf("A1"), watching = true))
        // Ni a una llanta ocupada, ni un sensor ya puesto en otra.
        assertNull(autoAssignTarget(mapOf(fl to "B2"), sysgration("A1", fl), emptySet(), watching = true))
        assertNull(autoAssignTarget(mapOf(fr to "A1"), sysgration("A1", fl), emptySet(), watching = true))
        // Sin llanta dicha, nada.
        assertNull(autoAssignTarget(emptyMap(), sysgration("A1", fl).copy(reportedWheel = null), emptySet(), watching = true))
    }

    @Test
    fun quitadosSinRepetirYConTope() {
        assertEquals(listOf("b", "c", "a"), pushRecent(listOf("a", "b", "c"), "a", 20))
        assertEquals(listOf("b", "c", "d"), pushRecent(listOf("a", "b", "c"), "d", 3))
    }

    @Test
    fun moverUnSensorLlevaSuHistorial() {
        // A1 pasa de fl a fr (donde estaba B2): el historial de fl va a fr y lo de B2 se vacía.
        val m = moveSensor(mapOf(fl to "A1", fr to "B2"), fr, "A1", emptyMap(), emptyMap())
        assertEquals(mapOf(fr to "A1"), m.assignment)
        assertEquals(fl, m.swapWith)
        assertEquals(setOf(fl), m.cut)
        assertNull(m.offset)
    }

    @Test
    fun sensorNuevoEmpiezaSinHistorial() {
        val m = moveSensor(mapOf(fl to "A1"), fl, "C3", emptyMap(), emptyMap())
        assertEquals(mapOf(fl to "C3"), m.assignment)
        assertNull(m.swapWith)
        assertEquals(setOf(fl), m.cut)
        // El mismo sensor en su misma llanta: nada cambia.
        val same = moveSensor(mapOf(fl to "A1"), fl, "A1", mapOf(fl to 5f), emptyMap())
        assertNull(same.swapWith)
        assertTrue(same.cut.isEmpty())
        assertNull(same.offset)
    }

    @Test
    fun elAjusteEsperaAlPrimerSensor() {
        // La llanta tenía un ajuste de antes sin sensor: lo estrena el primero que llega.
        assertEquals(-7f, moveSensor(emptyMap(), rl, "D4", mapOf(rl to -7f), emptyMap()).offset)
        // Si ese sensor ya trae su propio ajuste, se respeta el suyo.
        assertNull(moveSensor(emptyMap(), rl, "D4", mapOf(rl to -7f), mapOf("D4" to 3f)).offset)
    }

    @Test
    fun migracionAlAjustePorSensor() {
        val saved = mapOf(
            "offset_kpa" to 4f,
            "offset_kpa_fl" to -6f,
            "offset_kpa_fr" to 0f,
            "assign_fl" to "AA:01",
            "assign_rr" to "AA:04",
            "source" to "ble",
        )
        val m = offsetMigration(saved)
        assertEquals(
            mapOf(
                // fl: el suyo, en su sensor Bluetooth, OBD y USB.
                "offset_kpa_id_AA:01" to -6f, "offset_kpa_id_obd-fl" to -6f, "offset_kpa_id_usb-fl" to -6f,
                // fr: en 0, nada. rl: sin propio usa el global y, sin sensor, queda esperando.
                "offset_kpa_pending_rl" to 4f, "offset_kpa_id_obd-rl" to 4f, "offset_kpa_id_usb-rl" to 4f,
                "offset_kpa_id_AA:04" to 4f, "offset_kpa_id_obd-rr" to 4f, "offset_kpa_id_usb-rr" to 4f,
                // La refacción no tiene OBD.
                "offset_kpa_pending_spare" to 4f, "offset_kpa_id_usb-spare" to 4f,
            ),
            m.put,
        )
        assertEquals(setOf("offset_kpa", "offset_kpa_fl", "offset_kpa_fr"), m.remove)
    }

    @Test
    fun migracionDosVecesDaLoMismo() {
        val saved = mutableMapOf<String, Any?>("offset_kpa_fl" to -6f, "assign_fl" to "AA:01")
        val first = offsetMigration(saved)
        // Se cortó entre escribir y quitar: la segunda no pisa lo ya escrito.
        saved.putAll(first.put)
        saved["offset_kpa_id_AA:01"] = 2f
        assertTrue(offsetMigration(saved).put.isEmpty())
        // Ya completa: nada que escribir ni quitar.
        first.remove.forEach(saved::remove)
        assertEquals(OffsetMigration(emptyMap(), emptySet()), offsetMigration(saved))
        // Sin nada de antes, nada.
        assertEquals(OffsetMigration(emptyMap(), emptySet()), offsetMigration(mapOf("assign_fl" to "AA:01")))
    }
}

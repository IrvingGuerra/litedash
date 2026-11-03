package com.irvingguerra.litedash.feature.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private fun report(stored: List<String> = emptyList(), pending: List<String> = emptyList(), mil: Boolean = false) =
        EngineReport(Readiness(mil, stored.size, emptyList()), stored, pending, emptyList(), null, 12.6f)

    @Test
    fun `los códigos caen en su parte`() {
        assertEquals(Zone.INTAKE, zoneOf("P0171"))
        assertEquals(Zone.ENGINE, zoneOf("P0301"))
        assertEquals(Zone.ENGINE, zoneOf("P0606"))
        assertEquals(Zone.EXHAUST, zoneOf("P0420"))
        assertEquals(Zone.TRANSMISSION, zoneOf("P0730"))
        assertEquals(Zone.COOLING, zoneOf("P0128"))
        assertEquals(Zone.BATTERY, zoneOf("P0562"))
        assertEquals(Zone.WHEELS, zoneOf("C0035"))
        assertEquals(Zone.BODY, zoneOf("U0100"))
        assertEquals(Zone.BODY, zoneOf("B1234"))
        // Sensores de oxígeno: aunque sean de la familia 01xx/00xx, están en el escape.
        assertEquals(Zone.EXHAUST, zoneOf("P0133"))
        assertEquals(Zone.EXHAUST, zoneOf("P0036"))
        assertEquals(Zone.INTAKE, zoneOf("P0101"))
        assertEquals(Zone.ENGINE, zoneOf("P0011"))
        assertEquals(Zone.EXHAUST, zoneOf("P0041"))
        assertEquals(Zone.EXHAUST, zoneOf("P0060"))
        // Turbo: del aire, aunque caigan entre los de oxígeno.
        assertEquals(Zone.INTAKE, zoneOf("P0034"))
        assertEquals(Zone.INTAKE, zoneOf("P003A"))
        assertEquals(Zone.INTAKE, zoneOf("P0047"))
        // Los conocidos del enfriamiento y la batería mandan sobre la familia.
        assertEquals(Zone.COOLING, zoneOf("P0117"))
        assertEquals(Zone.BATTERY, zoneOf("P0562"))
    }

    @Test
    fun `sin reporte ni datos no se sabe nada`() {
        val health = healthOf(null, null, null, null, null)
        assertTrue(health.all { it.level == HealthLevel.UNKNOWN })
        assertEquals(0, health.issues())
    }

    @Test
    fun `con reporte limpio todo está bien`() {
        val health = healthOf(report(), null, 90f, 14.1f, 800f)
        assertTrue(health.all { it.level == HealthLevel.GOOD })
        assertEquals(90f, health.first { it.zone == Zone.COOLING }.value)
        assertEquals(14.1f, health.first { it.zone == Zone.BATTERY }.value)
    }

    @Test
    fun `guardado es falla y pendiente es atención`() {
        val health = healthOf(report(stored = listOf("P0420"), pending = listOf("P0171")), null, null, null, null)
        assertEquals(HealthLevel.FAULT, health.first { it.zone == Zone.EXHAUST }.level)
        assertEquals(listOf("P0420"), health.first { it.zone == Zone.EXHAUST }.codes)
        assertEquals(HealthLevel.WARN, health.first { it.zone == Zone.INTAKE }.level)
        assertEquals(HealthLevel.GOOD, health.first { it.zone == Zone.ENGINE }.level)
        assertEquals(2, health.issues())
    }

    @Test
    fun `las alertas en vivo señalan enfriamiento o batería`() {
        val hot = healthOf(report(), EngineAlert.HOT, 115f, 14f, 2000f)
        assertEquals(HealthLevel.FAULT, hot.first { it.zone == Zone.COOLING }.level)
        val charging = healthOf(report(), EngineAlert.NOT_CHARGING, 90f, 11.8f, 2000f)
        assertEquals(HealthLevel.WARN, charging.first { it.zone == Zone.BATTERY }.level)
        assertEquals(EngineAlert.NOT_CHARGING, charging.first { it.zone == Zone.BATTERY }.alert)
    }

    @Test
    fun `luz de falla sin códigos deja al motor en atención`() {
        val health = healthOf(report(mil = true), null, null, null, null)
        assertEquals(HealthLevel.WARN, health.first { it.zone == Zone.ENGINE }.level)
    }
}

package com.irvingguerra.litedash.feature.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Con respuestas crudas de capturas reales (OBDb) tal como las entrega el adaptador. */
class FactoryTiresTest {
    private fun profile(id: String) = TireProfiles.first { it.id == id }
    private fun bytes(request: String, vararg lines: String) = parseModuleReply(request, lines.toList())!!

    @Test fun mitsubishi_outlander_sport_2012() {
        // 61 23 00 C2 0D B9 F3 83 01 96 96 4D 0F: d[8] = 150 → 30 psi; d[9] = 77 → 27 °C.
        val d = bytes("2123", "00D", "0:612300C20DB9", "1:F3830196964D0F")
        val v = profile("Mitsubishi").decode(List(4) { d })
        assertEquals(30f * 6.89476f, v.kpa[0]!!, 0.1f)
        assertEquals(27f, v.tempC[0])
    }

    @Test fun toyota_camry_2015() {
        // 61 30 00 00 00 B1 …: la trasera derecha (d[3]) = 177/58 − 0.5 = 2.55 bar; 0 = sin dato.
        val d = bytes("2130", "007", "0:61300000 00B1".replace(" ", ""), "1:00")
        val v = profile("Toyota / Lexus").decode(listOf(d))
        assertNull(v.kpa[0])
        assertEquals(255.17f, v.kpa[3]!!, 0.1f)
    }

    @Test fun ford_escape_2015() {
        // 62 28 13 03 00 → 768/20 = 38.4 psi.
        val v = profile("Ford").decode(List(4) { bytes("222813", "6228130300") })
        assertEquals(38.4f * 6.89476f, v.kpa[0]!!, 0.1f)
    }

    @Test fun gm_equinox_2017() {
        // 62 50 05 0F 4C → 3916/16 = 244.75 kPa.
        val v = profile("GM").decode(List(4) { bytes("225005", "6250050F4C") })
        assertEquals(244.75f, v.kpa[0]!!, 0.01f)
    }

    @Test fun vin_primero() = assertEquals("Mitsubishi", ordered("JA32W8FV0AU000123").first().id)
}

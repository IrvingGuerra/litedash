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

    @Test fun sin_vin_el_orden_de_siempre() = assertEquals(TireProfiles, ordered(null))

    @Test fun nissan_las_dos() {
        // 62 0E 26 8C → 140/4 = 35 psi; 62 02 01 80 → 128/4 = 32 psi.
        val new = profile("Nissan / Mitsubishi").decode(List(4) { bytes("220E26", "620E268C") })
        assertEquals(35f * 6.89476f, new.kpa[0]!!, 0.1f)
        val old = profile("Nissan").decode(List(4) { bytes("220201", "62020180") })
        assertEquals(32f * 6.89476f, old.kpa[3]!!, 0.1f)
    }

    @Test fun hyundai_kia_los_dos_acomodos() {
        val p = profile("Hyundai / Kia")
        // Paso 4 (Kona ≤2023): 18 bytes, presión en d[4], d[8]…; 175/5 = 35 psi y 75 − 50 = 25 °C.
        val four = IntArray(18).also { d -> for (i in 0 until 4) { d[4 + i * 4] = 175; d[5 + i * 4] = 75 } }
        val v4 = p.decode(listOf(four))
        assertEquals(35f * 6.89476f, v4.kpa[3]!!, 0.1f)
        assertEquals(25f, v4.tempC[3])
        // Paso 5 (2024+): 21 bytes, presión en d[4], d[9]…
        val five = IntArray(21).also { d -> for (i in 0 until 4) { d[4 + i * 5] = 170; d[5 + i * 5] = 70 } }
        val v5 = p.decode(listOf(five))
        assertEquals(34f * 6.89476f, v5.kpa[3]!!, 0.1f)
        assertEquals(20f, v5.tempC[3])
    }

    @Test fun mazda_presion_y_temperatura() {
        // Ocho peticiones: las cuatro presiones (150/5 = 30 psi) y luego las cuatro temperaturas (80 − 50).
        val replies = List(4) { bytes("22D922", "62D92296") } + List(4) { bytes("22D926", "62D92650") }
        val v = profile("Mazda").decode(replies)
        assertEquals(30f * 6.89476f, v.kpa[0]!!, 0.1f)
        assertEquals(30f, v.tempC[3])
    }

    @Test fun subaru_en_su_orden() {
        // Vienen trasera izq., trasera der., delantera der., delantera izq.: d = [RL, RR, FR, FL].
        val d = bytes("221022", "621022A0A5AAAF")
        val v = profile("Subaru").decode(listOf(d))
        assertEquals(listOf(0xAF, 0xAA, 0xA0, 0xA5).map { it / 5f * 6.894757f }, v.kpa)
    }
}

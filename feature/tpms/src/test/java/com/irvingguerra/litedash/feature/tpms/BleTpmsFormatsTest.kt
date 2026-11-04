package com.irvingguerra.litedash.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTpmsFormatsTest {
    private fun bytes(hex: String): ByteArray = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Como lo da Android: los 2 primeros bytes de los datos completos son la compañía (LE). */
    private fun advert(full: String, name: String? = null, uuids: List<String> = emptyList(), address: String = "AA:BB:CC:DD:EE:FF"): Advert {
        val b = bytes(full)
        val company = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        return Advert(name, address, company, b.copyOfRange(2, b.size), uuids, -60)
    }

    private fun psiToKpa(psi: Float) = psi * KPA_PER_PSI

    @Test
    fun fullPayloadReconstruyeLaCompania() {
        val a = advert("101B0F026AB017")
        assertEquals(0x1B10, a.companyId)
        assertEquals("101B0F026AB017", a.fullPayload!!.hex())
    }

    @Test
    fun brEjemploDeLaDoc() {
        val r = BleTpmsFormats.decode(advert("101B0F026AB017", name = "BR"), 1000L)!!
        assertEquals("BR", r.family)
        assertEquals(2.7f, r.batteryVolts!!, 0.001f)
        assertEquals(15f, r.tempC!!, 0f)
        assertEquals(psiToKpa(47.3f), r.pressureKpa!!, 0.1f)
        assertEquals("AA:BB:CC:DD:EE:FF", r.sensorId)
        assertEquals(1000L, r.atMillis)
        assertFalse(r.lowBattery)
    }

    @Test
    fun brPorUuidSinNombreYVarianteKy() {
        val byUuid = BleTpmsFormats.decode(advert("101B0F026AB017", uuids = listOf("27a5")), 0L)!!
        assertEquals(psiToKpa(47.3f), byUuid.pressureKpa!!, 0.1f)
        val ky = BleTpmsFormats.decode(advert("101B0F026AB017", name = "KY"), 0L)!!
        assertEquals("KY", ky.family)
        assertEquals(psiToKpa(47.2f), ky.pressureKpa!!, 0.1f)
    }

    @Test
    fun brTemperaturaNegativaYPonchada() {
        // −5 °C (0xFB), 14.5 psi absolutos = 0 manométrica y pila en 2.4 V (baja).
        val r = BleTpmsFormats.decode(advert("1018FB00910000", name = "BR"), 0L)!!
        assertEquals(-5f, r.tempC!!, 0f)
        assertEquals(0f, r.pressureKpa!!, 0.01f)
        assertTrue(r.lowBattery)
    }

    @Test
    fun sysgrationEjemploDeLaDoc() {
        val r = BleTpmsFormats.decode(advert("000180eacaddeefff46503007c0c00003300", name = "TPMS1_0ACADD"), 0L)!!
        assertEquals("Sysgration", r.family)
        assertEquals(Wheel.FRONT_LEFT, r.reportedWheel)
        assertEquals(222.7f, r.pressureKpa!!, 0.05f) // 2.227 bar
        assertEquals(31.96f, r.tempC!!, 0.001f)
        assertEquals(51, r.batteryPct)
    }

    @Test
    fun bekubeeEjemploDeLaDoc() {
        val r = BleTpmsFormats.decode(advert("AF4500A7002D56A8B4", uuids = listOf("a827")), 0L)!!
        assertEquals("Bekubee", r.family)
        assertEquals(2.97f, r.batteryVolts!!, 0.001f)
        assertEquals(19f, r.tempC!!, 0f)
        assertEquals(67f, r.pressureKpa!!, 0.01f)
    }

    @Test
    fun wodhmieySintetico() {
        // Sin ejemplo en la doc: V 3.0, presión 70 cuentas (+256 por [6]==1) = 326 × 3.144, 30 °C, sin alarma.
        val mfr = bytes("1E 46 55 00 00 00 01 00 00 11 22 33 44 55 66")
        val r = BleTpmsFormats.decode(Advert(null, "11:22:33:44:55:66", 0x00AC, mfr, listOf("fbb0"), -70), 0L)!!
        assertEquals("WODHMIEY", r.family)
        assertEquals(326 * 3.144f, r.pressureKpa!!, 0.01f)
        assertEquals(30f, r.tempC!!, 0f)
        assertEquals(3.0f, r.batteryVolts!!, 0.001f)
        assertFalse(r.leak)
    }

    @Test
    fun aiYTcSinteticos() {
        // 44.7 psi abs (0x01BF) − 14.696 ≈ 30 psi.
        val ai = BleTpmsFormats.decode(advert("001E1901BF", name = "AI-1234"), 0L)!!
        assertEquals("AiTPMS", ai.family)
        assertEquals(psiToKpa(44.7f - 14.696f), ai.pressureKpa!!, 0.1f)
        assertEquals(25f, ai.tempC!!, 0f)
        val tc = BleTpmsFormats.decode(advert("001E1901BF112233445566", uuids = listOf("a828")), 0L)!!
        assertEquals("TC.TPMS", tc.family)
        assertEquals(psiToKpa(44.7f - 14.6f), tc.pressureKpa!!, 0.1f)
    }

    @Test
    fun loDesconocidoNoSeLee() {
        assertNull(BleTpmsFormats.decode(advert("4C000215AABBCCDD"), 0L)) // iBeacon de Apple
        assertNull(BleTpmsFormats.decode(Advert("BR", "AA:BB:CC:DD:EE:FF", null, null, emptyList(), -50), 0L))
        // BR con largo distinto de 7: no es BR.
        assertNull(BleTpmsFormats.decode(advert("101B0F026AB01700", name = "BR"), 0L))
    }

    @Test
    fun uuidCorto() {
        assertEquals("27a5", BleTpmsScanner.shortUuid("000027A5-0000-1000-8000-00805F9B34FB"))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleTpmsScanner.shortUuid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
    }
}

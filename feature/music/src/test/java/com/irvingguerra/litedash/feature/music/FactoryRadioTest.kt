package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FactoryRadioTest {
    @Test
    fun fmInHundredths() = assertEquals("FM 91.1", stationText(am = false, frequency = 9110))

    @Test
    fun fmHighFrequency() = assertEquals("FM 104.5", stationText(am = false, frequency = 10450))

    // NWD: FM en decenas de kHz (9810 = 98.1), igual que las centésimas de MHz de TopWay.
    @Test
    fun nwdFmInTensOfKhz() = assertEquals("FM 98.1", stationText(am = false, frequency = 9810))

    @Test
    fun amInKhz() = assertEquals("AM 1220", stationText(am = true, frequency = 1220))

    @Test
    fun nothingWithoutFrequency() = assertNull(stationText(am = false, frequency = 0))

    // HCN: según el firmware, kHz (87500) o decenas de kHz (8750); AM en kHz. Lo demás (banda 0–3, IsAS 0/1) no es frecuencia.
    @Test
    fun hcnFrequencies() {
        assertEquals("FM 87.5", HcnRadio.hcnFrequency(87_500)?.let { (am, f) -> stationText(am, f) })
        assertEquals("FM 107.9", HcnRadio.hcnFrequency(10_790)?.let { (am, f) -> stationText(am, f) })
        assertEquals("AM 1220", HcnRadio.hcnFrequency(1_220)?.let { (am, f) -> stationText(am, f) })
        for (notFrequency in listOf(0, 1, 3, -1, 200_000)) assertNull(HcnRadio.hcnFrequency(notFrequency))
    }

    // HCN: el contrato se distingue SOLO leyendo: la 19 es la frecuencia en el de 31; en el de 25 es IsAS (0/1) y la
    // frecuencia es la 18 (que en el de 31 es la banda 0–3).
    private fun contract(tries: Int = 1, pauses: IntArray = IntArray(1), reads: (attempt: Int, code: Int) -> Int?): Int {
        var attempt = 0
        return HcnRadio.contractOf(tries, { attempt++; pauses[0]++ }) { code -> reads(attempt, code) }
    }

    @Test
    fun hcnContractOf31() = assertEquals(31, contract { _, code -> mapOf(19 to 87_500, 18 to 2)[code] })

    @Test
    fun hcnContractOf25() = assertEquals(25, contract { _, code -> mapOf(19 to 1, 18 to 9_810)[code] })

    @Test
    fun hcnUnknownContract() {
        // Sin frecuencia creíble, o la pantalla no tiene esas llamadas: ninguno (el perfil se cierra).
        assertEquals(0, contract { _, code -> mapOf(19 to 0, 18 to 3)[code] })
        assertEquals(0, contract { _, _ -> null })
    }

    @Test
    fun hcnJustCreatedRetries() {
        val pauses = IntArray(1)
        // Recién arrancado aún no sintoniza (0); a la segunda ya da la frecuencia.
        assertEquals(31, contract(tries = 3, pauses = pauses) { attempt, code -> if (attempt == 0) 0 else mapOf(19 to 98_100)[code] })
        assertEquals(1, pauses[0])
        val none = IntArray(1)
        assertEquals(0, contract(tries = 3, pauses = none) { _, _ -> 0 })
        assertEquals(2, none[0])
    }
}

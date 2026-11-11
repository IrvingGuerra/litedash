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
}

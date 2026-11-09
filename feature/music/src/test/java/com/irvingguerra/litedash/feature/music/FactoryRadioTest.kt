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
}

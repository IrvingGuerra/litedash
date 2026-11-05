package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopWayRadioTest {
    @Test
    fun fmInHundredths() = assertEquals("FM 91.1", TopWayRadio.stationText(0, 9110))

    @Test
    fun fmBandOne() = assertEquals("FM 104.5", TopWayRadio.stationText(1, 10450))

    @Test
    fun amInKhz() = assertEquals("AM 1220", TopWayRadio.stationText(2, 1220))

    @Test
    fun nothingWithoutFrequency() = assertNull(TopWayRadio.stationText(0, 0))
}

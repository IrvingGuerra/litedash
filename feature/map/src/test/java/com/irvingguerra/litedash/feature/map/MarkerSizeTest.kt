package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerSizeTest {
    @Test fun bigScreenIsCappedAt240() = assertEquals(240f, markerWidthDp(1200f), 0.01f)

    @Test fun headUnitIsTwentyTwoPercent() = assertEquals(132f, markerWidthDp(600f), 0.01f)

    @Test fun smallGapNeverTakesMoreThanFortyPercent() = assertEquals(80f, markerWidthDp(200f), 0.01f)

    @Test fun phoneGetsTheMinimum() = assertEquals(96f, markerWidthDp(411f), 0.01f)
}

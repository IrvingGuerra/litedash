package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingHeadingTest {
    @Test
    fun theHeadingOnlyCountsWhenFreshAndMoving() {
        // Andando y con una lectura de ahora: cuenta.
        assertEquals(90f, reliableHeading(90f, speedMps = 8f, fixAgeMs = 500)!!, 0f)
        // Detenido (el rumbo guardado es viejo): no cuenta, como en la 1.5.14.
        assertNull(reliableHeading(90f, speedMps = 0f, fixAgeMs = 500))
        assertNull(reliableHeading(90f, speedMps = 2.5f, fixAgeMs = 500))
        // Sin velocidad o con la última ubicación conocida de hace rato: tampoco.
        assertNull(reliableHeading(90f, speedMps = null, fixAgeMs = 500))
        assertNull(reliableHeading(90f, speedMps = 8f, fixAgeMs = 60_000))
        assertNull(reliableHeading(null, speedMps = 8f, fixAgeMs = 500))
    }
}

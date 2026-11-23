package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockPatternTest {
    @Test
    fun markerAfterTheHour() {
        assertEquals("h:mm" to false, withoutMarker("h:mm a"))
    }

    @Test
    fun markerBeforeTheHourInAsianLanguages() {
        // Japonés, coreano y chino: «午後3:36», «오후 3:36».
        assertEquals("h:mm" to true, withoutMarker("a h:mm"))
        assertEquals("K:mm" to true, withoutMarker("aK:mm"))
    }

    @Test
    fun quotedTextKeepsItsA() {
        assertEquals("h:mm 'a las'" to false, withoutMarker("h:mm 'a las' a"))
    }

    @Test
    fun withoutMarkerIn24Hours() {
        assertEquals("HH:mm" to false, withoutMarker("HH:mm"))
    }
}

package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTimeTest {
    @Test
    fun minutesAndHours() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:00", formatTime(-500))
        assertEquals("3:07", formatTime(187_999))
        assertEquals("1:02:07", formatTime(3_727_000))
    }

    @Test
    fun latinDigitsInAnyLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("3:07", formatTime(187_000))
        } finally {
            Locale.setDefault(before)
        }
    }
}

package com.irvingguerra.litedash.feature.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo se busca al celular (PROTOCOLO_LINK.md §6), con un reloj falso. */
class LinkScheduleTest {
    @Test
    fun foregroundBacksOffAndKeepsTrying() {
        val s = LinkSchedule()
        val waits = (1..7).map { s.failed(foreground = true) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun backgroundNeverSchedulesByTime() {
        val s = LinkSchedule()
        assertNull(s.failed(foreground = false))
        assertEquals(0, s.retries)
    }

    @Test
    fun resetsStartOverFast() {
        val s = LinkSchedule()
        repeat(4) { s.failed(foreground = true) }
        s.resetRetries()
        assertEquals(5_000L, s.failed(foreground = true))
        repeat(4) { s.failed(foreground = true) }
        s.restart()
        assertEquals(5_000L, s.failed(foreground = true))
    }

    @Test
    fun minimumGapBetweenAttempts() {
        val s = LinkSchedule()
        assertEquals(0L, s.waitFor(1_000))
        s.attempt(1_000, foreground = true, watching = false)
        assertEquals(LinkSchedule.MIN_GAP_MS, s.waitFor(1_000))
        assertEquals(500L, s.waitFor(2_500))
        assertEquals(0L, s.waitFor(3_000))
    }

    @Test
    fun sweepsFirstThenOnlyWhenAskedOrWithThePanel() {
        val s = LinkSchedule()
        assertTrue(s.attempt(0, foreground = true, watching = false))
        assertFalse(s.attempt(60_000, foreground = true, watching = false))
        // Con el panel, cada tanto.
        assertTrue(s.attempt(70_000, foreground = true, watching = true))
        assertFalse(s.attempt(75_000, foreground = true, watching = true))
        assertTrue(s.attempt(80_000, foreground = true, watching = true))
        s.restart()
        assertTrue(s.attempt(81_000, foreground = true, watching = false))
    }

    @Test
    fun backgroundKeepsTheSweepForLater() {
        val s = LinkSchedule()
        s.restart()
        assertFalse(s.attempt(0, foreground = false, watching = false))
        assertTrue(s.attempt(5_000, foreground = true, watching = false))
    }

    @Test
    fun networkMemoryIgnoresRepeats() {
        val m = NetworkMemory<String>()
        assertTrue(m.changed("wifi", listOf("192.168.1.5"), listOf("192.168.1.1")))
        // Mismo IPv4 y puerta de enlace en otro orden: no es otra red (llega así con IPv6 o DNS).
        assertFalse(m.changed("wifi", listOf("192.168.1.5"), listOf("192.168.1.1")))
        assertTrue(m.changed("wifi", listOf("192.168.43.10"), listOf("192.168.43.1")))
        m.lost("wifi")
        assertTrue(m.changed("wifi", listOf("192.168.43.10"), listOf("192.168.43.1")))
        assertFalse(m.changed("wifi", listOf("192.168.43.10"), listOf("192.168.43.1")))
        m.clear()
        assertTrue(m.changed("wifi", listOf("192.168.43.10"), listOf("192.168.43.1")))
    }

    @Test
    fun artIsDecodedSmall() {
        assertEquals(1, artSampleSize(320, 320))
        assertEquals(1, artSampleSize(ART_MAX_PX, 100))
        assertEquals(2, artSampleSize(1000, 600))
        assertEquals(8, artSampleSize(4000, 4000))
        assertTrue(4000 / artSampleSize(4000, 3000) <= ART_MAX_PX)
    }
}

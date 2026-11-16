package com.irvingguerra.litedash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Varios avisos de encendido seguidos abren LiteDash una sola vez. */
class BootReceiverTest {
    @Test
    fun neverOpenedOpens() = assertFalse(BootReceiver.repeated(-1L, 5_000L))

    @Test
    fun secondNoticeWithinThirtySecondsIsSkipped() = assertTrue(BootReceiver.repeated(10_000L, 39_999L))

    @Test
    fun laterNoticeOpens() = assertFalse(BootReceiver.repeated(10_000L, 40_000L))

    // Sello del arranque anterior (mayor que el tiempo desde este encendido): hubo reinicio.
    @Test
    fun afterRebootOpens() = assertFalse(BootReceiver.repeated(900_000L, 20_000L))
}

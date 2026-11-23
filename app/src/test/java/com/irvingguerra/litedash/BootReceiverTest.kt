package com.irvingguerra.litedash

import com.irvingguerra.litedash.BootReceiver.Companion.NO_BOOT
import com.irvingguerra.litedash.BootReceiver.Companion.Stamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Varios avisos de encendido seguidos abren LiteDash una sola vez; cada arranque nuevo, otra vez. */
class BootReceiverTest {
    private fun repeated(last: Stamp, now: Stamp, memory: Long = -1L) = BootReceiver.repeated(last, now, memory)

    @Test
    fun neverOpenedOpens() = assertFalse(repeated(Stamp(-1L, NO_BOOT), Stamp(5_000L, 7)))

    @Test
    fun secondNoticeOfTheSameBootIsSkipped() = assertTrue(repeated(Stamp(10_000L, 7), Stamp(39_999L, 7)))

    @Test
    fun laterNoticeOfTheSameBootOpens() = assertFalse(repeated(Stamp(10_000L, 7), Stamp(40_000L, 7)))

    // El caso que se saltaba la mitad de los encendidos: abrió a los 25 s del arranque anterior y hoy avisa a los 31 s.
    @Test
    fun rebootSlightlyLaterOpens() = assertFalse(repeated(Stamp(25_000L, 7), Stamp(31_000L, 8)))

    @Test
    fun rebootSlightlyEarlierOpens() = assertFalse(repeated(Stamp(900_000L, 7), Stamp(20_000L, 8)))

    // Sello de una versión de antes (sin cuenta de arranques): se toma como de otro arranque.
    @Test
    fun oldStampWithoutBootOpens() = assertFalse(repeated(Stamp(25_000L, NO_BOOT), Stamp(31_000L, 8)))

    // Android 6: sin cuenta de arranques solo vale el sello de este proceso.
    @Test
    fun withoutBootCountTheSavedStampIsIgnored() = assertFalse(repeated(Stamp(25_000L, NO_BOOT), Stamp(31_000L, NO_BOOT)))

    @Test
    fun withoutBootCountSecondNoticeOfThisProcessIsSkipped() =
        assertTrue(repeated(Stamp(25_000L, NO_BOOT), Stamp(31_000L, NO_BOOT), memory = 25_000L))
}

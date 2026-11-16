package com.irvingguerra.litedash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitScreenTest {
    private val screen = 2000

    @Test
    fun fullWindowCountsAsLeft() {
        assertTrue(onSide(left = true, x = 0, width = 2000, screen = screen, multiWindow = false))
    }

    @Test
    fun leftHalfInSplitIsLeftAndNotRight() {
        assertTrue(onSide(left = true, x = 0, width = 1000, screen = screen, multiWindow = true))
        assertFalse(onSide(left = false, x = 0, width = 1000, screen = screen, multiWindow = true))
    }

    @Test
    fun fullWidthInSplitModeIsNotYetOnTheLeft() {
        // Ya en modo dividido pero todavía a lo ancho: aún no se acomoda.
        assertFalse(onSide(left = true, x = 0, width = 1900, screen = screen, multiWindow = true))
    }

    @Test
    fun rightHalfIsRight() {
        assertTrue(onSide(left = false, x = 1000, width = 1000, screen = screen, multiWindow = true))
        assertFalse(onSide(left = true, x = 1000, width = 1000, screen = screen, multiWindow = true))
    }

    @Test
    fun zeroScreenDoesNotDivideByZero() {
        // Sin medidas todavía: no truena y no da por acomodada la ventana (se sigue esperando).
        assertFalse(onSide(left = true, x = 0, width = 0, screen = 0, multiWindow = false))
    }
}

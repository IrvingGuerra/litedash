package com.irvingguerra.litedash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo se revisa Pro en la nube y cuándo vence sin red. */
class ProCheckTest {
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val last = 1_000 * day

    @Test
    fun recheckAfterTwelveHours() {
        assertFalse(ProCheck.due(last + 11 * hour, last))
        assertTrue(ProCheck.due(last + 12 * hour, last))
    }

    @Test
    fun clockGoingBackRechecksAtOnce() = assertTrue(ProCheck.due(last - 1, last))

    @Test
    fun expiresAfterThirtyDaysWithoutChecking() {
        assertFalse(ProCheck.expired(last + 30 * day, last))
        assertTrue(ProCheck.expired(last + 30 * day + 1, last))
    }

    @Test
    fun clockGoingBackDoesNotExpire() = assertFalse(ProCheck.expired(last - 365 * day, last))
}

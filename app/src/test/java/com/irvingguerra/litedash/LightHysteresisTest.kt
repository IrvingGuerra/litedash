package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightHysteresisTest {
    @Test
    fun cambiaSoloSiLaLuzDura() {
        val l = LightHysteresis(night = false)
        // Un puente: 3 s de oscuridad no lo vuelven noche.
        assertFalse(l.update(2f, 0))
        assertFalse(l.update(2f, 3_000))
        assertFalse(l.update(500f, 4_000))
        // Un túnel largo sí, después de 15 s.
        assertFalse(l.update(2f, 10_000))
        assertFalse(l.update(2f, 20_000))
        assertTrue(l.update(2f, 25_000))
    }

    @Test
    fun diceCuantoFaltaParaConfirmar() {
        val l = LightHysteresis(night = false)
        assertEquals(null, l.waitMs(0))
        l.update(2f, 1_000)
        assertEquals(15_000L, l.waitMs(1_000))
        assertEquals(5_000L, l.waitMs(11_000))
        // Al cumplirse, la revisión sin luz nueva confirma la noche.
        assertTrue(l.update(2f, 16_000))
        assertEquals(null, l.waitMs(16_000))
    }

    @Test
    fun entreLosDosUmbralesNoCambia() {
        val l = LightHysteresis(night = true)
        assertTrue(l.update(30f, 0))
        assertTrue(l.update(30f, 60_000))
        // Ya con luz clara sostenida, amanece.
        assertTrue(l.update(200f, 61_000))
        assertFalse(l.update(200f, 77_000))
    }
}

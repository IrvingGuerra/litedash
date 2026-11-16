package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué ajustes suenan a los faros y cómo se guarda el aprendido. */
class CarLightsTest {
    @Test
    fun lightNamesMatch() {
        assertTrue(soundsLikeLights("illum_state"))
        assertTrue(soundsLikeLights("car_headlight"))
        assertTrue(soundsLikeLights("ACC_ON"))
    }

    @Test
    fun noiseNeverMatches() {
        // "autofill" trae "ill_"; el brillo lo escribe LiteDash mismo.
        assertFalse(soundsLikeLights("autofill_service"))
        assertFalse(soundsLikeLights("screen_brightness_mode"))
        assertFalse(soundsLikeLights("wifi_night_mode"))
        assertFalse(soundsLikeLights("volume_music"))
    }

    @Test
    fun keyRoundTrip() {
        val key = LightsKey("global", "illum/state")
        assertEquals(key, LightsKey.fromStored(key.stored))
    }

    @Test
    fun badKeysAreNull() {
        assertNull(LightsKey.fromStored(null))
        assertNull(LightsKey.fromStored("sin_tabla"))
        assertNull(LightsKey.fromStored("/illum"))
    }
}

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

    @Test
    fun learnStepSurvivesBeingSaved() {
        val steps = listOf(
            LearnStep.Idle,
            LearnStep.Nothing,
            LearnStep.LightsOff(mapOf("system/illum" to "0", "global/acc_on" to "", "secure/x" to "a/b = c")),
            LearnStep.LightsOff(emptyMap()),
            LearnStep.Choose(listOf("system/illum" to "1", "global/illum" to "2")),
        )
        for (step in steps) assertEquals(step, learnStepFrom(step.toSaved()))
        // Lo que no se entiende regresa al inicio.
        assertEquals(LearnStep.Idle, learnStepFrom(emptyList()))
        assertEquals(LearnStep.Idle, learnStepFrom(listOf("otro")))
    }

    @Test
    fun candidatesShowTheTableOnlyWhenNamesRepeat() {
        assertEquals(
            listOf("illum = 1", "car_mode = 3"),
            candidateLabels(listOf("system/illum" to "1", "global/car_mode" to "3")),
        )
        assertEquals(
            listOf("system/illum = 1", "global/illum = 2", "car_mode = 3"),
            candidateLabels(listOf("system/illum" to "1", "global/illum" to "2", "global/car_mode" to "3")),
        )
    }
}

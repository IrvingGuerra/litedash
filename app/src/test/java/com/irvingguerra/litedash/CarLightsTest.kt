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

    @Test
    fun brightnessIsOfferedOnlyWhenTheScreenMovesIt() {
        val off = mapOf(BRIGHTNESS_KEY to "255", "system/screen_brightness_mode" to "0")
        val on = mapOf(BRIGHTNESS_KEY to "30", "system/screen_brightness_mode" to "0")
        // La pantalla se atenúa con los faros: tope a media distancia.
        assertEquals(BRIGHTNESS_KEY to "max:142", brightnessCandidate(off, on, ownBrightness = false))
        // Al revés (sube con los faros), sin cambio, en automático o si lo escribe LiteDash: no.
        assertEquals(BRIGHTNESS_KEY to "min:142", brightnessCandidate(on, off, ownBrightness = false))
        assertNull(brightnessCandidate(off, off, ownBrightness = false))
        // Un cambio chico es alguien moviendo el brillo, no los faros.
        assertNull(brightnessCandidate(off, off + (BRIGHTNESS_KEY to "250"), ownBrightness = false))
        assertNull(brightnessCandidate(off, on, ownBrightness = true))
        assertNull(brightnessCandidate(off + ("system/screen_brightness_mode" to "1"), on, ownBrightness = false))
        assertNull(brightnessCandidate(emptyMap(), on, ownBrightness = false))
    }

    @Test
    fun onlyTheLimitFormStopsLiteDashFromWritingBrightness() {
        assertTrue(brightnessIsTheSignal(BRIGHTNESS_KEY, "max:142"))
        assertTrue(brightnessIsTheSignal(BRIGHTNESS_KEY, "min:142"))
        // Lo aprendido antes de la 1.5.22 (valor exacto) y cualquier otro ajuste: como estaba.
        assertFalse(brightnessIsTheSignal(BRIGHTNESS_KEY, "30"))
        assertFalse(brightnessIsTheSignal("system/illum_state", "1"))
        assertFalse(brightnessIsTheSignal(null, null))
    }

    @Test
    fun lightsOnByExactValueOrByLimit() {
        // Lo aprendido antes (valor exacto) sigue igual.
        assertTrue(lightsAreOn("1", "1"))
        assertFalse(lightsAreOn("1", "0"))
        assertFalse(lightsAreOn("1", null))
        assertTrue(lightsAreOn("30", "30"))
        // El brillo, por tope: un poco más o menos sigue siendo «prendidas».
        assertTrue(lightsAreOn("max:142", "30"))
        assertTrue(lightsAreOn("max:142", "60"))
        assertFalse(lightsAreOn("max:142", "255"))
        assertFalse(lightsAreOn("max:142", null))
        assertTrue(lightsAreOn("min:142", "200"))
        assertFalse(lightsAreOn("min:142", "30"))
    }
}

package com.irvingguerra.litedash

import android.media.AudioManager
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeKeyTest {
    @Test
    fun byKeyAlwaysPresses() {
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, volumeKey(AudioManager.ADJUST_RAISE, byKey = true, before = 5, after = 5))
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKey(AudioManager.ADJUST_LOWER, byKey = true, before = 5, after = 5))
        assertEquals(KeyEvent.KEYCODE_VOLUME_MUTE, volumeKey(AudioManager.ADJUST_TOGGLE_MUTE, byKey = true, before = 5, after = 5))
    }

    @Test
    fun streamPressesOnlyWhenTheLevelDidNotMove() {
        // Android cambió el nivel: listo, sin tecla.
        assertNull(volumeKey(AudioManager.ADJUST_RAISE, byKey = false, before = 5, after = 6))
        // No se movió (en el tope o la pantalla no deja): la tecla, como el botón físico.
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, volumeKey(AudioManager.ADJUST_RAISE, byKey = false, before = 15, after = 15))
        // Silenciar no mueve el nivel: nunca manda tecla por eso.
        assertNull(volumeKey(AudioManager.ADJUST_TOGGLE_MUTE, byKey = false, before = 5, after = 5))
    }
}

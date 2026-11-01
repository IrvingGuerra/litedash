package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Solo en debug: una sesión de música como la del Bluetooth de muchas pantallas
 * (sonando, sin título ni artista), para probar la pieza Música sin el auto.
 *
 * adb shell am broadcast -n com.irvingguerra.litedash/.TestMediaSession -a com.irvingguerra.litedash.SESION_PRUEBA
 * (con --ez detener true la quita)
 */
class TestMediaSession : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("detener", false)) {
            session?.release()
            session = null
            return
        }
        if (session != null) return
        session = MediaSession(context.applicationContext, "prueba-bluetooth").apply {
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            fun state(s: Int) = setPlaybackState(PlaybackState.Builder().setActions(actions).setState(s, 0, 1f).build())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = state(PlaybackState.STATE_PLAYING)
                override fun onPause() = state(PlaybackState.STATE_PAUSED)
            })
            state(PlaybackState.STATE_PLAYING)
            isActive = true
        }
    }

    private companion object {
        var session: MediaSession? = null
    }
}

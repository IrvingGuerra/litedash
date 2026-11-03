package com.irvingguerra.litedash

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.player.Library
import com.irvingguerra.litedash.feature.player.Player
import com.irvingguerra.litedash.feature.player.PlayerSource
import com.irvingguerra.litedash.feature.player.PlayerStore

/** Lo que se espera a que MediaStore indexe una memoria recién conectada antes de rendirse. */
private const val SCAN_WAIT_MS = 4_000L

/**
 * Une el reproductor propio ([Player]) con la pieza de música: qué fuentes toca según los
 * módulos prendidos, y qué hacer cuando el usuario toca "USB" o "Radio" (seguir con lo
 * último que sonó; si no hay nada, la vista abre la biblioteca).
 */
object OwnPlayer {
    fun wire(context: Context, music: MusicRepository, prefs: Preferences) {
        Player.init(context)
        music.ownSources = sources(prefs)
        music.ownSourceNow = { Player.state.source?.toKind() }
        music.ownPlay = { kind -> play(context, kind) }
        music.ownStop = { Player.stop() }
    }

    /** Al prender o apagar un módulo: la pieza se entera, y si sonaba esa fuente se para. */
    fun refresh(music: MusicRepository, prefs: Preferences) {
        music.ownSources = sources(prefs)
        val now = Player.state.source?.toKind()
        if (now != null && now !in music.ownSources) Player.stop()
    }

    private fun sources(prefs: Preferences): Set<SourceKind> = buildSet {
        if (prefs.usbPlayer) add(SourceKind.USB)
        if (prefs.internetRadio) add(SourceKind.RADIO)
    }

    /** true = ya suena (o va a sonar); false = no hay nada que tocar todavía. */
    private fun play(context: Context, kind: SourceKind): Boolean {
        val source = kind.toPlayer() ?: return false
        if (Player.state.source == source && Player.state.current != null) {
            Player.resume()
            return true
        }
        val store = PlayerStore(context)
        return when (source) {
            PlayerSource.RADIO -> store.lastStation()?.let { Player.play(PlayerSource.RADIO, listOf(it), 0); true } ?: false
            PlayerSource.USB -> {
                if (!Library.hasPermission(context)) return false
                val drive = Library.drives(context).firstOrNull() ?: return false
                // Leer la memoria tarda: en un hilo, y al tener la lista se sigue con lo último que sonó.
                Thread {
                    var tracks = Library.tracks(context, drive)
                    // Recién conectada, Android tarda unos segundos en indexarla.
                    if (tracks.isEmpty()) { Thread.sleep(SCAN_WAIT_MS); tracks = Library.tracks(context, drive) }
                    if (tracks.isEmpty()) return@Thread
                    val last = store.last(PlayerSource.USB)
                    val index = tracks.indexOfFirst { it.key == last }.coerceAtLeast(0)
                    Handler(Looper.getMainLooper()).post { Player.play(PlayerSource.USB, tracks, index) }
                }.start()
                true
            }
        }
    }
}

fun SourceKind.toPlayer(): PlayerSource? = when (this) {
    SourceKind.USB -> PlayerSource.USB
    SourceKind.RADIO -> PlayerSource.RADIO
    SourceKind.BLUETOOTH -> null
}

private fun PlayerSource.toKind(): SourceKind = if (this == PlayerSource.USB) SourceKind.USB else SourceKind.RADIO

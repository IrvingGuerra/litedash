package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.PhoneSong
import com.irvingguerra.litedash.feature.link.PhoneSound
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.Song
import com.irvingguerra.litedash.feature.music.SourceKind

/**
 * La música del celular (LiteDash Connect) entra a la pieza Música y sus botones regresan por el enlace.
 * Con el celular conectado la música de la pantalla pasa a Bluetooth, y si la canción suena solo en la bocina
 * del celular la pieza lo pregunta con [noSound] (no lo afirma: el sonido puede ir por un camino que no se ve).
 */
fun wirePhoneMusic(link: PhoneLink, music: MusicRepository, noSound: () -> String) {
    link.onMusic = { song ->
        val silent = song != null && song.playing && link.phoneSound == PhoneSound.PHONE
        music.fromPhone(song?.toSong(if (silent) noSound() else null), artComing = song != null && song.hasArt && song.art == null)
    }
    music.phoneControl = link::command
    link.onLink = music::phoneLinked
    if (link.connected) music.phoneLinked(true)
    // El enlace ya traía una canción antes de unirlos (LiteDash volvió al frente).
    link.song?.let { link.onMusic?.invoke(it) }
}

private fun PhoneSong.toSong(error: String?) = Song(
    title = title,
    artist = artist,
    app = app,
    artwork = art,
    playing = playing,
    durationMs = durationMs,
    positionMs = positionMs,
    readAt = readAt,
    speed = speed,
    pkg = "",
    seekable = seekable,
    actions = emptyList(),
    source = SourceKind.BLUETOOTH,
    buffering = buffering,
    error = error,
    phone = true,
)

package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.PhoneSong
import com.irvingguerra.litedash.feature.link.PhoneSound
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.Song
import com.irvingguerra.litedash.feature.music.SourceKind

/**
 * La música del celular (LiteDash Connect) entra a la pieza Música y sus botones regresan por el enlace.
 * Mientras el celular suena (y su audio no lo lleva Android Auto ni un cable) la música de la pantalla pasa a
 * Bluetooth; y si suena en el propio celular, la pieza lo dice con [noSound]: el Bluetooth quedó solo para
 * llamadas (se afirma) o solo se ve su bocina (se pregunta: el sonido puede ir por un camino que no se ve).
 */
fun wirePhoneMusic(link: PhoneLink, music: MusicRepository, noSound: (PhoneSound) -> String) {
    // Lo de la pantalla solo se calla cuando el celular de verdad suena; con Android Auto o cable, ellos mandan.
    fun tell() {
        val route = link.phoneSound
        music.phone(link.connected, playing = link.song?.playing == true && route != PhoneSound.CAR && route != PhoneSound.WIRED)
    }
    link.onMusic = { song ->
        val silent = link.phoneSound?.takeIf { song != null && song.playing && (it == PhoneSound.PHONE || it == PhoneSound.CALLS) }
        music.fromPhone(song?.toSong(silent?.let(noSound)), artComing = song != null && song.hasArt && song.art == null)
        tell()
    }
    music.phoneControl = link::command
    link.onLink = { tell() }
    tell()
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

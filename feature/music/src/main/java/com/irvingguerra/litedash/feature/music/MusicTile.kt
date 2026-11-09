package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Tarjeta de música (ver [MediaTile]): lo que suena, con sus controles. Tocarla (fuera de los
 * botones) la amplía con [onExpand]. [revision] cambia al volver a la app, para notar si el
 * usuario acaba de dar el permiso.
 */
@Composable
fun MusicTile(repo: MusicRepository, revision: Int, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val song = repo.song
    MediaTile(
        repo, revision, onExpand, modifier, song, Icons.music, stringResource(R.string.music), stringResource(R.string.music_explain),
        text = { tall -> SongText(repo, song, tall) },
        // Cargando se pinta como pausa: tocarlo cancela la carga.
        controls = { m, compact -> Controls(repo, song?.playing == true, m, loading = song?.buffering == true, compact = compact) },
        needsAccess = repo.needsAccess,
    )
}

/** Título, artista (o lo que esté pasando) y la barra de avance. */
@Composable
private fun SongText(repo: MusicRepository, song: Song?, tall: Boolean) {
    val c = LocalColors.current
    val opening = repo.starting
    if (song == null && opening != null) {
        Label(stringResource(R.string.opening_app, opening), Typography.subtitle, c.accent, maxLines = 1)
        Label(stringResource(R.string.opening_wait), Typography.small, c.textMuted, maxLines = 1)
    } else if (song == null) {
        Label(stringResource(R.string.nothing_playing), if (tall) Typography.title else Typography.subtitle, maxLines = 1)
        Label(idleHint(repo), Typography.small, c.textMuted, maxLines = 1)
    } else {
        SongLines(repo, song, tall)
        if (song.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            Progress(song)
        }
    }
}

/** Sin nada sonando: la memoria USB recién puesta, la app elegida o "pon música". */
@Composable
private fun idleHint(repo: MusicRepository): String {
    // Lo que toca según el método elegido (Archivos, Bluetooth o App).
    return when (repo.mode) {
        MusicMode.FILES -> repo.usbDrive?.let { stringResource(R.string.usb_drive_in, it) } ?: MusicMode.FILES.label()
        MusicMode.BLUETOOTH -> repo.bluetoothDevice ?: stringResource(R.string.bluetooth_connect_phone)
        MusicMode.APP -> repo.preferredName().ifBlank { stringResource(R.string.open_an_app) }
    }
}

/** Barra de avance: se calcula con la hora, un repintado por segundo solo mientras suena. */
@Composable
private fun Progress(song: Song) {
    val pos by rememberPosition(song)
    ProgressTrack((pos.toFloat() / song.durationMs).coerceIn(0f, 1f), 4.dp)
}

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
 * Tarjeta de música (ver [MediaTile]): lo que suena, con sus controles. [revision] cambia al volver
 * a la app, para notar si el usuario acaba de dar el permiso. Con Archivos, play sin nada que sonar
 * abre la pantalla Música en la biblioteca (lo atiende el inicio, ver [OnFilesEmpty]).
 */
@Composable
fun MusicTile(
    repo: MusicRepository,
    revision: Int,
    modifier: Modifier = Modifier,
    artwork: Boolean = true,
    skip: Boolean = true,
) {
    val c = LocalColors.current
    val song = repo.song
    val label = sourceLabel(repo, song)
    MediaTile(
        repo, revision, modifier, song, SoundPiece.MUSIC, Icons.music, stringResource(R.string.music), stringResource(R.string.music_explain),
        label = label,
        text = { tall -> SongText(repo, song, tall, label) },
        // Cargando se pinta como pausa: tocarlo cancela la carga.
        controls = { m, compact ->
            Controls(
                repo, song?.playing == true, m, loading = song?.buffering == true, compact = compact,
                playBackground = c.text, playColor = c.background,
            )
        },
        needsAccess = repo.needsAccess,
        artwork = artwork,
        skip = skip,
        tinted = true,
    )
}

/** De dónde suena, para la etiqueta: la app que suena (o la elegida) o el método; nunca repite el título. */
@Composable
private fun sourceLabel(repo: MusicRepository, song: Song?): String {
    val app = when {
        song == null -> if (repo.mode == MusicMode.APP) repo.preferredName() else ""
        repo.isOwn(song.pkg) -> MusicMode.FILES.label()
        else -> song.app
    }
    return app.takeIf { it.isNotBlank() && it != song?.title } ?: repo.mode.label()
}

/** Título, artista (o lo que esté pasando) y la barra de avance; sin nada, la pista que no repite [label]. */
@Composable
private fun SongText(repo: MusicRepository, song: Song?, tall: Boolean, label: String) {
    val c = LocalColors.current
    val opening = repo.starting
    if (song == null && opening != null) {
        Label(stringResource(R.string.opening_app, opening), Typography.subtitle, c.accent, maxLines = 1)
        Label(stringResource(R.string.opening_wait), Typography.small, c.textMuted, maxLines = 1)
    } else if (song == null) {
        Label(stringResource(R.string.nothing_playing), if (tall) Typography.title else Typography.subtitle, maxLines = 1)
        Label(idleHint(repo).takeIf { it != label }.orEmpty(), Typography.small, c.textMuted, maxLines = 1)
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
    ProgressTrack((pos.toFloat() / song.durationMs).coerceIn(0f, 1f), 3.dp)
}

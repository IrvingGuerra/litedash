package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Tarjeta de música. Se acomoda a su tamaño: chica es una fila con carátula y
 * play (o los tres botones si cabe); con más alto, todo más grande. Tocarla (fuera de los botones) la amplía
 * con [onExpand]. [revision] cambia al volver a la app, para notar si el
 * usuario acaba de dar el permiso.
 */
@Composable
fun MusicTile(repo: MusicRepository, revision: Int, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(repo) {
        repo.use()
        onDispose { repo.release() }
    }
    val permission = remember(revision) { repo.hasPermission().also { if (it) repo.recheck() } }
    Card(modifier.fillMaxSize(), onClick = if (permission) onExpand else null) {
        if (!permission) NoPermission(repo) else Player(repo)
    }
}

@Composable
private fun NoPermission(repo: MusicRepository) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.music, c.accent, 32.dp)
        Column(Modifier.weight(1f)) {
            Label(stringResource(R.string.music), Typography.subtitle, maxLines = 1)
            Label(stringResource(R.string.music_explain), Typography.small, c.textMuted, maxLines = 2)
        }
        Button(stringResource(R.string.enable), { repo.requestPermission() }, primary = true)
    }
}

@Composable
private fun Player(repo: MusicRepository) {
    val song = repo.song
    val playing = song?.playing == true
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val tall = maxHeight > 150.dp
        if (tall && maxWidth < maxHeight * 1.5f) return@BoxWithConstraints Stacked(repo, song, playing)
        // Angosta (la fila de abajo en 7"): solo play/pausa, para que el título se lea.
        val compact = !tall && maxWidth < 400.dp
        // Carátula cuadrada con esquinas, dentro de la tarjeta, si deja lugar al título.
        val art = minOf(maxHeight, 240.dp)
        val withArtwork = maxWidth - art >= if (compact) 150.dp else 220.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (withArtwork) Artwork(song, Modifier.size(art), Radius.inner)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                SongText(repo, song, tall)
                if (tall) {
                    Spacer(Modifier.height(12.dp))
                    Controls(repo, playing)
                }
            }
            if (!tall) Controls(repo, playing, compact = compact)
        }
    }
}

/** Alta y no tan ancha (una columna del tablero): carátula arriba, el resto abajo y centrado. */
@Composable
private fun Stacked(repo: MusicRepository, song: Song?, playing: Boolean) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(song, Modifier.fillMaxWidth().weight(1f), Radius.inner)
        Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp)) { SongText(repo, song, tall = true) }
        Controls(repo, playing, Modifier.padding(top = 8.dp))
    }
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
        Label(song.title, if (tall) Typography.title else Typography.subtitle, maxLines = 1)
        Label(
            // Sin canción (Bluetooth sin datos) el título ya es la app: no repetirla.
            listOf(subtitleFor(repo, song), song.app).filter { it.isNotBlank() && it != song.title }.distinct().joinToString(" · "),
            Typography.small, c.textMuted, maxLines = 1,
        )
        if (song.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            Progress(song)
        }
    }
}

/** Sin nada sonando: la memoria USB recién puesta, la app elegida o "pon música". */
@Composable
private fun idleHint(repo: MusicRepository): String {
    val usb = repo.usbDrive
    return when {
        usb != null -> stringResource(R.string.usb_drive_in, usb)
        else -> repo.preferredName().ifBlank { stringResource(R.string.open_an_app) }
    }
}

/** Barra de avance: se calcula con la hora, un repintado por segundo solo mientras suena. */
@Composable
private fun Progress(song: Song) {
    val pos by rememberPosition(song)
    ProgressTrack((pos.toFloat() / song.durationMs).coerceIn(0f, 1f), 4.dp)
}

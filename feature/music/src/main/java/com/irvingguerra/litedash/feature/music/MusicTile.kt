package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Card
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
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val tall = maxHeight > 150.dp
        if (tall && maxWidth < maxHeight * 1.5f) return@BoxWithConstraints Stacked(repo, song)
        // Angosta (la fila de abajo en 7"): solo play/pausa, para que el título se lea.
        val compact = !tall && maxWidth < 400.dp
        // Carátula cuadrada con esquinas, dentro de la tarjeta, si deja lugar al título.
        val art = minOf(maxHeight, 240.dp)
        val withArtwork = maxWidth - art >= if (compact) 150.dp else 220.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (withArtwork) Artwork(song, Modifier.size(art))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                SongText(repo, song, tall)
                if (tall) {
                    Spacer(Modifier.height(12.dp))
                    Controls(repo, song?.playing == true)
                }
            }
            if (!tall) Controls(repo, song?.playing == true, compact = compact)
        }
    }
}

/** Alta y no tan ancha (una columna del tablero): carátula arriba, el resto abajo y centrado. */
@Composable
private fun Stacked(repo: MusicRepository, song: Song?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(song, Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp)) { SongText(repo, song, tall = true) }
        Controls(repo, song?.playing == true, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Artwork(song: Song?, modifier: Modifier) {
    val c = LocalColors.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        val bmp = song?.artwork
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.music, c.textMuted, (minOf(maxWidth, maxHeight) * 0.4f).coerceAtMost(56.dp))
        }
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
        Label(repo.preferredName().ifBlank { stringResource(R.string.open_an_app) }, Typography.small, c.textMuted, maxLines = 1)
    } else {
        Label(song.title, if (tall) Typography.title else Typography.subtitle, maxLines = 1)
        Label(
            // Sin canción (Bluetooth sin datos) el título ya es la app: no repetirla.
            listOf(song.artist, song.app).filter { it.isNotBlank() && it != song.title }.joinToString(" · ")
                .ifBlank { stringResource(R.string.no_song_info) },
            Typography.small, c.textMuted, maxLines = 1,
        )
        if (song.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            Progress(song)
        }
    }
}

@Composable
private fun Controls(repo: MusicRepository, playing: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = LocalColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!compact) IconButton(Icons.previous, { repo.previous() })
        IconButton(if (playing) Icons.pause else Icons.play, { repo.toggle() }, background = c.text, color = c.background)
        if (!compact) IconButton(Icons.next, { repo.next() })
    }
}

/** Barra de avance: se calcula con la hora, un repintado por segundo solo mientras suena. */
@Composable
private fun Progress(song: Song) {
    val c = LocalColors.current
    val pos by rememberPosition(song)
    val fraction = (pos.toFloat() / song.durationMs).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(2.dp)).background(c.text))
    }
}

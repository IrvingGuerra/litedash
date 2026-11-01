package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.delay

/**
 * Tarjeta de música. Se acomoda a su tamaño: chica es una fila con controles;
 * con más alto suma la carátula grande. Tocarla (fuera de los botones) la amplía
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
    val c = LocalColors.current
    val song = repo.song
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight > 150.dp
        // La carátula (cuadrada, del alto de la tarjeta) solo si deja lugar al título y los botones.
        val withArtwork = maxWidth - maxHeight >= 240.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Carátula pegada a la orilla, del alto de la tarjeta.
            if (withArtwork) Box(
                Modifier.fillMaxHeight().aspectRatio(1f).background(c.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = song?.artwork
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.music, c.textMuted, 28.dp)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                if (song == null) {
                    Label(stringResource(R.string.nothing_playing), Typography.subtitle, maxLines = 1)
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
                if (tall) {
                    Spacer(Modifier.height(12.dp))
                    Controls(repo, song?.playing == true)
                }
            }
            if (!tall) Controls(repo, song?.playing == true, Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun Controls(repo: MusicRepository, playing: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.previous, { repo.previous() })
        IconButton(if (playing) Icons.pause else Icons.play, { repo.toggle() }, background = c.text, color = c.background)
        IconButton(Icons.next, { repo.next() })
    }
}

/** Barra de avance: se calcula con la hora, un repintado por segundo solo mientras suena. */
@Composable
private fun Progress(song: Song) {
    val c = LocalColors.current
    var pos by remember { mutableLongStateOf(song.currentPosition()) }
    LaunchedEffect(song) {
        pos = song.currentPosition()
        while (song.playing) {
            delay(1000)
            pos = song.currentPosition()
        }
    }
    val fraction = (pos.toFloat() / song.durationMs).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(2.dp)).background(c.text))
    }
}

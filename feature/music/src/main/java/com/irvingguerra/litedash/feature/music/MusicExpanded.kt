package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * La música en grande, sin salir de LiteDash: carátula, barra para adelantar,
 * y los botones propios de la app (en Spotify: me gusta, aleatorio, repetir).
 * La app se elige en Ajustes. Ancha pone la carátula a un lado; angosta, arriba.
 */
@Composable
fun MusicExpanded(repo: MusicRepository, close: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(repo) {
        repo.use()
        onDispose { repo.release() }
    }
    val c = LocalColors.current
    val song = repo.song
    Card(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val wide = maxWidth > 520.dp && maxWidth > maxHeight
            // La portada cuadrada, pero sin pasar del 40 % del ancho: a media pantalla (con
            // las piezas al lado) el título y los botones siguen cabiendo.
            val artworkMax = maxWidth * 0.4f
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val app = song?.app ?: repo.preferredName()
                    Label(app.uppercase(), Typography.label, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (song != null || repo.preferred != null) IconButton(Icons.openExternal, { repo.openApp() }, size = 48.dp, background = c.surfaceHigh)
                    IconButton(Icons.close, close, size = 48.dp, background = c.surfaceHigh)
                }
                Spacer(Modifier.height(16.dp))
                when {
                    song == null -> Empty(repo, Modifier.weight(1f).fillMaxWidth())
                    wide -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song, Modifier.widthIn(max = artworkMax).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = false))
                        Spacer(Modifier.width(24.dp))
                        Details(repo, song, Modifier.weight(1f))
                    }
                    else -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(song, Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true))
                        Spacer(Modifier.height(16.dp))
                        Details(repo, song, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Artwork(song: Song, modifier: Modifier) {
    val c = LocalColors.current
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        val bmp = song.artwork
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.music, c.textMuted, 56.dp)
        }
    }
}

@Composable
private fun Details(repo: MusicRepository, song: Song, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier) {
        Label(song.title, Typography.title.copy(fontSize = 26.sp), maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Label(song.artist.ifBlank { stringResource(R.string.no_song_info) }, Typography.subtitle, c.textMuted, maxLines = 1)
        if (song.durationMs > 0) {
            Spacer(Modifier.height(20.dp))
            TimeBar(repo, song)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.previous, { repo.previous() }, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            IconButton(if (song.playing) Icons.pause else Icons.play, { repo.toggle() }, size = 76.dp, background = c.text, color = c.background)
            Spacer(Modifier.width(12.dp))
            IconButton(Icons.next, { repo.next() }, size = 64.dp)
        }
        if (song.actions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (a in song.actions.take(5)) ActionButton(a) { repo.action(a) }
            }
        }
    }
}

/** Botón propio de la app con su ícono (se pinta del color del texto: suelen ser monocromos). */
@Composable
private fun ActionButton(a: MediaAction, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (a.icon != null) {
            Image(a.icon.asImageBitmap(), a.name, Modifier.size(26.dp), colorFilter = ColorFilter.tint(c.textMuted))
        } else {
            Label(a.name.take(2), Typography.small, c.textMuted, 1)
        }
    }
}

/** Barra de avance que se toca o arrastra para adelantar (si la app lo permite). */
@Composable
private fun TimeBar(repo: MusicRepository, song: Song) {
    val c = LocalColors.current
    val pos by rememberPosition(song)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = song.durationMs
    val fraction = dragFraction ?: (pos.toFloat() / duration).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (song.seekable) Modifier
                    .pointerInput(song) {
                        detectTapGestures { o -> repo.seekTo((o.x / size.width * duration).toLong()) }
                    }
                    .pointerInput(song) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { change, d ->
                                change.consume()
                                dragFraction = ((dragFraction ?: 0f) + d / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFraction?.let { repo.seekTo((it * duration).toLong()) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    }
                else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(c.text))
        }
        if (song.seekable) {
            Box(Modifier.offset(maxWidth * fraction - 8.dp).size(16.dp).clip(CircleShape).background(c.text))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Label(formatTime(if (dragFraction != null) (fraction * duration).toLong() else pos), Typography.small, c.textMuted, 1)
        Spacer(Modifier.weight(1f))
        Label(formatTime(duration), Typography.small, c.textMuted, 1)
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Empty(repo: MusicRepository, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.music, c.accent, 56.dp)
        Spacer(Modifier.height(16.dp))
        val opening = repo.starting
        Label(if (opening != null) stringResource(R.string.opening_app, opening) else stringResource(R.string.nothing_playing), Typography.title, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Label(stringResource(if (opening != null) R.string.opening_wait else R.string.resume_explain), Typography.body, c.textMuted, maxLines = 2)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(stringResource(R.string.resume), { repo.resume() }, primary = true)
            // Solo con una app elegida: en "Sistema" no hay una app que abrir.
            val app = repo.preferredName()
            if (app.isNotBlank()) Button(stringResource(R.string.open_app, app), { repo.openApp() })
        }
    }
}

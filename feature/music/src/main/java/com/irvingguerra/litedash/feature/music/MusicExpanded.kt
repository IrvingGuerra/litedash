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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography

/**
 * La música en grande, sin salir de LiteDash: fuentes como en un estéreo (USB, Bluetooth,
 * radio, apps), carátula, barra para adelantar, y los botones propios de la app (en
 * Spotify: me gusta, aleatorio, repetir). Ancha pone la carátula a un lado; angosta, arriba.
 * [onLinkUsb] abre los ajustes de música (para enlazar la app de USB de la pantalla).
 */
@Composable
fun MusicExpanded(repo: MusicRepository, close: () -> Unit, onLinkUsb: () -> Unit, modifier: Modifier = Modifier) {
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
                Spacer(Modifier.height(12.dp))
                SourceRow(repo, song, onLinkUsb)
                Spacer(Modifier.height(12.dp))
                // Sin nada sonando se ve igual, con los controles: play reanuda lo último.
                when {
                    wide -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song, Modifier.widthIn(max = artworkMax).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = false), Radius.card)
                        Spacer(Modifier.width(24.dp))
                        Details(repo, song, Modifier.weight(1f))
                    }
                    else -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(song, Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true), Radius.card)
                        Spacer(Modifier.height(16.dp))
                        Details(repo, song, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Details(repo: MusicRepository, song: Song?, modifier: Modifier) {
    val c = LocalColors.current
    val opening = repo.starting
    Column(modifier) {
        val title = when {
            song != null -> song.title
            opening != null -> stringResource(R.string.opening_app, opening)
            else -> stringResource(R.string.nothing_playing)
        }
        Label(title, Typography.title.copy(fontSize = 26.sp), maxLines = 2)
        Spacer(Modifier.height(4.dp))
        val subtitle = when {
            song != null -> subtitleFor(repo, song)
            opening != null -> stringResource(R.string.opening_wait)
            repo.usbDrive != null -> stringResource(R.string.usb_drive_in, repo.usbDrive.orEmpty())
            else -> stringResource(R.string.resume_explain)
        }
        Label(subtitle, Typography.subtitle, c.textMuted, maxLines = 2)
        if (song != null && song.durationMs > 0) {
            Spacer(Modifier.height(20.dp))
            TimeBar(repo, song)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Controls(repo, song?.playing == true, size = 64.dp, playSize = 76.dp, gap = 12.dp)
        }
        if (song != null && song.actions.isNotEmpty()) {
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
        ProgressTrack(fraction, 6.dp)
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

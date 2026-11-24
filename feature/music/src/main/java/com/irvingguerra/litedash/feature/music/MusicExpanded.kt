package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import java.util.Locale

/**
 * La música en grande, sin salir de LiteDash: arriba de dónde suena (Archivos, Bluetooth o App, uno
 * solo; ahí mismo se cambia), carátula, barra para adelantar, y los botones propios de la app (en Spotify: me
 * gusta, aleatorio, repetir). Ancha pone la carátula a un lado; angosta, arriba. [browser] es la
 * biblioteca de Archivos del reproductor propio: se abre con el botón de lista o al dar play sin
 * nada que sonar; recibe qué hacer cuando ya se eligió algo.
 */
@Composable
fun MusicExpanded(
    repo: MusicRepository,
    modifier: Modifier = Modifier,
    /** Abre ya en la biblioteca (se llegó aquí por play sin nada que sonar). */
    startBrowsing: Boolean = false,
    browser: @Composable (onPicked: () -> Unit) -> Unit,
) {
    val song = repo.song
    val files = repo.mode == MusicMode.FILES
    // La biblioteca de Archivos abierta; si no, lo que suena.
    var browsing by remember { mutableStateOf(startBrowsing && files) }
    // Con Archivos, play sin nada que tocar abre la biblioteca.
    OnFilesEmpty(repo, files, remember { { browsing = true } })
    PhoneOnlySound(repo)
    ExpandedCard(repo, SoundPiece.MUSIC, modifier, tint = song) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Lo que sobra es del selector; los botones, siempre a la derecha.
            Box(Modifier.weight(1f)) { ModePicker(repo) }
            if (files) HeaderButton(if (browsing) Icons.music else Icons.list) { browsing = !browsing }
            val app = song?.pkg?.ifEmpty { null } ?: repo.modeApp
            if (app != null && !repo.isOwn(app)) HeaderButton(Icons.openExternal) { repo.openApp() }
        }
        Spacer(Modifier.height(16.dp))
        repo.soundCheckFor(SoundPiece.MUSIC)?.let { SoundCheckPrompt(repo, it, Modifier.fillMaxWidth().padding(bottom = 16.dp)) }
        if (browsing) {
            browser { browsing = false }
            return@ExpandedCard
        }
        // Sin nada sonando se ve igual, con los controles: play reanuda lo último.
        // Título (~120), barra (~50), botones (~92) y, si la app trae, sus botones propios (~64).
        val idle = when {
            repo.phoneOnly -> stringResource(R.string.music_from_phone)
            else -> repo.usbDrive?.let { stringResource(R.string.usb_drive_in, it) } ?: stringResource(R.string.resume_explain)
        }
        NowPlaying(
            song, Icons.music,
            detailsHeight = if (song?.actions.isNullOrEmpty()) 262.dp else 326.dp,
            title = { m, compact ->
                // Sin ▶: tocar el aviso vuelve a pedir la entrada de audio de la pantalla.
                val tap = if (song == null && repo.phoneOnly) m.clickable { repo.askBluetoothSound() } else m
                ExpandedTitle(repo, song, repo.starting, stringResource(R.string.nothing_playing), idle, tap, compact)
            },
            // Deslizar solo sobre la carátula: la barra de avance ya se arrastra.
            onNext = { repo.next() }.takeIf { repo.soundCheckFor(SoundPiece.MUSIC) == null },
            onPrevious = { repo.previous() },
        ) { PlayerControls(repo, song, it) }
    }
}

/** La barra para adelantar (si la canción tiene duración), los controles y los botones propios de la app. */
@Composable
private fun ColumnScope.PlayerControls(repo: MusicRepository, song: Song?, fit: ControlsFit) {
    val compact = fit != ControlsFit.FULL
    val wide = LocalWide.current
    // Ancha, los botones propios de la app (me gusta, aleatorio…) van arriba de la barra, como en los reproductores.
    if (wide && song != null && song.actions.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppActions(repo, song)
    }
    if (fit != ControlsFit.MINI && song != null && song.durationMs > 0) {
        Spacer(Modifier.height(if (compact) 10.dp else if (wide) 24.dp else 20.dp))
        TimeBar(repo, song)
    }
    Spacer(Modifier.height(if (compact) 8.dp else if (wide) 20.dp else 16.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val m = fit.sizes(wide)
        // Archivos: el aleatorio a la derecha de los controles, marcado cuando está prendido (pedido 7743FE).
        if (repo.mode == MusicMode.FILES && OwnShuffle.available) {
            val c = LocalColors.current
            IconButton(
                ShuffleIcon, { OwnShuffle.toggle() }, Modifier.align(Alignment.CenterEnd), size = m.size,
                color = if (OwnShuffle.on) c.primary else c.textMuted,
                label = stringResource(R.string.music_shuffle),
            )
        }
        // Sin por dónde darle play a la app de Bluetooth de marca: sin botones (lo dice el texto), con la salida a Connect.
        if (repo.phoneOnly) PhoneLinkButton(repo) else Controls(repo, song?.playing == true, loading = song?.buffering == true, size = m.size, playSize = m.play, gap = m.gap)
    }
    if (!wide && fit != ControlsFit.MINI && song != null && song.actions.isNotEmpty()) {
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        AppActions(repo, song)
    }
}

/** Flechas cruzadas (aleatorio), con el mismo trazo que los íconos de la app. */
private val ShuffleIcon: ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    for (d in listOf("M18 4l3 3l-3 3", "M18 20l3 -3l-3 -3", "M3 7h3a5 5 0 0 1 5 5a5 5 0 0 0 5 5h5", "M21 7h-5a4.98 4.98 0 0 0 -3 1m-4 8a4.98 4.98 0 0 1 -3 1h-3")) {
        addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

/** Los botones propios de la app (en Spotify: me gusta, aleatorio, repetir). */
@Composable
private fun AppActions(repo: MusicRepository, song: Song) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (a in song.actions.take(5)) ActionButton(a) { repo.action(a) }
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
    // Los gestos se reinician solo al cambiar de canción: cada play, pausa o posición trae otra instancia de [song]
    // y, con ella de llave, un arrastre se cortaba cuando la app publicaba su estado (Spotify al cargar).
    val gestureKey = Triple(song.pkg, song.title, duration)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (song.seekable) Modifier
                    .pointerInput(gestureKey) {
                        detectTapGestures { o -> repo.seekTo((o.x / size.width * duration).toLong()) }
                    }
                    .pointerInput(gestureKey) {
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
            Box(Modifier.offset(maxWidth * fraction - 8.dp).size(16.dp).clip(CircleShape).background(c.primary))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Label(formatTime(if (dragFraction != null) (fraction * duration).toLong() else pos), Typography.small, c.textMuted, 1)
        Spacer(Modifier.weight(1f))
        Label(formatTime(duration), Typography.small, c.textMuted, 1)
    }
}

/** "3:07" o "1:02:07"; con dígitos latinos en todos los idiomas (como los muestran los reproductores). */
internal fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

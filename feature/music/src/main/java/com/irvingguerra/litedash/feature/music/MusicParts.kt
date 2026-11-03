package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Colors
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

// Partes que comparten la pieza de música y la música en grande.

/** Carátula cuadrada con esquinas [radius]; sin carátula, el ícono de música. */
@Composable
internal fun Artwork(song: Song?, modifier: Modifier, radius: Dp) {
    val c = LocalColors.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(radius)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        val bmp = song?.artwork
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.music, c.textMuted, (minOf(maxWidth, maxHeight) * 0.4f).coerceAtMost(56.dp))
        }
    }
}

/** Anterior, play/pausa y siguiente; [compact] deja solo play/pausa. */
@Composable
internal fun Controls(
    repo: MusicRepository,
    playing: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    size: Dp = 56.dp,
    playSize: Dp = 56.dp,
    gap: Dp = 0.dp,
) {
    val c = LocalColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!compact) IconButton(Icons.previous, { repo.previous() }, size = size)
        if (!compact) Spacer(Modifier.width(gap))
        IconButton(if (playing) Icons.pause else Icons.play, { repo.toggle() }, size = playSize, background = c.text, color = c.background)
        if (!compact) Spacer(Modifier.width(gap))
        if (!compact) IconButton(Icons.next, { repo.next() }, size = size)
    }
}

/** La pista de avance: fondo y la parte recorrida ([fraction] de 0 a 1). */
@Composable
internal fun ProgressTrack(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(c.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(c.text))
    }
}

/**
 * Qué decir debajo del título cuando la app no manda artista: en Bluetooth, el celular
 * conectado (o que lo conecte); en lo demás, "sin datos".
 */
@Composable
internal fun subtitleFor(repo: MusicRepository, song: Song): String = when {
    song.artist.isNotBlank() -> song.artist
    song.source == SourceKind.BLUETOOTH -> repo.bluetoothDevice ?: stringResource(R.string.bluetooth_connect_phone)
    else -> stringResource(R.string.no_song_info)
}

/** Nombre corto de la fuente para los botones. */
@Composable
fun SourceKind.label(): String = stringResource(
    when (this) {
        SourceKind.USB -> R.string.source_usb
        SourceKind.BLUETOOTH -> R.string.source_bluetooth
        SourceKind.RADIO -> R.string.source_radio
    },
)

/**
 * Fila de fuentes como en un estéreo: Sistema, las fuentes enlazadas (USB, Bluetooth,
 * radio), la app elegida y la que suena. Tocar una la pone a sonar. Vacía si no hay nada
 * que elegir (sin fuentes ni app). [onLinkUsb] abre Ajustes cuando hay una memoria puesta
 * pero ninguna app enlazada a USB.
 */
@Composable
internal fun SourceRow(repo: MusicRepository, song: Song?, onLinkUsb: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val current = song?.pkg ?: repo.preferred
    val linked = SourceKind.entries.mapNotNull { k -> repo.sourceApps[k]?.let { k to it } }
    val apps = listOfNotNull(repo.preferred, song?.pkg).distinct().filter { pkg -> linked.none { it.second == pkg } }
    val usbHint = repo.usbDrive != null && SourceKind.USB !in repo.sourceApps
    if (linked.isEmpty() && apps.isEmpty() && !usbHint) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(stringResource(R.string.source_system), repo.preferred == null, c) { repo.choose(null) }
        for ((kind, pkg) in linked) Chip(kind.label(), current == pkg, c) { repo.switchTo(pkg) }
        if (usbHint) Chip(stringResource(R.string.source_usb), false, c, onClick = onLinkUsb)
        for (pkg in apps) Chip(repo.appName(pkg), current == pkg, c) { repo.switchTo(pkg) }
    }
}

/** Botón de fuente, redondo y chico (la fila cabe a media pantalla). */
@Composable
private fun Chip(text: String, selected: Boolean, c: Colors, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.text else c.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Label(text, Typography.small.copy(fontWeight = FontWeight.SemiBold), if (selected) c.background else c.text, 1) }
}

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Tarjeta de radio: la FM de la pantalla (la app de la marca, enlazada en Ajustes) o la
 * radio por internet de LiteDash. Se acomoda a su tamaño como la de música: chica es una
 * fila con carátula y play; alta, carátula arriba y controles abajo. Tocarla la amplía con
 * [onExpand]; play sin nada con qué sonar también (la vista en grande explica qué falta).
 * [revision] cambia al volver a la app, para notar si el usuario acaba de dar el permiso.
 */
@Composable
fun RadioTile(repo: MusicRepository, revision: Int, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(repo) {
        repo.use()
        onDispose { repo.release() }
    }
    val permission = remember(revision) { repo.hasPermission().also { if (it) repo.recheck() } }
    Card(modifier.fillMaxSize(), onClick = if (permission) onExpand else null) {
        if (!permission) NoPermission(repo) else Player(repo, onExpand)
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
        Icon(Icons.radio, c.accent, 32.dp)
        Column(Modifier.weight(1f)) {
            Label(stringResource(R.string.radio), Typography.subtitle, maxLines = 1)
            Label(stringResource(R.string.radio_explain), Typography.small, c.textMuted, maxLines = 2)
        }
        Button(stringResource(R.string.enable), { repo.requestPermission() }, primary = true)
    }
}

@Composable
private fun Player(repo: MusicRepository, onExpand: () -> Unit) {
    val song = repo.radio.song
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val tall = maxHeight > 150.dp
        if (tall && maxWidth < maxHeight * 1.5f) return@BoxWithConstraints Stacked(repo, song, onExpand)
        // Angosta: solo play/pausa, para que la estación se lea.
        val compact = !tall && maxWidth < 400.dp
        val art = minOf(maxHeight, 240.dp)
        val withArtwork = maxWidth - art >= if (compact) 150.dp else 220.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (withArtwork) Artwork(song, Modifier.size(art), Radius.inner, Icons.radio)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                StationText(repo, song, tall)
                if (tall) {
                    Spacer(Modifier.height(12.dp))
                    RadioControls(repo, song, onExpand)
                }
            }
            if (!tall) RadioControls(repo, song, onExpand, compact = compact)
        }
    }
}

/** Alta y no tan ancha (una columna del tablero): carátula arriba, el resto abajo y centrado. */
@Composable
private fun Stacked(repo: MusicRepository, song: Song?, onExpand: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(song, Modifier.fillMaxWidth().weight(1f), Radius.inner, Icons.radio)
        Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp)) { StationText(repo, song, tall = true) }
        RadioControls(repo, song, onExpand, Modifier.padding(top = 8.dp))
    }
}

/** La estación y lo que dice (o, sin nada sonando, con qué va a sonar). */
@Composable
private fun StationText(repo: MusicRepository, song: Song?, tall: Boolean) {
    val c = LocalColors.current
    if (song == null) {
        Label(stringResource(R.string.radio), if (tall) Typography.title else Typography.subtitle, maxLines = 1)
        val opening = repo.radio.starting
        Label(
            if (opening != null) stringResource(R.string.opening_app, opening) else radioHint(repo),
            Typography.small, if (opening != null) c.accent else c.textMuted, maxLines = 1,
        )
        return
    }
    Label(song.title, if (tall) Typography.title else Typography.subtitle, maxLines = 1)
    val error = song.error
    when {
        error != null -> Label(error, Typography.small, c.danger, maxLines = 1)
        song.buffering -> Label(stringResource(R.string.player_connecting), Typography.small, c.textMuted, maxLines = 1)
        else -> Label(
            // La radio por internet no agrega "LiteDash"; la FM sí dice su app.
            listOf(subtitleFor(repo, song), if (repo.isOwn(song.pkg)) "" else song.app)
                .filter { it.isNotBlank() && it != song.title }.distinct().joinToString(" · "),
            Typography.small, c.textMuted, maxLines = 1,
        )
    }
}

/** Sin nada sonando: la FM que va a despertar, "Por internet" o que enlace una. */
@Composable
internal fun radioHint(repo: MusicRepository): String {
    val pkg = repo.appFor(SourceKind.RADIO)
    return when {
        pkg == null -> stringResource(R.string.radio_unlinked)
        repo.isOwn(pkg) -> stringResource(R.string.radio_internet)
        else -> repo.sourceName(SourceKind.RADIO).ifBlank { stringResource(R.string.source_radio) }
    }
}

/**
 * Los controles de la radio. Sin sesión, solo play: despierta la FM o la de internet y,
 * si no hay con qué, llama [onIdle] (la pieza se amplía y explica qué falta).
 */
@Composable
internal fun RadioControls(
    repo: MusicRepository,
    song: Song?,
    onIdle: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    size: Dp = 56.dp,
    playSize: Dp = 56.dp,
    gap: Dp = 0.dp,
) {
    if (song == null) {
        val c = LocalColors.current
        IconButton(Icons.play, { if (!repo.resumeRadio()) onIdle() }, modifier, size = playSize, background = c.text, color = c.background)
        return
    }
    Controls(repo, song.playing, modifier, loading = song.buffering, compact = compact, size = size, playSize = playSize, gap = gap, lens = repo.radio)
}

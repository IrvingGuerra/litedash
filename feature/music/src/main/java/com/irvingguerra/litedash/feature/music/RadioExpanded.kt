package com.irvingguerra.litedash.feature.music

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography

/**
 * La radio en grande: la FM de la pantalla o la radio por internet de LiteDash (si hay
 * las dos, se escoge arriba). Carátula, estación y controles, sin barra de avance (la
 * radio no tiene). [browser] es la lista de estaciones por internet (null = solo FM): se
 * abre con el botón de lista, o sola si la pieza está en internet y no suena nada.
 * [onSettings] abre los ajustes para enlazar la radio de la pantalla.
 */
@Composable
fun RadioExpanded(
    repo: MusicRepository,
    close: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    browser: (@Composable (onPicked: () -> Unit) -> Unit)? = null,
) {
    DisposableEffect(repo) {
        repo.use()
        onDispose { repo.release() }
    }
    val c = LocalColors.current
    val song = repo.radio.song
    val target = repo.appFor(SourceKind.RADIO)
    // Con qué suena la pieza: lo que ya suena manda; si no, lo que está escogido.
    val internet = when {
        repo.radioHasBoth -> repo.radioInternet
        song != null -> repo.isOwn(song.pkg)
        else -> target != null && repo.isOwn(target)
    }
    val canBrowse = browser != null && internet
    // La lista de estaciones; al entrar (o al cambiar a internet) sin nada sonando, se abre sola.
    var browsing by remember(canBrowse) { mutableStateOf(canBrowse && song == null && repo.radio.starting == null) }
    Card(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val wide = maxWidth > 520.dp && maxWidth > maxHeight
            val artworkMax = maxWidth * 0.4f
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(stringResource(R.string.radio).uppercase(), Typography.label, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (canBrowse) {
                        IconButton(if (browsing) Icons.radio else Icons.list, { browsing = !browsing }, size = 48.dp, background = c.surfaceHigh)
                    }
                    if (!internet && repo.sourceApps[SourceKind.RADIO] != null) {
                        IconButton(Icons.openExternal, { repo.openRadioApp() }, size = 48.dp, background = c.surfaceHigh)
                    }
                    IconButton(Icons.close, close, size = 48.dp, background = c.surfaceHigh)
                }
                if (repo.radioHasBoth) {
                    Spacer(Modifier.height(12.dp))
                    Segmented(
                        listOf(false to stringResource(R.string.radio_fm), true to stringResource(R.string.radio_internet)),
                        repo.radioInternet,
                        { repo.radioInternet = it },
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (browser != null && canBrowse && browsing) {
                    browser { browsing = false }
                    return@Column
                }
                if (song == null && target == null) {
                    NoRadio(onSettings, Modifier.weight(1f).fillMaxWidth())
                    return@Column
                }
                val onIdle: () -> Unit = { if (canBrowse) browsing = true }
                when {
                    wide -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song, Modifier.widthIn(max = artworkMax).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = false), Radius.card, Icons.radio)
                        Spacer(Modifier.width(24.dp))
                        Details(repo, song, onIdle, Modifier.weight(1f))
                    }
                    else -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(song, Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true), Radius.card, Icons.radio)
                        Spacer(Modifier.height(16.dp))
                        Details(repo, song, onIdle, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Sin FM enlazada ni radio por internet: qué hacer y el botón a Ajustes. */
@Composable
private fun NoRadio(onSettings: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.radio, c.textMuted, 56.dp)
        Spacer(Modifier.height(16.dp))
        Label(stringResource(R.string.radio_unlinked), Typography.subtitle.copy(textAlign = TextAlign.Center), c.textMuted, maxLines = 3)
        Spacer(Modifier.height(20.dp))
        Button(stringResource(R.string.radio_settings), onSettings, primary = true)
    }
}

@Composable
private fun Details(repo: MusicRepository, song: Song?, onIdle: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val opening = repo.radio.starting
    Column(modifier) {
        val title = when {
            song != null -> song.title
            opening != null -> stringResource(R.string.opening_app, opening)
            else -> stringResource(R.string.radio)
        }
        // Alto de dos líneas reservado: con un nombre largo los botones no bajan.
        Box(Modifier.heightIn(min = 68.dp), contentAlignment = Alignment.BottomStart) {
            Label(title, Typography.title.copy(fontSize = 26.sp), maxLines = 2)
        }
        Spacer(Modifier.height(4.dp))
        val error = song?.error
        val subtitle = when {
            error != null -> error
            song?.buffering == true -> stringResource(R.string.player_connecting)
            song != null -> subtitleFor(repo, song)
            opening != null -> stringResource(R.string.opening_wait)
            else -> radioHint(repo)
        }
        // También dos líneas reservadas: los botones no se mueven al cambiar de estación.
        Box(Modifier.heightIn(min = 48.dp)) {
            Label(subtitle, Typography.subtitle, if (error != null) c.danger else c.textMuted, maxLines = 2)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RadioControls(repo, song, onIdle, size = 64.dp, playSize = 76.dp, gap = 12.dp)
        }
    }
}

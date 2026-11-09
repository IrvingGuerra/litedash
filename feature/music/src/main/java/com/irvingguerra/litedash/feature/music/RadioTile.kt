package com.irvingguerra.litedash.feature.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Tarjeta de radio (ver [MediaTile]): la FM de la pantalla (la app de la marca, enlazada en
 * Ajustes) o la radio por internet de LiteDash. Play sin nada con qué sonar llama [onIdle]
 * (abre los ajustes de la radio para enlazarla). [revision] cambia al
 * volver a la app, para notar si el usuario acaba de dar el permiso.
 */
@Composable
fun RadioTile(repo: MusicRepository, revision: Int, onIdle: () -> Unit, modifier: Modifier = Modifier) {
    val song = repo.radio.song
    MediaTile(
        repo, revision, modifier, song, Icons.radio, stringResource(R.string.radio), stringResource(R.string.radio_explain),
        text = { tall -> StationText(repo, song, tall) },
        controls = { m, compact -> RadioControls(repo, song, onIdle, m, compact = compact) },
        // La radio por internet es el reproductor propio: su sesión se lee directo, sin el acceso a notificaciones.
        needsAccess = !repo.radioInternet,
    )
}

/** La estación y lo que dice (o, sin nada sonando, con qué va a sonar). */
@Composable
private fun StationText(repo: MusicRepository, song: Song?, tall: Boolean) {
    val c = LocalColors.current
    if (song != null) return SongLines(repo, song, tall)
    Label(stringResource(R.string.radio), if (tall) Typography.title else Typography.subtitle, maxLines = 1)
    val opening = repo.radio.starting
    Label(
        if (opening != null) stringResource(R.string.opening_app, opening) else radioHint(repo),
        Typography.small, if (opening != null) c.accent else c.textMuted, maxLines = 1,
    )
}

/** Sin nada sonando: la FM que va a despertar, "Por internet" o que enlace una. */
@Composable
internal fun radioHint(repo: MusicRepository): String {
    val pkg = repo.radioApp
    return when {
        pkg == null -> stringResource(R.string.radio_unlinked)
        repo.isOwn(pkg) -> stringResource(R.string.radio_internet)
        else -> repo.sourceName(SourceKind.RADIO).ifBlank { stringResource(R.string.source_radio) }
    }
}

/**
 * Los controles de la radio. Sin sesión, solo play: despierta la FM o la de internet y,
 * si no hay con qué, llama [onIdle] (la pieza se amplía y explica qué falta). Una FM de la
 * pantalla sin sesión pero con receptor lleva los tres botones: van como teclas del volante
 * (play/pausa, estación siguiente y anterior), sin abrir la app.
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
    val keys = remember(repo.radioApp) { repo.radioHasKeys() }
    if (song == null && keys) {
        return Controls(repo, false, modifier, compact = compact, size = size, playSize = playSize, gap = gap, lens = repo.radio)
    }
    if (song == null) {
        val c = LocalColors.current
        IconButton(Icons.play, { repo.resumeRadio(onIdle) }, modifier, size = playSize, background = c.primary, color = c.onPrimary)
        return
    }
    Controls(repo, song.playing, modifier, loading = song.buffering, compact = compact, size = size, playSize = playSize, gap = gap, lens = repo.radio)
}

package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.fitFont

/**
 * Tarjeta de radio (ver [MediaTile]): la FM de la pantalla (la app de la marca, enlazada en
 * Ajustes) o la radio por internet de LiteDash. Play sin nada con qué sonar llama [onIdle]
 * (abre los ajustes de la radio para enlazarla). [revision] cambia al
 * volver a la app, para notar si el usuario acaba de dar el permiso. Las estaciones por internet no se
 * eligen aquí: [onIdle] lleva a la pantalla Música (o a los ajustes, sin radio enlazada).
 */
@Composable
fun RadioTile(
    repo: MusicRepository,
    revision: Int,
    onIdle: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: Boolean = true,
    skip: Boolean = true,
) {
    val c = LocalColors.current
    val song = repo.radio.song
    MediaTile(
        repo, revision, modifier, song, SoundPiece.RADIO, Icons.radio, stringResource(R.string.radio), stringResource(R.string.radio_explain),
        // Con estación, arriba si es la FM de la pantalla o la de internet.
        label = song?.let { stringResource(if (repo.radioInternet) R.string.radio else R.string.radio_fm_label) },
        text = { tall -> StationText(repo, song, tall) },
        controls = { m, compact ->
            RadioControls(
                repo, song, onIdle, m, compact = compact, gap = 8.dp,
                playBackground = c.text, playColor = c.background, skipBackground = c.surfaceHigh,
            )
        },
        // La radio por internet es el reproductor propio: su sesión se lee directo, sin el acceso a notificaciones.
        needsAccess = !repo.radioInternet,
        artwork = artwork,
        skip = skip,
    )
}

/** La estación en grande y lo que dice (o, sin nada sonando, con qué va a sonar). */
@Composable
private fun StationText(repo: MusicRepository, song: Song?, tall: Boolean) {
    val c = LocalColors.current
    if (song != null) {
        // La estación del tamaño que quepa a lo ancho y en lo alto que dejan la etiqueta, la línea de abajo
        // y, si va alta, los botones; nunca se corta.
        BoxWithConstraints {
            val free = maxHeight - STATION_RESERVED - if (tall) STATION_CONTROLS else 0.dp
            // En sp sin la escala de letra del sistema: el tamaño ya es lo que cabe.
            val size = fitFont(song.title, maxWidth, free, heightShare = 1 / 1.2f, min = 18f, max = if (tall) 48f else 40f)
            Label(song.title, Typography.title.copy(fontSize = size.sp, lineHeight = (size * 1.2f).sp), maxLines = 1)
        }
        return SongDetail(repo, song)
    }
    Label(stringResource(R.string.radio), if (tall) Typography.title else Typography.subtitle, maxLines = 1)
    val opening = repo.radio.starting
    Label(
        if (opening != null) stringResource(R.string.opening_app, opening) else radioHint(repo),
        Typography.small, if (opening != null) c.accent else c.textMuted, maxLines = 1,
    )
}

/** La etiqueta y la línea de abajo, y los botones bajo el texto (alta), que no caben en el número grande. */
private val STATION_RESERVED = 36.dp
private val STATION_CONTROLS = 68.dp

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
 * (play/pausa, estación siguiente y anterior), sin abrir la app. Los colores, como en [Controls].
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
    playBackground: Color = LocalColors.current.primary,
    playColor: Color = LocalColors.current.onPrimary,
    skipBackground: Color = Color.Transparent,
) {
    val keys = remember(repo.radioApp) { repo.radioHasKeys() }
    if (song == null && !keys) {
        IconButton(Icons.play, { repo.resumeRadio(onIdle) }, modifier, size = playSize, background = playBackground, color = playColor)
        return
    }
    Controls(
        repo, song?.playing == true, modifier, loading = song?.buffering == true, compact = compact, size = size, playSize = playSize,
        gap = gap, lens = repo.radio, playBackground = playBackground, playColor = playColor, skipBackground = skipBackground,
    )
}

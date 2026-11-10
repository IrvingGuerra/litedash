package com.irvingguerra.litedash.feature.music

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography

// Partes que comparten la pieza de música y la música en grande.

/** Carátula cuadrada con esquinas [radius]; sin carátula, [icon] (la música o la radio). */
@Composable
internal fun Artwork(song: Song?, modifier: Modifier, radius: Dp, icon: ImageVector = Icons.music) {
    val c = LocalColors.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(radius)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        val bmp = song?.artwork
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, c.textMuted, (minOf(maxWidth, maxHeight) * 0.4f).coerceAtMost(56.dp))
        }
    }
}

/**
 * Anterior, play/pausa y siguiente; [compact] deja solo play/pausa. Con [loading]
 * (conectando) se ve pausa, porque tocarlo cancela la carga. [lens] es la vista que
 * controla: la música (por omisión) o la radio. [playBackground] y [playColor] pintan el
 * círculo de play; [skipBackground], el de anterior y siguiente.
 */
@Composable
internal fun Controls(
    repo: MusicRepository,
    playing: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    compact: Boolean = false,
    size: Dp = 56.dp,
    playSize: Dp = 56.dp,
    gap: Dp = 0.dp,
    lens: MusicRepository.Lens = repo.music,
    playBackground: Color = LocalColors.current.primary,
    playColor: Color = LocalColors.current.onPrimary,
    skipBackground: Color = Color.Transparent,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!compact) IconButton(Icons.previous, { lens.previous() }, size = size, background = skipBackground)
        if (!compact) Spacer(Modifier.width(gap))
        IconButton(if (playing || loading) Icons.pause else Icons.play, { lens.toggle() }, size = playSize, background = playBackground, color = playColor)
        if (!compact) Spacer(Modifier.width(gap))
        if (!compact) IconButton(Icons.next, { lens.next() }, size = size, background = skipBackground)
    }
}

/** La pista de avance: fondo y la parte recorrida ([fraction] de 0 a 1). */
@Composable
internal fun ProgressTrack(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(c.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(c.primary))
    }
}

/**
 * Qué decir debajo del título cuando la app no manda artista: en Bluetooth, el celular
 * conectado (o que lo conecte); en la radio, nada (la estación ya es el título); en lo demás, "sin datos".
 */
@Composable
internal fun subtitleFor(repo: MusicRepository, song: Song): String = when {
    song.artist.isNotBlank() -> song.artist
    song.source == SourceKind.BLUETOOTH -> repo.bluetoothDevice ?: stringResource(R.string.bluetooth_connect_phone)
    repo.isRadio(song.pkg) -> ""
    else -> stringResource(R.string.no_song_info)
}

/** Nombre corto del método de música (Archivos, Bluetooth, App). */
@Composable
fun MusicMode.label(): String = stringResource(
    when (this) {
        MusicMode.FILES -> R.string.source_usb
        MusicMode.BLUETOOTH -> R.string.source_bluetooth
        MusicMode.APP -> R.string.mode_app
    },
)

/**
 * Qué se está escuchando, arriba de la música en grande: el método elegido en Ajustes (uno solo; ahí
 * se cambia). Con App, el nombre de tu app (o de la que suena).
 */
@Composable
internal fun ModeTitle(repo: MusicRepository, song: Song?, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val text = when (repo.mode) {
        MusicMode.APP -> (repo.preferred ?: song?.pkg?.ifEmpty { null })?.let(repo::appName)?.ifBlank { null } ?: MusicMode.APP.label()
        else -> repo.mode.label()
    }
    Label(text.uppercase(), Typography.label, c.textMuted, 1, modifier)
}

/** La vista cuenta como usuaria de [repo] mientras está en pantalla (escucha solo entonces). */
@Composable
internal fun UseRepo(repo: MusicRepository) {
    DisposableEffect(repo) {
        repo.use()
        onDispose { repo.release() }
    }
}

/**
 * La base de las piezas Música y Radio. Se acomoda a su tamaño: chica es una fila con carátula
 * y play (o los tres botones si cabe); alta y no tan ancha, carátula arriba y lo demás abajo.
 * [revision] cambia al volver a la
 * app, para notar si el usuario acaba de dar el permiso. [label] es la etiqueta chica arriba del
 * texto (de dónde suena; se omite si no cabe), [text] lo escrito y [controls] los botones (con
 * [Modifier] y si van compactos). Con [tinted], la carátula tiñe la tarjeta. La biblioteca y las
 * estaciones no van aquí (aunque el widget sea grande): viven en la pantalla Música.
 */
@Composable
internal fun MediaTile(
    repo: MusicRepository,
    revision: Int,
    modifier: Modifier,
    song: Song?,
    icon: ImageVector,
    title: String,
    explain: String,
    label: String?,
    text: @Composable (tall: Boolean) -> Unit,
    controls: @Composable (Modifier, compact: Boolean) -> Unit,
    /** Sin acceso a notificaciones no se ve nada (Archivos y el Bluetooth de Android no lo necesitan). */
    needsAccess: Boolean = true,
    /** Opciones del widget: con carátula y con anterior/siguiente (sin ellos, solo play/pausa). */
    artwork: Boolean = true,
    skip: Boolean = true,
    tinted: Boolean = false,
) {
    UseRepo(repo)
    val permission = remember(revision, needsAccess) { !needsAccess || repo.hasPermission().also { if (it) repo.recheck() } }
    Card(modifier.fillMaxSize()) {
        if (!permission) return@Card NoPermission(repo, icon, title, explain)
        // La llave es la canción, no el Bitmap: cada play o pausa trae otra instancia de la misma carátula.
        if (tinted && !LocalPlainCard.current) song?.artwork?.let { ArtworkTint(it, "${song.pkg}|${song.title}|${song.artist}") }
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            val tall = maxHeight > 150.dp
            // La etiqueta solo si deja lugar al título, el subtítulo y la barra.
            val labeled: @Composable () -> Unit = {
                val shown = label != null && (tall || maxHeight >= LABEL_MIN)
                if (shown) Label(label.uppercase(), Typography.label, LocalColors.current.textMuted, 1, Modifier.padding(bottom = 2.dp))
                // Si no cupo, la app va en la línea de abajo ([SongDetail]): siempre se sabe qué suena.
                CompositionLocalProvider(LocalHiddenLabel provides label.takeIf { !shown }) { text(tall) }
            }
            if (tall && maxWidth < maxHeight * 1.5f) {
                // Una columna del tablero: carátula arriba, el resto abajo y centrado.
                return@BoxWithConstraints Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Sin carátula, el título y los botones al centro de la columna.
                    if (artwork) Artwork(song, Modifier.fillMaxWidth().weight(1f), Radius.inner, icon) else Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp)) { labeled() }
                    controls(Modifier.padding(top = 8.dp), !skip)
                    if (!artwork) Spacer(Modifier.weight(1f))
                }
            }
            // Mediana y angosta: en fila el título no se leería; carátula chica junto al título y los botones abajo.
            if (artwork && !tall && maxHeight >= STACKED_MIN_HEIGHT && maxWidth < STACKED_MAX_WIDTH) {
                return@BoxWithConstraints Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song, Modifier.size(ARTWORK_ROW_MIN), Radius.inner, icon)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { labeled() }
                    }
                    controls(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), !skip)
                }
            }
            // Angosta (la fila de abajo en 7"): solo play/pausa, para que el título se lea.
            val compact = !skip || !tall && maxWidth < 400.dp
            // Carátula cuadrada con esquinas, dentro de la tarjeta: se achica a lo que deje libre el título (y los
            // botones, si van al lado) en vez de desaparecer; solo se quita si quedaría más chica que el mínimo.
            val text = if (tall) TALL_TEXT_MIN else if (compact) 170.dp else 230.dp
            val art = minOf(maxHeight, 240.dp, maxWidth - text)
            val withArtwork = artwork && art >= if (tall) ARTWORK_MIN else ARTWORK_ROW_MIN
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (withArtwork) Artwork(song, Modifier.size(art), Radius.inner, icon)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                    labeled()
                    if (tall) {
                        Spacer(Modifier.height(12.dp))
                        controls(Modifier, !skip)
                    }
                }
                if (!tall) controls(Modifier, compact)
            }
        }
    }
}

/**
 * Con Archivos, play sin nada que tocar llama [open] (abre la biblioteca) mientras [enabled]. El inicio
 * lo registra siempre (abre la pantalla Música) y la pantalla Música encima mientras está abierta.
 */
@Composable
fun OnFilesEmpty(repo: MusicRepository, enabled: Boolean, open: () -> Unit) {
    DisposableEffect(repo, enabled) {
        // La de antes (otra vista: pantalla dividida o la vista previa de las opciones) se regresa al irse esta.
        val previous = repo.onFilesEmpty
        if (enabled) repo.onFilesEmpty = open
        onDispose { if (repo.onFilesEmpty === open) repo.onFilesEmpty = previous }
    }
}

/**
 * Lo que necesita la columna del título con los tres controles abajo, la carátula más chica que vale la
 * pena y la altura desde la que cabe la etiqueta arriba del texto.
 */
private val TALL_TEXT_MIN = 200.dp
private val ARTWORK_MIN = 72.dp
/** En fila (título y botones al lado) la carátula puede ser más chica: junto al texto se ve bien. */
private val ARTWORK_ROW_MIN = 52.dp
/** Desde este alto y hasta este ancho, el widget pone los botones abajo del título (ver [MediaTile]). */
private val STACKED_MIN_HEIGHT = 116.dp
private val STACKED_MAX_WIDTH = 320.dp

/** En grande: ancha desde aquí (carátula a un lado); arriba solo si queda de [ARTWORK_ABOVE_MIN] o más; si no, chica junto al título. */
private val WIDE_MIN = 480.dp
private val ARTWORK_ABOVE_MIN = 140.dp
private val ARTWORK_SMALL = 96.dp
/** Más bajo que esto, la vista muy baja (ver [NowPlaying]), con la carátula hasta [ARTWORK_SHORT]. */
private val SHORT_MAX = 190.dp
private val ARTWORK_SHORT = 140.dp
private val LABEL_MIN = 88.dp

/** El color promedio de la carátula en degradado suave desde la esquina superior izquierda (como Apple Music). */
@Composable
private fun ArtworkTint(artwork: Bitmap, key: String) {
    val dark = LocalColors.current.dark
    // Una vez por carátula: se reduce a 16×16 y se promedia (si no se puede leer, sin tinte).
    val average = remember(key) { runCatching { averageColor(artwork) }.getOrNull() } ?: return
    val brush = remember(average, dark) {
        Brush.linearGradient(listOf(average.copy(alpha = if (dark) 0.30f else 0.22f), Color.Transparent), Offset.Zero, Offset.Infinite)
    }
    Box(Modifier.fillMaxSize().background(brush))
}

private fun averageColor(bmp: Bitmap): Color {
    val side = 16
    val pixels = IntArray(side * side)
    Bitmap.createScaledBitmap(bmp, side, side, true).getPixels(pixels, 0, side, 0, 0, side, side)
    var r = 0
    var g = 0
    var b = 0
    for (p in pixels) {
        r += p shr 16 and 0xFF
        g += p shr 8 and 0xFF
        b += p and 0xFF
    }
    return Color(r / pixels.size, g / pixels.size, b / pixels.size)
}

/** Sin acceso a notificaciones: qué hace la pieza y el botón para activarlo. */
@Composable
private fun NoPermission(repo: MusicRepository, icon: ImageVector, title: String, explain: String) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, c.accent, 32.dp)
        Column(Modifier.weight(1f)) {
            Label(title, Typography.subtitle, maxLines = 1)
            Label(explain, Typography.small, c.textMuted, maxLines = 2)
        }
        Button(stringResource(R.string.enable), { repo.requestPermission() }, primary = true)
    }
}

/** Título de lo que suena y, debajo, [SongDetail]. */
@Composable
internal fun SongLines(repo: MusicRepository, song: Song, tall: Boolean) {
    Label(song.title, if (tall) Typography.title else Typography.subtitle, maxLines = 1)
    SongDetail(repo, song)
}

/**
 * La etiqueta de la fuente (la app) cuando la tarjeta es tan baja que no cupo arriba: [SongDetail] la agrega
 * a su línea. null = ya se ve arriba.
 */
internal val LocalHiddenLabel = compositionLocalOf<String?> { null }

/** Debajo del título: el error, "Conectando…" o el artista (con la app si no cupo arriba). */
@Composable
internal fun SongDetail(repo: MusicRepository, song: Song) {
    val c = LocalColors.current
    val error = song.error
    when {
        error != null -> Label(error, Typography.small, c.danger, maxLines = 1)
        song.buffering -> Label(stringResource(R.string.player_connecting), Typography.small, c.textMuted, maxLines = 1)
        else -> {
            // Sin canción (Bluetooth sin datos) el título ya es la app: no repetirla.
            val detail = subtitleFor(repo, song).takeIf { it != song.title }
            val app = LocalHiddenLabel.current?.takeIf { it != song.title && it != detail }
            Label(listOfNotNull(detail?.ifBlank { null }, app).joinToString(" · "), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/**
 * La base de la música y la radio en grande: la tarjeta (teñida con la carátula de [tint], como el widget) con
 * su margen. [content] va en una columna que llena la tarjeta.
 */
@Composable
internal fun ExpandedCard(repo: MusicRepository, modifier: Modifier, tint: Song? = null, content: @Composable ColumnScope.() -> Unit) {
    UseRepo(repo)
    Card(modifier.fillMaxSize()) {
        tint?.artwork?.let { if (!LocalPlainCard.current) ArtworkTint(it, "${tint.pkg}|${tint.title}|${tint.artist}") }
        Column(Modifier.fillMaxSize().padding(20.dp)) { content() }
    }
}

/** Cuánto lugar tienen los controles de la vista en grande (ver [NowPlaying]). */
internal enum class ControlsFit {
    /** Todo: barra para adelantar, botones grandes y los botones propios de la app. */
    FULL,
    /** Lo mismo con botones más chicos y menos aire. */
    COMPACT,
    /** Pantallas muy bajas (un celular acostado): solo anterior, play y siguiente, chicos. */
    MINI,
}

/**
 * Carátula, [title] y [controls] en lo que quede de la tarjeta, en pantallas acostadas y paradas. Primero se
 * aparta lo que necesitan título y controles ([detailsHeight]) y la carátula toma lo que sobra: nunca tapa el
 * título ni aplasta los botones.
 * - Ancha: carátula a un lado y título con controles al otro.
 * - Alta con lugar: carátula arriba, título y controles abajo.
 * - Alta sin lugar: carátula chica junto al título y los controles abajo a todo lo ancho.
 * - Muy baja: carátula a un lado y, junto a ella, el título en una línea y los controles chicos.
 */
@Composable
internal fun ColumnScope.NowPlaying(
    song: Song?,
    icon: ImageVector,
    detailsHeight: Dp,
    title: @Composable (Modifier, compact: Boolean) -> Unit,
    controls: @Composable ColumnScope.(ControlsFit) -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val width = maxWidth
        val height = maxHeight
        val wide = width > height * 1.1f && width >= WIDE_MIN
        val above = minOf(width, height - detailsHeight - 16.dp)
        val fit = if (height < detailsHeight) ControlsFit.COMPACT else ControlsFit.FULL
        when {
            height < SHORT_MAX -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val art = minOf(height, width * 0.3f, ARTWORK_SHORT)
                if (art >= ARTWORK_MIN) {
                    Artwork(song, Modifier.size(art), Radius.inner, icon)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    title(Modifier.fillMaxWidth(), true)
                    controls(ControlsFit.MINI)
                }
            }
            wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song, Modifier.size(minOf(height, width * 0.4f)), Radius.card, icon)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    title(Modifier.fillMaxWidth(), fit != ControlsFit.FULL)
                    controls(fit)
                }
            }
            above >= ARTWORK_ABOVE_MIN -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Artwork(song, Modifier.size(above), Radius.card, icon)
                Spacer(Modifier.height(16.dp))
                title(Modifier.fillMaxWidth(), false)
                controls(ControlsFit.FULL)
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val small = minOf(ARTWORK_SMALL, width * 0.28f)
                    if (small >= ARTWORK_MIN) {
                        Artwork(song, Modifier.size(small), Radius.inner, icon)
                        Spacer(Modifier.width(16.dp))
                    }
                    title(Modifier.weight(1f), true)
                }
                controls(fit)
            }
        }
    }
}

/** Título y subtítulo de lo que suena (o el error, "Conectando…", "Abriendo X…" o, sin nada, [idleTitle] e [idleSubtitle]). */
@Composable
internal fun ExpandedTitle(repo: MusicRepository, song: Song?, opening: String?, idleTitle: String, idleSubtitle: String, modifier: Modifier, compact: Boolean) {
    val title = when {
        song != null -> song.title
        opening != null -> stringResource(R.string.opening_app, opening)
        else -> idleTitle
    }
    val error = song?.error
    val subtitle = when {
        error != null -> error
        song?.buffering == true -> stringResource(R.string.player_connecting)
        song != null -> subtitleFor(repo, song)
        opening != null -> stringResource(R.string.opening_wait)
        else -> idleSubtitle
    }
    Column(modifier) { BigTitle(title, subtitle, error != null, compact) }
}

/** Título y subtítulo grandes, con dos líneas reservadas cada uno: los botones no se mueven. */
@Composable
internal fun BigTitle(title: String, subtitle: String, error: Boolean, compact: Boolean) {
    val c = LocalColors.current
    if (compact) {
        // Sin lugar: una línea de cada uno y sin reservar espacio.
        Label(title, Typography.title.copy(fontSize = 22.sp), maxLines = 1)
        Label(subtitle, Typography.subtitle, if (error) c.danger else c.textMuted, maxLines = 1)
        return
    }
    Box(Modifier.heightIn(min = 68.dp), contentAlignment = Alignment.BottomStart) {
        Label(title, Typography.title.copy(fontSize = 26.sp), maxLines = 2)
    }
    Spacer(Modifier.height(4.dp))
    Box(Modifier.heightIn(min = 48.dp)) {
        Label(subtitle, Typography.subtitle, if (error) c.danger else c.textMuted, maxLines = 2)
    }
}

/** Botón redondo del encabezado de la vista en grande (lista, abrir app, cerrar). */
@Composable
internal fun HeaderButton(icon: ImageVector, onClick: () -> Unit) {
    IconButton(icon, onClick, size = 48.dp, background = LocalColors.current.surfaceHigh)
}

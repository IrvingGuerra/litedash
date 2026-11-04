package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MORSE_ASPECT
import com.irvingguerra.litedash.core.ui.MorseMark
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Una imagen que eliges de tu galería: el logo de tu marca (LiteDash no trae logos
 * de marcas, son suyos) o el fondo de la pieza del auto. Se guarda una copia
 * reducida en la app y se decodifica una sola vez por revisión (la usan la pieza,
 * la intro y Ajustes).
 */
private class SavedImage(val file: String, val maxPx: Int, val png: Boolean) {
    var revision = -1
    var image: ImageBitmap? = null

    fun file(context: Context) = File(context.filesDir, file)

    /** Suelta la imagen de memoria (el archivo se queda): la siguiente lectura la vuelve a decodificar. */
    fun release() {
        image = null
        revision = -1
    }
}

/**
 * Las fotos de las piezas de imagen, una por nombre ([Tile.data]): files/images/NOMBRE.png, con
 * transparencia y a lo más 1024 px. Cada pieza tiene la suya.
 */
private val tileImages = HashMap<String, SavedImage>()

private fun newTileImage(name: String) = SavedImage("images/$name.png", 1024, png = true)

private fun tileImage(name: String) = tileImages.getOrPut(name) { newTileImage(name) }

/**
 * Suelta de memoria las fotos de las piezas de imagen que ya no están en [tiles] (se
 * quitaron o cambiaron de foto). Solo la memoria: los PNG se quedan en files/images/.
 */
fun releaseTileImages(tiles: List<Tile>) {
    val used = tiles.mapNotNullTo(HashSet()) { t -> t.data.takeIf { t.type == TileTypes.IMAGE } }
    val iterator = tileImages.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in used) {
            entry.value.release()
            iterator.remove()
        }
    }
}

/** La imagen de la animación de entrada: aparte de la de la pieza del auto. */
private val INTRO = SavedImage("intro.png", 1024, png = true)

/** El fondo del inicio: una foto que llena toda la pantalla. */
private val WALLPAPER = SavedImage("wallpaper.jpg", 1920, png = false)

/** La imagen del modo estacionado (en vez del reloj), con transparencia por si es un logo. */
private val PARKED = SavedImage("parked.png", 1920, png = true)

/** Copia la imagen elegida, reducida a [SavedImage.maxPx]. */
private suspend fun save(context: Context, uri: Uri, target: SavedImage): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target.maxPx) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching false
        // El muestreo es en potencias de 2: se termina de ajustar a maxPx exactos.
        val k = target.maxPx.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (k < 1f) decoded.scale((decoded.width * k).toInt(), (decoded.height * k).toInt()) else decoded
        // Primero a un temporal: si algo falla a la mitad, la imagen de antes queda intacta.
        val temp = File(context.filesDir, "${target.file}.tmp").apply { parentFile?.mkdirs() }
        temp.outputStream().use {
            if (target.png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        temp.renameTo(target.file(context))
        target.revision = -1
        true
    }.getOrDefault(false)
}

/** La imagen; [loaded] = ya se sabe si hay o no (para no mostrar el nombre de paso). */
private class LoadedImage(val image: ImageBitmap?, val loaded: Boolean)

@Composable
private fun rememberImage(target: SavedImage, revision: Int): LoadedImage {
    val context = LocalContext.current
    val cached = target.revision == revision
    val loaded by produceState(if (cached) LoadedImage(target.image, true) else LoadedImage(null, false), target, revision) {
        if (target.revision != revision) {
            target.image = withContext(Dispatchers.IO) {
                target.file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
            target.revision = revision
        }
        value = LoadedImage(target.image, loaded = true)
    }
    return loaded
}

/** Pieza Imagen: tu foto [name] centrada o, sin foto, el logo de LiteDash; un toque hace [onClick]. */
@Composable
fun ImageTile(d: Dependencies, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val loaded = if (name.isEmpty()) LoadedImage(null, true) else rememberImage(tileImage(name), d.prefs.imageRevision)
    val image = loaded.image
    Box(
        modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> Image(image, null, Modifier.fillMaxSize(0.8f))
            loaded.loaded -> MorseMark(Modifier.fillMaxWidth(0.5f).aspectRatio(MORSE_ASPECT))
        }
    }
}

/**
 * Animación de entrada al abrir LiteDash: el logo aparece desde el fondo, crece un
 * poco y se desvanece hacia el inicio. Un toque la salta.
 */
@Composable
fun LogoIntro(prefs: Preferences, onDone: () -> Unit) {
    val c = LocalColors.current
    val done by rememberUpdatedState(onDone)
    // El fondo tapa el inicio desde el primer cuadro; el logo aparece encima y al final
    // todo se desvanece hacia el inicio.
    val curtain = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.82f) }
    // Solo se ve al abrir: al terminar (o saltarla) su imagen sale de memoria.
    DisposableEffect(Unit) { onDispose { INTRO.release() } }
    LaunchedEffect(Unit) {
        // Empieza cuando la app ya se dibuja: mientras Android muestra su pantalla de
        // arranque (y el inicio carga el auto) la animación se iba sin que se viera.
        repeat(3) { withFrameNanos { } }
        delay(250)
        launch { scale.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        alpha.animateTo(1f, tween(600))
        delay(1100)
        curtain.animateTo(0f, tween(500))
        done()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = curtain.value }
            .background(c.background)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        val logo = rememberImage(INTRO, prefs.introRevision)
        Box(
            Modifier.fillMaxSize(0.55f).graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
            contentAlignment = Alignment.Center,
        ) {
            // Con tu imagen, la tuya; sin ella, el logo de LiteDash.
            val image = logo.image
            when {
                image != null -> Image(image, null, Modifier.fillMaxSize(0.7f))
                logo.loaded -> MorseMark(Modifier.fillMaxWidth(0.6f).aspectRatio(MORSE_ASPECT))
            }
        }
    }
}

/**
 * Tu imagen de fondo detrás de todo el inicio, recortada para llenar la pantalla.
 * Lleva un velo del color del tema para que la hora y los textos se lean encima.
 */
@Composable
fun HomeWallpaper(prefs: Preferences) {
    val c = LocalColors.current
    val image = rememberImage(WALLPAPER, prefs.wallpaperRevision).image ?: return
    Box(Modifier.fillMaxSize()) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(c.background.copy(alpha = 0.4f)))
    }
}

/**
 * Tu imagen del modo estacionado a toda la pantalla (sin recortarla). Devuelve false si no hay
 * imagen guardada, para que se vea el reloj. Sale de memoria al quitarse el modo estacionado.
 */
@Composable
fun parkedImageShown(prefs: Preferences): Boolean {
    DisposableEffect(Unit) { onDispose { PARKED.release() } }
    val loaded = rememberImage(PARKED, prefs.parkedRevision)
    val image = loaded.image
    if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    return image != null || !loaded.loaded
}

/** Inicio → Modo estacionado: reloj o tu imagen (elegirla, cambiarla o quitarla). */
@Composable
fun ParkedSettings(prefs: Preferences) {
    val c = LocalColors.current
    SwitchRow(stringResource(R.string.parked_mode), prefs.parkedMode, { prefs.parkedMode = it }, stringResource(R.string.parked_mode_help))
    if (!prefs.parkedMode) return
    Segmented(
        listOf(ParkedView.CLOCK to stringResource(R.string.parked_clock), ParkedView.IMAGE to stringResource(R.string.parked_image)),
        prefs.parkedView,
        { prefs.parkedView = it },
    )
    if (prefs.parkedView == ParkedView.IMAGE && !ImageButtons(PARKED, prefs.parkedRevision, { prefs.parkedRevision++ })) {
        Label(stringResource(R.string.parked_no_image), Typography.small, c.textMuted)
    }
}

/** Elegir, cambiar o quitar una imagen guardada; [onPicked] avisa tras guardar una nueva. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageButtons(target: SavedImage, revision: Int, bump: () -> Unit, onPicked: () -> Unit = {}): Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { if (save(context, uri, target)) { bump(); onPicked() } }
    }
    // Basta saber si el archivo existe: decodificar la imagen completa para esto sobraba.
    val hasImage = remember(target, revision) { target.file(context).exists() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(if (hasImage) R.string.logo_change else R.string.logo_pick), {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        if (hasImage) Button(stringResource(R.string.logo_remove), {
            target.file(context).delete()
            bump()
        })
    }
    return hasImage
}

/** Apariencia → Fondo del inicio: elegir, cambiar o quitar la imagen. */
@Composable
fun WallpaperSettings(prefs: Preferences) {
    ImageButtons(WALLPAPER, prefs.wallpaperRevision, { prefs.wallpaperRevision++ })
}

/** Apariencia → Animación de entrada: encenderla y su propia imagen. */
@Composable
fun IntroSettings(prefs: Preferences) {
    val c = LocalColors.current
    SwitchRow(stringResource(R.string.logo_intro), prefs.logoIntro, { prefs.logoIntro = it }, stringResource(R.string.logo_intro_help))
    if (prefs.logoIntro && !ImageButtons(INTRO, prefs.introRevision, { prefs.introRevision++ })) {
        Label(stringResource(R.string.intro_no_image), Typography.small, c.textMuted)
    }
}

/** Elegir, cambiar o quitar la foto de una pieza de imagen ([tileId]). */
@Composable
fun CustomizeImagePanel(d: Dependencies, tileId: String, close: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    // Sin foto todavía: se le da un nombre nuevo y se guarda en la pieza al elegirla.
    val fresh = remember(tileId) { System.currentTimeMillis().toString(36) }
    val name = tile.data.ifEmpty { fresh }
    // La del nombre nuevo va aparte (no en tileImages): abrir el panel y cerrarlo sin elegir no deja nada.
    val freshImage = remember(tileId) { newTileImage(fresh) }
    val target = if (tile.data.isEmpty()) freshImage else tileImage(name)
    PanelScaffold(stringResource(R.string.image_title), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(stringResource(R.string.image_help), Typography.body, c.textMuted)
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(2f)) {
                ImageTile(d, tile.data, onClick = {})
            }
            val hasImage = ImageButtons(target, d.prefs.imageRevision, { d.prefs.imageRevision++ }, onPicked = {
                if (tile.data.isEmpty()) d.dashboard.setData(tileId, name)
            })
            if (!hasImage) Label(stringResource(R.string.image_empty), Typography.small, c.textMuted)
        }
    }
}

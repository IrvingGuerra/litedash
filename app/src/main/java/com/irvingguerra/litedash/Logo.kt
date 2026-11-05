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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Una imagen que eliges de tu galería: la de un widget Imagen (p. ej. el logo de tu marca;
 * LiteDash no trae logos de marcas), la de la animación de entrada, el fondo del inicio o
 * la del modo estacionado. Se guarda una copia reducida en la app y se decodifica una sola
 * vez por [version]: cada imagen lleva la suya, así cambiar una no vuelve a leer las demás.
 */
private class SavedImage(val file: String, val maxPx: Int, val png: Boolean) {
    /** Sube al elegir o quitar la imagen (solo en memoria): quien la muestra la vuelve a leer. */
    var version by mutableIntStateOf(0)
        private set

    /** La [version] que ya está decodificada en [image]; -1 = ninguna. */
    var revision = -1
    var image: ImageBitmap? = null

    fun bump() {
        version++
    }

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

/** La imagen de la animación de entrada: aparte de las de los widgets Imagen. */
private val INTRO = SavedImage("intro.png", 1024, png = true)

/** El fondo del inicio: una foto que llena toda la pantalla. */
private val WALLPAPER = SavedImage("wallpaper.jpg", 1920, png = false)

/** La imagen del modo estacionado (en vez del reloj), con transparencia por si es un logo. */
private val PARKED = SavedImage("parked.png", 1920, png = true)

/** Lee la imagen [uri] reducida a [maxPx] por lado; null si no se puede leer. Llamar fuera del hilo principal. */
private fun decodeScaled(context: Context, uri: Uri, maxPx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null
    // El muestreo es en potencias de 2: se termina de ajustar a maxPx exactos.
    val k = maxPx.toFloat() / maxOf(decoded.width, decoded.height)
    if (k < 1f) decoded.scale((decoded.width * k).toInt(), (decoded.height * k).toInt()) else decoded
}.getOrNull()

/** Guarda [bitmap] en [target]: primero a un temporal, así si algo falla la imagen de antes queda intacta. */
private suspend fun write(context: Context, bitmap: Bitmap, target: SavedImage): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val temp = File(context.filesDir, "${target.file}.tmp").apply { parentFile?.mkdirs() }
        temp.outputStream().use {
            if (target.png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        temp.renameTo(target.file(context))
    }.getOrDefault(false)
}

/** Copia la imagen elegida, reducida a [SavedImage.maxPx]. */
private suspend fun save(context: Context, uri: Uri, target: SavedImage): Boolean {
    val bitmap = withContext(Dispatchers.IO) { decodeScaled(context, uri, target.maxPx) } ?: return false
    return write(context, bitmap, target)
}

/** Lado máximo de la foto al quitarle el fondo (y del recorte que se guarda): memoria y CPU de pantallas viejas. */
private const val CUT_MAX_PX = 900

/** Vista previa del fondo quitado con la intensidad [level]; [image] null = no se pudo separar. */
private class CutPreview(val source: Bitmap, val level: Int, val bitmap: Bitmap?) {
    val image = bitmap?.asImageBitmap()
}

/**
 * Quita el fondo de [source] (fuera del hilo principal). Uno a la vez: al tocar varias intensidades
 * seguidas, las que ya no se ven ni empiezan (cada una ocupa ~14 MB mientras corre).
 */
private suspend fun cutPreview(source: Bitmap, level: Int): CutPreview = cutLock.withLock {
    currentCoroutineContext().ensureActive()
    cut(source, level)
}

private val cutLock = Mutex()

private suspend fun cut(source: Bitmap, level: Int): CutPreview = withContext(Dispatchers.Default) {
    val w = source.width
    val h = source.height
    val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
    val cut = removeBackground(pixels, w, h, level)
    CutPreview(source, level, cut?.let { Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) })
}

/** La imagen; [loaded] = ya se sabe si hay o no (para no mostrar el nombre de paso). */
private class LoadedImage(val image: ImageBitmap?, val loaded: Boolean)

@Composable
private fun rememberImage(target: SavedImage): LoadedImage {
    val context = LocalContext.current
    val revision = target.version
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
    val loaded = if (name.isEmpty()) LoadedImage(null, true) else rememberImage(tileImage(name))
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
        val logo = rememberImage(INTRO)
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
    val image = rememberImage(WALLPAPER).image ?: return
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
    val loaded = rememberImage(PARKED)
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
    if (prefs.parkedView == ParkedView.IMAGE && !imageButtons(PARKED)) {
        Label(stringResource(R.string.parked_no_image), Typography.small, c.textMuted)
    }
}

/** Elegir, cambiar o quitar una imagen guardada; [onPicked] avisa tras guardar una nueva. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun imageButtons(target: SavedImage, onPicked: (Uri) -> Unit = {}): Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { if (save(context, uri, target)) { target.bump(); onPicked(uri) } }
    }
    // Basta saber si el archivo existe: decodificar la imagen completa para esto sobraba.
    val hasImage = remember(target, target.version) { target.file(context).exists() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(if (hasImage) R.string.logo_change else R.string.logo_pick), {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        if (hasImage) Button(stringResource(R.string.logo_remove), {
            scope.launch {
                withContext(Dispatchers.IO) { target.file(context).delete() }
                target.bump()
            }
        })
    }
    return hasImage
}

/** Inicio y apariencia → Imágenes → Fondo del inicio: elegir, cambiar o quitar la imagen. */
@Composable
fun WallpaperSettings() {
    imageButtons(WALLPAPER)
}

/** Inicio y apariencia → Imágenes → Animación de entrada: encenderla y su propia imagen. */
@Composable
fun IntroSettings(prefs: Preferences) {
    val c = LocalColors.current
    SwitchRow(stringResource(R.string.logo_intro), prefs.logoIntro, { prefs.logoIntro = it }, stringResource(R.string.logo_intro_help))
    if (prefs.logoIntro && !imageButtons(INTRO)) {
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // La foto elegida en este panel: mientras siga abierto se puede volver a la original.
    var picked by remember(tileId) { mutableStateOf<Uri?>(null) }
    val source by produceState<Bitmap?>(null, picked) {
        value = picked?.let { withContext(Dispatchers.IO) { decodeScaled(context, it, CUT_MAX_PX) } }
    }
    var removing by remember(tileId) { mutableStateOf(false) }
    var level by remember(tileId) { mutableIntStateOf(1) }
    // Lo que hay guardado: -1 = la original; si no, la intensidad con que se quitó el fondo.
    var saved by remember(tileId) { mutableIntStateOf(-1) }
    val chosen = if (removing) level else -1
    // Se queda la vista previa anterior mientras se calcula la nueva (sin parpadeo).
    val preview by produceState<CutPreview?>(null, source, removing, level) {
        val photo = source
        if (removing && photo != null && (value?.source !== photo || value?.level != level)) value = cutPreview(photo, level)
    }
    val ready = preview?.takeIf { removing && it.source === source && it.level == level }
    val sourceImage = remember(source) { source?.asImageBitmap() }
    PanelScaffold(stringResource(R.string.image_title), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(stringResource(R.string.image_help), Typography.body, c.textMuted)
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(2f), contentAlignment = Alignment.Center) {
                val shown = (if (removing) preview?.takeIf { it.source === source }?.image else null) ?: sourceImage
                if (shown != null) Image(shown, null, Modifier.fillMaxSize(0.8f)) else ImageTile(d, tile.data, onClick = {})
            }
            val hasImage = imageButtons(target, onPicked = { uri ->
                if (tile.data.isEmpty()) d.dashboard.setData(tileId, name)
                picked = uri
                removing = false
                saved = -1
            })
            // Al quitar la imagen se olvida la foto elegida.
            LaunchedEffect(hasImage) { if (!hasImage) picked = null }
            if (!hasImage) Label(stringResource(R.string.image_empty), Typography.small, c.textMuted)
            if (hasImage && source != null) {
                Label(stringResource(R.string.image_background), Typography.label, c.textMuted)
                Segmented(
                    listOf(false to stringResource(R.string.image_background_original), true to stringResource(R.string.image_background_remove)),
                    removing,
                    { removing = it },
                )
                if (removing) {
                    Label(stringResource(R.string.image_background_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.image_strength), Typography.label, c.textMuted)
                    Segmented(
                        listOf(
                            0 to stringResource(R.string.image_strength_low),
                            1 to stringResource(R.string.image_strength_medium),
                            2 to stringResource(R.string.image_strength_high),
                            3 to stringResource(R.string.image_strength_max),
                        ),
                        level,
                        { level = it },
                    )
                    if (ready != null && ready.bitmap == null) Label(stringResource(R.string.image_cut_failed), Typography.small, c.danger)
                }
                val cut = ready?.bitmap
                if (chosen != saved && (chosen < 0 || cut != null)) {
                    Button(stringResource(R.string.image_apply), {
                        val uri = picked ?: return@Button
                        scope.launch {
                            val ok = if (cut == null) save(context, uri, target) else write(context, cut, target)
                            if (ok) {
                                target.bump()
                                saved = chosen
                            }
                        }
                    }, primary = true)
                }
            }
        }
    }
}

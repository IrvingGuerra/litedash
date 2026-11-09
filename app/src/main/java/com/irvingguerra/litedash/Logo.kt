package com.irvingguerra.litedash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
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
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.FileBrowser
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MORSE_ASPECT
import com.irvingguerra.litedash.core.ui.MorseMark
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.scaledDecode
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Una imagen que eliges de tu galería: la de un widget Imagen (p. ej. el logo de tu marca;
 * LiteDash no trae logos de marcas), la de la animación de entrada, el fondo del inicio o
 * la del modo estacionado. Se guarda una copia reducida a [maxPx] en la app y se decodifica una sola
 * vez por [version]: cada imagen lleva la suya, así cambiar una no vuelve a leer las demás.
 * Al mostrarla se lee al tamaño en que se ve: [screenShare] = fracción del lado largo de la pantalla
 * que ocupa a lo más (0 = como se guardó); con [cover], lo justo para cubrir la pantalla. Las JPEG
 * (sin transparencia) se leen en RGB_565: la mitad de memoria.
 */
private class SavedImage(
    val file: String,
    val maxPx: Int,
    val png: Boolean,
    val screenShare: Float = 0f,
    val cover: Boolean = false,
) {
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

    /** La imagen guardada al tamaño en que se muestra; null si no hay o no se pudo leer (p. ej. sin memoria). Solo en IO. */
    fun read(context: Context): ImageBitmap? = orNull {
        val path = file(context).takeIf { it.exists() }?.path ?: return@orNull null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = scaledDecode(maxOf(bounds.outWidth, bounds.outHeight), shownPx(context.resources.displayMetrics, bounds.outWidth, bounds.outHeight))
        if (!png) options.inPreferredConfig = Bitmap.Config.RGB_565
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }

    /** Lado largo con que se lee una imagen de [width]×[height]; 0 = como se guardó. Nunca agranda ([scaledDecode]). */
    private fun shownPx(display: DisplayMetrics, width: Int, height: Int): Int = when {
        screenShare <= 0f || width <= 0 || height <= 0 -> 0
        cover -> ceil(maxOf(width, height) * maxOf(display.widthPixels.toFloat() / width, display.heightPixels.toFloat() / height)).toInt()
        else -> maxOf(MIN_SHOWN_PX, (maxOf(display.widthPixels, display.heightPixels) * screenShare).roundToInt())
    }
}

/** Lo menos con que se lee una foto de widget o del frente de un velocímetro (pantallas chicas). */
private const val MIN_SHOWN_PX = 512

/**
 * [block] o null si falla, también sin memoria: en Android 6 y 7 las imágenes viven en la memoria de
 * la app y una foto grande puede no caber (sin esto, tumbaría la app).
 */
private inline fun <T> orNull(block: () -> T?): T? = try {
    block()
} catch (e: OutOfMemoryError) {
    null
} catch (e: Exception) {
    null
}

/**
 * Las fotos de las piezas de imagen y de lo que va al frente de un velocímetro, una por nombre
 * ([Tile.data] o [GaugeLook.image]): files/images/NOMBRE.png, con transparencia y a lo más 1024 px.
 * Cada pieza tiene la suya; se muestran a lo más a media pantalla.
 */
private val tileImages = HashMap<String, SavedImage>()

private fun newTileImage(name: String) = SavedImage("images/$name.png", 1024, png = true, screenShare = 0.5f)

private fun tileImage(name: String) = tileImages.getOrPut(name) { newTileImage(name) }

/**
 * Suelta de memoria las fotos de las piezas (imagen o al frente de un velocímetro) que ya no están en
 * [tiles] (se quitaron o cambiaron de foto). Solo la memoria: los PNG se quedan en files/images/.
 */
fun releaseTileImages(tiles: List<Tile>) {
    val used = tiles.mapNotNullTo(HashSet()) { t ->
        when (t.type) {
            TileTypes.IMAGE -> t.data
            TileTypes.SPEEDOMETER -> GaugeLook.parse(t.data).takeIf { it.front == GaugeFront.IMAGE }?.image
            else -> null
        }
    }
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

/** El fondo del inicio: una foto que cubre toda la pantalla (se lee en RGB_565, sin transparencia). */
private val WALLPAPER = SavedImage("wallpaper.jpg", 1920, png = false, screenShare = 1f, cover = true)

/** La imagen del modo estacionado (en vez del reloj), con transparencia por si es un logo. */
private val PARKED = SavedImage("parked.png", 1920, png = true, screenShare = 1f)

/** Lee la imagen [uri] ya reducida a [maxPx] por lado; null si no se puede leer. Llamar fuera del hilo principal. */
private fun decodeScaled(context: Context, uri: Uri, maxPx: Int): Bitmap? = orNull {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, scaledDecode(maxOf(bounds.outWidth, bounds.outHeight), maxPx))
    }
}

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
            target.image = withContext(Dispatchers.IO) { target.read(context) }
            target.revision = revision
        }
        value = LoadedImage(target.image, loaded = true)
    }
    return loaded
}

/**
 * Pieza Imagen: tu foto [name] centrada o, sin foto, el logo de LiteDash (nada si [placeholder] es
 * false); un toque hace [onClick].
 */
@Composable
fun ImageTile(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, placeholder: Boolean = true) {
    val loaded = if (name.isEmpty()) LoadedImage(null, true) else rememberImage(tileImage(name))
    val image = loaded.image
    Box(
        modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> Image(image, null, Modifier.fillMaxSize(0.8f))
            loaded.loaded && placeholder -> MorseMark(Modifier.fillMaxWidth(0.5f).aspectRatio(MORSE_ASPECT))
        }
    }
}

/** La foto [name] de files/images/, abajo al centro y sin recortar (al frente de un velocímetro); sin foto, nada. */
@Composable
fun SavedTileImage(name: String, modifier: Modifier = Modifier) {
    val image = rememberImage(tileImage(name)).image ?: return
    Image(image, null, modifier, alignment = Alignment.BottomCenter)
}

/**
 * Animación de entrada al abrir LiteDash: el logo aparece desde el fondo, crece un
 * poco y se desvanece hacia el inicio. Un toque la salta.
 */
@Composable
fun LogoIntro(onDone: () -> Unit) {
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
fun HomeWallpaper() {
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
fun parkedImageShown(): Boolean {
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
    if (prefs.parkedView == ParkedView.IMAGE && imageButtons(PARKED) == false) {
        Label(stringResource(R.string.parked_no_image), Typography.small, c.textMuted)
    }
}

/**
 * Elegir, cambiar o quitar una imagen guardada; [onPicked] avisa tras guardar una nueva y [onRemoved]
 * tras borrarla. Devuelve si hay imagen (null mientras se revisa el disco).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun imageButtons(target: SavedImage, onPicked: (Uri) -> Unit = {}, onRemoved: () -> Unit = {}): Boolean? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val use: (Uri) -> Unit = { uri -> scope.launch { if (save(context, uri, target)) { target.bump(); onPicked(uri) } } }
    // El selector de Android regresó sin imagen (quizá ni se abrió) o no hay: el explorador propio.
    var missed by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val onResult: (Uri?) -> Unit = { uri -> if (uri != null) use(uri) else missed = true }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onResult)
    val content = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), onResult)
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) browsing = true
    }
    val browse = {
        // Hasta Android 12 leer la memoria pide permiso; desde 13 el selector de fotos viene en el sistema.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ask.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else browsing = true
    }
    // Pantallas de auto sin "Archivos" ni galería: lanzar el selector tronaba la app (no había quién lo abriera).
    val open = {
        val opened = (canPickImage(context) && runCatching { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.isSuccess) ||
            (canGetImage(context) && runCatching { content.launch("image/*") }.isSuccess)
        if (!opened) browse()
    }
    // Basta saber si el archivo existe (en IO); mientras, se queda lo último que se supo.
    val hasImage by produceState<Boolean?>(null, target, target.version) {
        value = withContext(Dispatchers.IO) { target.file(context).exists() }
    }
    val known = hasImage ?: return null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(if (known) R.string.logo_change else R.string.logo_pick), open)
        if (missed && !browsing) Button(stringResource(R.string.image_browse_memory), browse)
        if (denied) Label(stringResource(R.string.image_no_permission), Typography.small, LocalColors.current.danger)
        if (known) Button(stringResource(R.string.logo_remove), {
            scope.launch {
                withContext(Dispatchers.IO) { target.file(context).delete() }
                target.bump()
                onRemoved()
            }
        })
    }
    if (browsing) FileBrowser(
        pickFolder = false,
        accept = { name -> name.substringAfterLast('.', "").lowercase() in IMAGE_TYPES },
        onPick = { file ->
            browsing = false
            missed = false
            use(Uri.fromFile(file))
        },
        onCancel = { browsing = false },
    )
    return known
}

private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

/** Hay quién abra el selector de fotos (el del sistema o, si no, "Archivos" con imágenes). */
private fun canPickImage(context: Context): Boolean =
    ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context) ||
        resolves(context, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))

/** Hay una galería o un gestor de archivos que entregue una imagen. */
private fun canGetImage(context: Context): Boolean =
    resolves(context, Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))

private fun resolves(context: Context, intent: Intent) =
    runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)

/** Inicio y apariencia → Inicio → Fondo de pantalla: elegir, cambiar o quitar la imagen. */
@Composable
fun WallpaperSettings() {
    imageButtons(WALLPAPER)
}

/** Inicio y apariencia → Inicio → Animación de entrada: encenderla y su propia imagen. */
@Composable
fun IntroSettings(prefs: Preferences) {
    val c = LocalColors.current
    SwitchRow(stringResource(R.string.logo_intro), prefs.logoIntro, { prefs.logoIntro = it }, stringResource(R.string.logo_intro_help))
    if (prefs.logoIntro && imageButtons(INTRO) == false) {
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
    PanelScaffold(stringResource(R.string.image_title), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(stringResource(R.string.image_help), Typography.body, c.textMuted)
            ImageEditor(tileId, tile.data) { d.dashboard.setData(tileId, it) }
        }
    }
}

/**
 * Elegir, cambiar o quitar una foto guardada como files/images/[current].png, con vista previa y
 * "quitar fondo". [key] identifica a quien la usa (la pieza); sin foto todavía ([current] vacío) se le
 * da un nombre nuevo y se avisa con [onNamed] al elegirla; [onRemoved] avisa al quitarla. [empty] dice
 * qué pasa sin foto y [placeholder], si la vista previa sin foto lleva el logo de LiteDash. Va dentro de una columna.
 */
@Composable
fun ImageEditor(
    key: String,
    current: String,
    empty: String = stringResource(R.string.image_empty),
    placeholder: Boolean = true,
    onRemoved: () -> Unit = {},
    onNamed: (String) -> Unit,
) {
    val c = LocalColors.current
    // Sin foto todavía: se le da un nombre nuevo y se guarda al elegirla.
    val fresh = remember(key) { System.currentTimeMillis().toString(36) }
    val name = current.ifEmpty { fresh }
    // La del nombre nuevo va aparte (no en tileImages): abrir el panel y cerrarlo sin elegir no deja nada.
    val freshImage = remember(key) { newTileImage(fresh) }
    val target = if (current.isEmpty()) freshImage else tileImage(name)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // La foto elegida en este panel: mientras siga abierto se puede volver a la original.
    var picked by remember(key) { mutableStateOf<Uri?>(null) }
    val source by produceState<Bitmap?>(null, picked) {
        value = picked?.let { withContext(Dispatchers.IO) { decodeScaled(context, it, CUT_MAX_PX) } }
    }
    var removing by remember(key) { mutableStateOf(false) }
    var level by remember(key) { mutableIntStateOf(1) }
    // Lo que hay guardado: -1 = la original; si no, la intensidad con que se quitó el fondo.
    var saved by remember(key) { mutableIntStateOf(-1) }
    val chosen = if (removing) level else -1
    // Se queda la vista previa anterior mientras se calcula la nueva (sin parpadeo).
    val preview by produceState<CutPreview?>(null, source, removing, level) {
        val photo = source
        if (removing && photo != null && (value?.source !== photo || value?.level != level)) value = cutPreview(photo, level)
    }
    val ready = preview?.takeIf { removing && it.source === source && it.level == level }
    val sourceImage = remember(source) { source?.asImageBitmap() }
    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(2f), contentAlignment = Alignment.Center) {
        val shown = (if (removing) preview?.takeIf { it.source === source }?.image else null) ?: sourceImage
        if (shown != null) Image(shown, null, Modifier.fillMaxSize(0.8f)) else ImageTile(current, onClick = {}, placeholder = placeholder)
    }
    val hasImage = imageButtons(target, onPicked = { uri ->
        if (current.isEmpty()) onNamed(name)
        picked = uri
        removing = false
        saved = -1
    }, onRemoved = onRemoved)
    // Al quitar la imagen se olvida la foto elegida.
    LaunchedEffect(hasImage) { if (hasImage == false) picked = null }
    if (hasImage == false) Label(empty, Typography.small, c.textMuted)
    if (hasImage == true && source != null) {
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

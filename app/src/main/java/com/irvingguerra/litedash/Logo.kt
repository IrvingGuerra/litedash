package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.LightColors
import com.irvingguerra.litedash.core.ui.DarkColors
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.geometry.Size
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.CardLook
import com.irvingguerra.litedash.core.ui.GLASS_GUARD
import com.irvingguerra.litedash.core.ui.GLASS_LIFT
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.CardBackdrop
import com.irvingguerra.litedash.core.ui.blurredBackdrop
import com.irvingguerra.litedash.core.ui.FileBrowser
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MORSE_ASPECT
import com.irvingguerra.litedash.core.ui.MORSE_INTRO_MS
import com.irvingguerra.litedash.core.ui.MorseMark
import com.irvingguerra.litedash.core.ui.MorseMarkIntro
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.Blurred
import com.irvingguerra.litedash.core.ui.blurredCopy
import com.irvingguerra.litedash.core.ui.canvasVeil
import com.irvingguerra.litedash.core.ui.glassVeil
import com.irvingguerra.litedash.core.ui.legibleVeil
import com.irvingguerra.litedash.core.ui.meanColor
import com.irvingguerra.litedash.core.ui.scaledDecode
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cómo se ve una imagen guardada en pantalla: de eso depende a qué tamaño se lee. */
internal enum class ImageFit {
    /** A lo más a media pantalla (widget Imagen, frente de un velocímetro y animación de entrada), nunca menos de [MIN_SHOWN_PX]. */
    HALF_SCREEN,

    /** Completa dentro de la pantalla, sin recortar (modo estacionado). */
    FIT_SCREEN,

    /** Cubre toda la pantalla, recortada (fondo del inicio). */
    COVER_SCREEN,
}

/**
 * Lado largo con que se lee una imagen de [width]×[height] que se muestra como [shown] en una pantalla de
 * [screenWidth]×[screenHeight] (0 sin medidas). Puede pedir más que la imagen: [scaledDecode] nunca agranda.
 */
internal fun shownPx(shown: ImageFit, width: Int, height: Int, screenWidth: Int, screenHeight: Int): Int {
    if (width <= 0 || height <= 0) return 0
    // El lado largo escalado para que el ancho llegue al de la pantalla, o el alto al suyo (redondeado hacia arriba,
    // en enteros para no perder un píxel por decimales).
    val long = maxOf(width, height).toLong()
    val across = ((long * screenWidth + width - 1) / width).toInt()
    val down = ((long * screenHeight + height - 1) / height).toInt()
    return when (shown) {
        ImageFit.HALF_SCREEN -> maxOf(MIN_SHOWN_PX, maxOf(screenWidth, screenHeight) / 2)
        ImageFit.FIT_SCREEN -> minOf(across, down)
        ImageFit.COVER_SCREEN -> maxOf(across, down)
    }
}

/**
 * Una imagen que eliges de tu galería: la de un widget Imagen (p. ej. el logo de tu marca;
 * LiteDash no trae logos de marcas), la de la animación de entrada, el fondo del inicio o
 * la del modo estacionado. Se guarda una copia reducida a [maxPx] en la app y se decodifica una sola
 * vez por [version]: cada imagen lleva la suya, así cambiar una no vuelve a leer las demás.
 * Se guarda en PNG solo si tiene transparencia; si no, en JPEG (el nombre del archivo no cambia:
 * Android la lee por su contenido, y así lo que ya tenías guardado se sigue leyendo igual). Al mostrarla
 * se lee al tamaño en que se ve ([shown]); las JPEG y las [opaque] en RGB_565: la mitad de memoria.
 */
private class SavedImage(
    val file: String,
    val maxPx: Int,
    val shown: ImageFit,
    /** Nunca lleva transparencia (el fondo cubre la pantalla): siempre JPEG y RGB_565. */
    val opaque: Boolean = false,
    /** Corre al cambiar la imagen (en el hilo principal): para soltar lo que se calculó de la de antes. */
    private val onBump: () -> Unit = {},
) {
    /** Sube al elegir o quitar la imagen (solo en memoria): quien la muestra la vuelve a leer. */
    var version by mutableIntStateOf(0)
        private set

    /** La [version] que ya está decodificada en [image]; -1 = ninguna. */
    var revision = -1
    var image: ImageBitmap? = null

    /**
     * Uno a la vez escribe o borra el archivo (y lo que va con él): elegir tu foto mientras baja un fondo de LiteDash
     * usaba el mismo temporal y el que terminaba primero le borraba el suyo al otro.
     */
    val lock = Mutex()

    fun bump() {
        version++
        onBump()
    }

    fun file(context: Context) = File(context.filesDir, file)

    /** Suelta la imagen de memoria (el archivo se queda): la siguiente lectura la vuelve a decodificar. */
    fun release() {
        image = null
        revision = -1
    }

    /** La imagen guardada al tamaño en que se muestra; null si no hay o no se pudo leer (p. ej. sin memoria). Solo en IO. */
    fun read(context: Context): ImageBitmap? {
        val file = file(context).takeIf { it.exists() } ?: return null
        val screen = context.resources.displayMetrics
        return decodeImage("read $file", maxPx = { w, h -> shownPx(shown, w, h, screen.widthPixels, screen.heightPixels) }, lowMemory = opaque) {
            FileInputStream(file)
        }?.asImageBitmap()
    }
}

/** Lo menos con que se lee una foto de widget o del frente de un velocímetro (pantallas chicas). */
private const val MIN_SHOWN_PX = 512

/**
 * Lee una imagen ya reducida: primero sus medidas y luego la imagen al lado largo que da [maxPx] (0 = completa).
 * [open] abre la imagen desde el principio (se llama dos veces). Las JPEG (nunca tienen transparencia) y con
 * [lowMemory] se leen en RGB_565. null si no se puede leer: formato que Android no conoce, archivo dañado o sin
 * memoria (en Android 6 y 7 las imágenes viven en la memoria de la app y una foto grande puede no caber). [what]
 * va al registro de Android para saber por qué.
 */
private fun decodeImage(what: String, maxPx: (Int, Int) -> Int, lowMemory: Boolean, open: () -> InputStream?): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.w(LOG_TAG, "No se pudo leer la imagen ($what): formato ${bounds.outMimeType}")
        null
    } else {
        val options = scaledDecode(maxOf(bounds.outWidth, bounds.outHeight), maxPx(bounds.outWidth, bounds.outHeight))
        if (lowMemory || bounds.outMimeType == JPEG_MIME) options.inPreferredConfig = Bitmap.Config.RGB_565
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }
} catch (e: IOException) {
    Log.w(LOG_TAG, "No se pudo leer la imagen ($what)", e)
    null
} catch (e: SecurityException) {
    // Un archivo de otra app que ya no nos deja leerlo.
    Log.w(LOG_TAG, "Sin permiso para leer la imagen ($what)", e)
    null
} catch (e: OutOfMemoryError) {
    Log.w(LOG_TAG, "Sin memoria para la imagen ($what)", e)
    null
}

private const val JPEG_MIME = "image/jpeg"

/**
 * Giro en grados y espejo (horizontal, después de girar) que dejan derecha una foto con la orientación EXIF
 * [orientation] (1–8, como la guarda la cámara). Cualquier otro valor: tal cual.
 */
@SuppressLint("ExifInterface") // Ver exifOrientation: la de Android, sin dependencia nueva.
internal fun exifTurn(orientation: Int): Pair<Int, Boolean> = when (orientation) {
    android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
    android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
    android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
    android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
    else -> 0 to false
}

/**
 * La orientación EXIF de [uri] (las fotos de cámara vienen acostadas y la marca dice cómo girarlas). De un
 * archivo, por su ruta (todas las versiones); de otra app, por su flujo (Android 7+; en Android 6 ese camino no
 * se usa: ahí las fotos se eligen con el explorador propio, que da archivos). Sin dato o ilegible: normal.
 *
 * Usa la ExifInterface de Android y no la de androidx para no sumar una dependencia (APK ligera): el aviso de lint
 * es por fallas de la versión vieja, y aquí solo se lee la orientación de una foto que eligió el usuario.
 */
@SuppressLint("ExifInterface")
private fun exifOrientation(context: Context, uri: Uri): Int {
    val normal = android.media.ExifInterface.ORIENTATION_NORMAL
    return try {
        val exif = when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path?.let { android.media.ExifInterface(it) }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it) }
            else -> null
        }
        exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, normal) ?: normal
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo leer la orientación de $uri", e)
        normal
    } catch (e: RuntimeException) {
        // La ExifInterface de Android truena de varias formas con una foto dañada.
        Log.w(LOG_TAG, "Orientación ilegible en $uri", e)
        normal
    }
}

/** [bitmap] derecho según la orientación EXIF [orientation] (suelta el original si tuvo que girarlo). */
private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
    val (degrees, mirror) = exifTurn(orientation)
    if (degrees == 0 && !mirror) return bitmap
    val matrix = Matrix().apply {
        setRotate(degrees.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (turned !== bitmap) bitmap.recycle()
    return turned
}

/**
 * Las fotos de las piezas de imagen y de lo que va al frente de un velocímetro, una por nombre
 * ([Tile.data] o [GaugeLook.image]): files/images/NOMBRE.png (PNG si tiene transparencia, si no JPEG), a lo
 * más 1024 px. Cada pieza tiene la suya; se muestran a lo más a media pantalla.
 */
private val tileImages = HashMap<String, SavedImage>()

private fun newTileImage(name: String) = SavedImage("images/$name.png", 1024, ImageFit.HALF_SCREEN)

private fun tileImage(name: String) = tileImages.getOrPut(name) { newTileImage(name) }

/**
 * Suelta de memoria las fotos de las piezas (imagen o al frente de un velocímetro) que ya no están en
 * [tiles] (se quitaron o cambiaron de foto). Solo la memoria: los archivos se quedan en files/images/.
 */
fun releaseTileImages(tiles: List<Tile>) {
    val used = tiles.mapNotNullTo(HashSet()) { t ->
        when (t.type) {
            TileTypes.IMAGE -> ImageData.name(t.data)
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
private val INTRO = SavedImage("intro.png", 1024, ImageFit.HALF_SCREEN)

/** El fondo del inicio: una foto que cubre toda la pantalla (sin transparencia). */
private val WALLPAPER = SavedImage(OwnWallpaper.CURRENT, 1920, ImageFit.COVER_SCREEN, opaque = true, onBump = ::forgetWallpaperCopies)

/** La imagen del modo estacionado (en vez del reloj), completa; con transparencia si es un logo. */
private val PARKED = SavedImage("parked.png", 1920, ImageFit.FIT_SCREEN)

/**
 * Lee la imagen [uri] derecha (según su EXIF) y reducida a [maxPx] por lado; [lowMemory] = en RGB_565 aunque no
 * sea JPEG. null si no se puede leer. Llamar fuera del hilo principal.
 */
private fun decodePicked(context: Context, uri: Uri, maxPx: Int, lowMemory: Boolean): Bitmap? {
    val bitmap = decodeImage("$uri", { _, _ -> maxPx }, lowMemory) { context.contentResolver.openInputStream(uri) } ?: return null
    return try {
        upright(bitmap, exifOrientation(context, uri))
    } catch (e: OutOfMemoryError) {
        Log.w(LOG_TAG, "Sin memoria para girar la imagen ($uri)", e)
        bitmap.recycle()
        null
    }
}

/**
 * Guarda [bitmap] en [target]: PNG solo si tiene transparencia (un recorte o un logo), si no JPEG. Primero a un
 * temporal, así si algo falla la imagen de antes queda intacta.
 */
private suspend fun write(context: Context, bitmap: Bitmap, target: SavedImage): Boolean = withContext(Dispatchers.IO) {
    val png = !target.opaque && bitmap.hasAlpha()
    try {
        replaceFile(target.file(context)) { temp ->
            temp.outputStream().use { out ->
                val written = if (png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                if (!written) throw IOException("compress ${target.file}")
            }
        }
        true
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo guardar ${target.file}", e)
        false
    }
}

/** Copia la imagen elegida, derecha y reducida a [SavedImage.maxPx]. false si no se pudo leer o guardar. */
private suspend fun save(context: Context, uri: Uri, target: SavedImage): Boolean {
    val bitmap = withContext(Dispatchers.IO) { decodePicked(context, uri, target.maxPx, target.opaque) } ?: return false
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
 * Animación de entrada al abrir LiteDash. Sin imagen propia, la marca —·· se arma (el guion
 * entra y los puntos caen saltando); con tu imagen, aparece desde el fondo y crece un poco.
 * Al final se desvanece hacia el inicio. Un toque la salta.
 */
@Composable
fun LogoIntro(onDone: () -> Unit) {
    val c = LocalColors.current
    val done by rememberUpdatedState(onDone)
    val logo = rememberImage(INTRO)
    val current by rememberUpdatedState(logo)
    // El fondo tapa el inicio desde el primer cuadro; el logo aparece encima y al final
    // todo se desvanece hacia el inicio.
    val curtain = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.82f) }
    val time = remember { Animatable(0f) }
    // Solo se ve al abrir: al terminar (o saltarla) su imagen sale de memoria.
    DisposableEffect(Unit) { onDispose { INTRO.release() } }
    LaunchedEffect(Unit) {
        // Empieza cuando la app ya se dibuja: mientras Android muestra su pantalla de
        // arranque (y el inicio carga el auto) la animación se iba sin que se viera.
        repeat(3) { withFrameNanos { } }
        delay(250)
        snapshotFlow { current.loaded }.first { it }
        if (current.image == null) {
            time.animateTo(MORSE_INTRO_MS.toFloat(), tween(MORSE_INTRO_MS, easing = LinearEasing))
            delay(450)
        } else {
            launch { scale.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
            alpha.animateTo(1f, tween(600))
            delay(1100)
        }
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
        // Con tu imagen, la tuya; sin ella, la marca de LiteDash armándose.
        val image = logo.image
        when {
            image != null -> Box(
                Modifier.fillMaxSize(0.55f).graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
                contentAlignment = Alignment.Center,
            ) { Image(image, null, Modifier.fillMaxSize(0.7f)) }
            logo.loaded -> MorseMarkIntro({ time.value }, Modifier.fillMaxWidth(0.33f).aspectRatio(MORSE_ASPECT))
        }
    }
}

/**
 * Tu imagen de fondo detrás de todo el inicio, recortada para llenar la pantalla.
 * Lleva un velo del color del tema, lo que pidas en «Oscurecer el fondo» ([Preferences.wallpaperDim]; se lee al
 * dibujar) pero nunca menos de lo que pide tu imagen para que lo que va sin tarjeta se lea ([legibleVeil]). Sin imagen
 * y con tarjetas de vidrio, el resplandor del tema.
 */
@Composable
fun HomeWallpaper(prefs: Preferences, asInWindow: Boolean = false) {
    val c = LocalColors.current
    val image = rememberImage(WALLPAPER).image
    val floor = rememberLegibleFloor(image)
    if (image == null) {
        // Sin imagen, el vidrio se luce sobre el resplandor del tema.
        if (LocalCardLook.current.style == CardStyle.GLASS) Box(Modifier.fillMaxSize().glow())
        return
    }
    // Una muestra ([asInWindow]): el pedazo de tu fondo que cae justo ahí, a la escala del inicio (no la imagen
    // entera en chico), para que lo de encima se vea igual que en el inicio.
    if (asInWindow) {
        return Box(Modifier.fillMaxSize().blurredBackdrop(remember(image, c, floor) { CardBackdrop(image) { c.background.copy(alpha = maxOf(prefs.wallpaperDim, floor)) } }))
    }
    Image(image, null, Modifier.fillMaxSize().drawWithContent { drawContent(); drawRect(c.background.copy(alpha = maxOf(prefs.wallpaperDim, floor))) }, contentScale = ContentScale.Crop)
}

/**
 * Lo menos que se vela [image] en el inicio para que el texto del tema se lea sin tarjeta ([legibleVeil] sobre su
 * color medio, calculado una vez por imagen y fuera del hilo principal). 0 sin imagen o mientras se calcula.
 */
@Composable
private fun rememberLegibleFloor(image: ImageBitmap?): Float {
    val c = LocalColors.current
    val mean by produceState(wallpaperMean?.takeIf { it.first === image }?.second, image) {
        value = image?.let { src ->
            wallpaperMean?.takeIf { it.first === src }?.second
                ?: withContext(Dispatchers.Default) { meanColor(src.asAndroidBitmap()) }.also { wallpaperMean = src to it }
        }
    }
    return remember(mean, c) { mean?.let { legibleVeil(c, it) } ?: 0f }
}

/**
 * Tu fondo de pantalla desenfocado ([wanted]), una vez por imagen, en chico y fuera del hilo principal; para el cristal
 * ([clear]) es otra copia: menos desenfocada y con el color más vivo. null = sin fondo (o aún no está).
 */
@Composable
private fun rememberBlurredWallpaper(wanted: Boolean, clear: Boolean): Pair<ImageBitmap, Blurred>? {
    val image = if (wanted) rememberImage(WALLPAPER).image else null
    // Lo comparten el inicio, Ajustes y lo que se abre en grande.
    val cached = if (clear) clearWallpaper else blurredWallpaper
    val blurred by produceState(cached?.takeIf { it.first === image }, image, clear) {
        value = image?.let { src ->
            (if (clear) clearWallpaper else blurredWallpaper)?.takeIf { it.first === src }
                ?: (src to withContext(Dispatchers.Default) { blurredCopy(src.asAndroidBitmap(), vivid = clear) })
                    .also { if (clear) clearWallpaper = it else blurredWallpaper = it }
        }
    }
    // Solo la copia de ESTA imagen: [blurred] guarda la de antes hasta que su efecto corre (un cuadro después de
    // cambiar de página o de fondo) y ese cuadro las tarjetas salían con otra piel; si ya está hecha, de una vez.
    return blurred?.takeIf { it.first === image } ?: cached?.takeIf { it.first === image }
}

/**
 * Para las tarjetas de vidrio del inicio ([wanted]): tu fondo desenfocado como cristal y el velo que lleva detrás: el de
 * tu fondo ([dim], se lee al dibujar, nunca menos del que pide tu imagen), cerrado lo que haga falta para que el texto
 * de la tarjeta se lea. null = sin fondo (o aún no está).
 */
@Composable
fun rememberWallpaperBackdrop(wanted: Boolean, dim: () -> Float): CardBackdrop? {
    val pair = rememberBlurredWallpaper(wanted, clear = true)
    val floor = rememberLegibleFloor(pair?.first)
    val b = pair?.second ?: return null
    val c = LocalColors.current
    // Se calcula una vez por valor, no en cada dibujo.
    val currentDim by rememberUpdatedState(dim)
    return remember(b, c, floor) {
        // Con margen ([GLASS_GUARD]): detrás del cristal tu fondo va más claro que afuera y una mancha brillante pesa más.
        val extreme = if (c.dark) lerp(b.light, Color.White, GLASS_GUARD) else lerp(b.shade, Color.Black, GLASS_GUARD)
        var lastDim = Float.NaN
        var veil = 0f
        CardBackdrop(b.image) {
            // Detrás del cristal tu fondo va menos oscurecido que afuera ([GLASS_LIFT]): la tarjeta se ve con luz propia.
            val base = maxOf(currentDim(), floor) * GLASS_LIFT
            if (base != lastDim) {
                lastDim = base
                veil = glassVeil(c, extreme, base)
            }
            c.background.copy(alpha = veil)
        }
    }
}

/**
 * Lo calculado de tu fondo y de qué imagen salió (solo se toca en el hilo principal): desenfocado para el lienzo, para
 * el cristal y su color medio. Se sueltan al cambiar o quitar el fondo ([forgetWallpaperCopies]), así no retienen la
 * imagen de antes (hasta 4.6 MB en una pantalla grande).
 */
private var blurredWallpaper: Pair<ImageBitmap, Blurred>? = null
private var clearWallpaper: Pair<ImageBitmap, Blurred>? = null
private var wallpaperMean: Pair<ImageBitmap, Color>? = null

private fun forgetWallpaperCopies() {
    blurredWallpaper = null
    clearWallpaper = null
    wallpaperMean = null
}

/** El velo de partida del lienzo: más cerrado que el del inicio, encima van listas y textos largos. */
private const val CANVAS_VEIL = 0.62f

/**
 * La piel sin el fondo desenfocado por tarjeta (lo que va sobre un lienzo ya desenfocado). La misma de la última vez
 * si no cambió: una piel nueva en cada recomposición hacía recomponer todo lo que la lee.
 */
internal fun globalLook(look: CardLook): CardLook {
    if (look.backdrop == null && look.clear) return look
    lastGlobal?.let { (from, to) -> if (from === look) return to }
    return CardLook(look.style, look.opacity).also { lastGlobal = look to it }
}

private var lastGlobal: Pair<CardLook, CardLook>? = null

/**
 * El lienzo de lo que tapa el inicio (Ajustes, los paneles, la música o el mapa en grande): el fondo del tema y, con
 * tarjetas de vidrio, tu fondo de pantalla desenfocado bajo un velo (una vez por pantalla; las tarjetas de encima
 * solo llevan su relleno) o, sin imagen, el resplandor del tema. En tema claro el velo se cierra lo que pida tu imagen
 * para que lo apagado (las secciones sin elegir) se lea; en oscuro es el de siempre ([canvasVeil]).
 */
@Composable
internal fun Modifier.canvas(): Modifier {
    val c = LocalColors.current
    val glass = LocalCardLook.current.style == CardStyle.GLASS
    val blurred = rememberBlurredWallpaper(glass, clear = false)?.second
    val veil = remember(blurred, c) { blurred?.let { c.background.copy(alpha = canvasVeil(c, if (c.dark) it.light else it.shade, CANVAS_VEIL)) } }
    val base = background(c.background)
    if (!glass) return base
    if (blurred == null || veil == null) return base.glow()
    return base.drawBehind {
        val iw = blurred.image.width.toFloat()
        val ih = blurred.image.height.toFloat()
        val s = maxOf(size.width / iw, size.height / ih)
        translate((size.width - iw * s) / 2, (size.height - ih * s) / 2) {
            scale(s, Offset.Zero) { drawImage(blurred.image, filterQuality = FilterQuality.High) }
        }
        drawRect(veil)
    }
}

/**
 * El resplandor del tema detrás del vidrio cuando no hay imagen de fondo: dos luces suaves (una arriba y un azul
 * acero abajo) para que las tarjetas tengan qué dejar ver. Con imagen, la imagen lo tapa.
 */
@Composable
internal fun Modifier.glow(): Modifier {
    val c = LocalColors.current
    // De fábrica, luces neutras (plata arriba, acero abajo): el ámbar de la marca se queda en los detalles, en un
    // degradado grande se veía sucio. Con un color de interfaz elegido, la luz de arriba es de ese color.
    val natural = c.accent == (if (c.dark) DarkColors else LightColors).accent
    val warm = when {
        !natural -> c.accent.copy(alpha = if (c.dark) 0.26f else 0.22f)
        c.dark -> GLOW_SILVER.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.7f)
    }
    val cool = GLOW_STEEL.copy(alpha = if (c.dark) 0.3f else 0.22f)
    return drawWithCache {
        // Se pinta una vez en chico y cada cuadro solo se estira (dos degradados a pantalla completa por cuadro
        // pesan en las pantallas lentas).
        val w = GLOW_WIDTH
        val h = (w * size.height / size.width.coerceAtLeast(1f)).toInt().coerceIn(1, GLOW_WIDTH * 4)
        val small = ImageBitmap(w, h)
        val reach = maxOf(w, h) * 0.75f
        CanvasDrawScope().draw(this, layoutDirection, Canvas(small), Size(w.toFloat(), h.toFloat())) {
            drawRect(Brush.radialGradient(listOf(warm, Color.Transparent), Offset(w * 0.12f, 0f), reach))
            drawRect(Brush.radialGradient(listOf(cool, Color.Transparent), Offset(w * 0.92f, h.toFloat()), reach))
        }
        val full = IntSize(size.width.toInt(), size.height.toInt())
        onDrawBehind { drawImage(small, dstSize = full, filterQuality = FilterQuality.High) }
    }
}

private const val GLOW_WIDTH = 160
private val GLOW_SILVER = Color(0xFFC9D3E0)
private val GLOW_STEEL = Color(0xFF3F6C9E)

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
    val minutes = (PARK_AFTER_MS / 60_000).toInt()
    SwitchRow(stringResource(R.string.parked_mode), prefs.parkedMode, { prefs.parkedMode = it }, pluralStringResource(R.plurals.parked_mode_help, minutes, minutes))
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
 * Elegir, cambiar o quitar una imagen guardada; [onPicked] corre tras guardar una nueva y [onRemoved] tras borrarla.
 * Guardar y quitar terminan aunque se cierre Ajustes a la mitad (si no, el archivo cambiaba y el inicio no se
 * enteraba). Si la imagen no se puede leer, lo dice. [busy] = otra cosa está cambiando la misma imagen (no se deja
 * elegir a la vez). Devuelve si hay imagen (null mientras se revisa el disco).
 */
@Composable
private fun imageButtons(
    target: SavedImage,
    busy: Boolean = false,
    onPicked: suspend (Uri) -> Unit = {},
    onRemoved: suspend () -> Unit = {},
): Boolean? {
    val context = LocalContext.current
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var unusable by remember(target) { mutableStateOf(false) }
    val use: (Uri) -> Unit = { uri ->
        saving = true
        unusable = false
        scope.launch {
            withContext(NonCancellable) {
                val ok = target.lock.withLock {
                    save(context, uri, target).also { ok ->
                        if (ok) {
                            target.bump()
                            onPicked(uri)
                        }
                    }
                }
                unusable = !ok
                saving = false
            }
        }
    }
    var browsing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) use(uri) }
    // Negado para siempre (Android 11+ a la segunda), Android ya no pregunta: junto al aviso va «Permisos».
    val ask = rememberPermissionAsk(Manifest.permission.READ_EXTERNAL_STORAGE) { ok -> if (ok) browsing = true }
    // Un solo camino por versión. Android 13+: el selector de fotos del sistema (siempre viene, no pide permisos y el
    // explorador propio ya no puede ver fotos sin un permiso que Play no da a un launcher). Hasta 12: el explorador
    // propio, porque los selectores de las pantallas de auto fallan (sin "Archivos", o el de Google Play Services, que
    // se baja de Play: "No es posible acceder" y LiteDash trabado).
    val open: () -> Unit = {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> try {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: ActivityNotFoundException) {
                Log.w(LOG_TAG, "Sin selector de fotos del sistema", e)
            }
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> ask.ask()
            else -> {
                // Pudo darse en Ajustes de Android: ya no hay nada que avisar.
                ask.denied = false
                browsing = true
            }
        }
    }
    // Basta saber si el archivo existe (en IO); mientras, se queda lo último que se supo.
    val hasImage by produceState<Boolean?>(null, target, target.version) {
        value = withContext(Dispatchers.IO) { target.file(context).exists() }
    }
    val known = hasImage ?: return null
    val idle = !saving && !busy
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(if (known) R.string.logo_change else R.string.logo_pick), open, enabled = idle)
        if (known) Button(stringResource(R.string.logo_remove), {
            unusable = false
            scope.launch {
                withContext(NonCancellable) {
                    target.lock.withLock {
                        withContext(Dispatchers.IO) { target.file(context).delete() }
                        target.bump()
                        onRemoved()
                    }
                }
            }
        }, enabled = idle)
    }
    if (ask.denied) {
        Label(stringResource(R.string.image_no_permission), Typography.small, c.danger)
        Button(stringResource(R.string.local_network_settings), { openAppSettings(context) })
    }
    if (unusable) Label(stringResource(R.string.image_unusable), Typography.small, c.danger)
    if (browsing) FileBrowser(
        pickFolder = false,
        accept = { name -> name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_TYPES },
        onPick = { file ->
            browsing = false
            use(Uri.fromFile(file))
        },
        onCancel = { browsing = false },
        images = true,
        onPickUri = { uri ->
            browsing = false
            use(uri)
        },
    )
    return known
}

/** Lo que el explorador propio ofrece como imagen: lo que Android sabe leer (HEIC desde Android 9). */
private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") +
    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setOf("heic", "heif") else emptySet())

/**
 * Inicio y apariencia → Inicio → Fondo de pantalla: los fondos de LiteDash (miniaturas; uno se baja de litedash.dev
 * al tocarlo y queda guardado como si fuera tu foto) y elegir, cambiar o quitar una foto tuya. Sin internet, solo tu foto.
 * Mientras baja uno no se puede elegir tu foto; y si tu foto se está guardando al tocar uno, el fondo espera su turno
 * ([SavedImage.lock]): gana el último que se eligió.
 */
@Composable
fun WallpaperSettings(prefs: Preferences) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(WALLPAPER.version) { chosen = withContext(Dispatchers.IO) { liteDashWallpaper(context) } }
    val choices by produceState<List<WallpaperChoice>?>(null) { value = loadWallpapers(context) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val list = choices
    if (!list.isNullOrEmpty()) {
        Label(stringResource(R.string.wallpaper_included), Typography.label, c.textMuted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (w in list) {
                val thumb by produceState<ImageBitmap?>(null, w.id) { value = wallpaperThumb(context, w.id) }
                val selected = chosen == w.id
                val shape = RoundedCornerShape(Radius.inner)
                Box(
                    Modifier
                        .size(WALLPAPER_THUMB_WIDTH, WALLPAPER_THUMB_WIDTH * 9 / 16)
                        .clip(shape)
                        .background(c.surfaceHigh)
                        .border(if (selected) 3.dp else 0.dp, if (selected) c.accent else Color.Transparent, shape)
                        // El que ya está puesto no se vuelve a bajar.
                        .clickable(enabled = downloading == null && !selected) {
                            downloading = w.id
                            failed = false
                            // Elegido a mano: el fondo de un Diseño que esperaba internet ya no se pone solo.
                            prefs.designWallpaper = null
                            scope.launch {
                                failed = putLiteDashWallpaper(context, w.id) != WallpaperFetch.DONE
                                downloading = null
                            }
                        },
                ) {
                    thumb?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = if (downloading == w.id) 0.4f else 1f }, contentScale = ContentScale.Crop) }
                    if (downloading == w.id) Label(stringResource(R.string.wallpaper_downloading), Typography.small, c.text, 1, Modifier.align(Alignment.Center))
                }
            }
        }
        val credit = list.firstOrNull { it.id == chosen }?.credit
        when {
            failed -> Label(downloadError(R.string.wallpaper_failed), Typography.small, c.danger)
            !credit.isNullOrBlank() && credit != OWN_CREDIT -> Label(stringResource(R.string.wallpaper_credit, credit), Typography.small, c.textMuted)
        }
        Label(stringResource(R.string.wallpaper_own), Typography.label, c.textMuted)
    }
    val forget: suspend () -> Unit = {
        withContext(Dispatchers.IO) { wallpaperMarker(context).delete() }
        chosen = null
        prefs.designWallpaper = null
    }
    if (imageButtons(WALLPAPER, busy = downloading != null, onPicked = { forget() }, onRemoved = forget) == true) WallpaperDim(prefs)
}

/** Oscurecer el fondo (en tema claro, aclararlo): de nada a [MAX_WALLPAPER_DIM]; de fábrica, el velo de siempre. */
@Composable
private fun WallpaperDim(prefs: Preferences) {
    val c = LocalColors.current
    Label(stringResource(R.string.wallpaper_dim), Typography.label, c.textMuted)
    LevelSlider(prefs.wallpaperDim / MAX_WALLPAPER_DIM, { prefs.previewWallpaperDim(it * MAX_WALLPAPER_DIM) }) { prefs.wallpaperDim = it * MAX_WALLPAPER_DIM }
    Label(stringResource(R.string.wallpaper_dim_help), Typography.small, c.textMuted)
}

/** Anota cuál de los fondos de LiteDash quedó puesto (si no se puede, solo no se marca su miniatura). */
private fun writeMarker(marker: File, id: String) {
    try {
        marker.writeText(id)
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo anotar el fondo $id", e)
    }
}

/** Cuál de los fondos de LiteDash está puesto: un archivo junto al fondo (elegir tu foto o quitarlo lo borra). */
private fun wallpaperMarker(context: Context) = File(context.filesDir, OwnWallpaper.MARKER)

/** El id del fondo de LiteDash que está puesto; null = tu foto o ninguno. Solo en IO. */
internal fun liteDashWallpaper(context: Context): String? = try {
    wallpaperMarker(context).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
} catch (e: IOException) {
    Log.w(LOG_TAG, "No se pudo leer cuál fondo está puesto", e)
    null
}

/**
 * Pone el fondo de LiteDash [id]: lo baja de litedash.dev y reemplaza el de ahora solo si llegó completo (como si fuera tu
 * foto). Termina aunque se cierre quien lo pidió (el inicio debe enterarse). [still] se revisa ya con el candado: si dice
 * que no (otro lo cambió mientras esperaba su turno), no se baja nada. [byDesign] = lo pone un Diseño (se anota su
 * huella: [OwnWallpaper]). Desde el hilo principal.
 */
internal suspend fun putLiteDashWallpaper(context: Context, id: String, byDesign: Boolean = false, still: () -> Boolean = { true }): WallpaperFetch =
    withContext(NonCancellable) {
        WALLPAPER.lock.withLock {
            if (!still()) return@withLock WallpaperFetch.SKIPPED
            downloadWallpaper(context, id, WALLPAPER.file(context)).also { got ->
                if (got != WallpaperFetch.DONE) return@also
                withContext(Dispatchers.IO) {
                    writeMarker(wallpaperMarker(context), id)
                    if (byDesign) placeSafely(context)
                }
                WALLPAPER.bump()
            }
        }
    }

/**
 * El fondo que dejó un Diseño sigue al tema: al pasar de claro a oscuro (o al revés: «Día y noche», o cambiarlo a mano)
 * se pone su pareja ([themedWallpaper]). Solo si sigue siendo el del Diseño: tu foto o un fondo que elegiste no se
 * tocan. Sin red y sin copia guardada se queda el que está (se reintenta en el siguiente cambio o al abrir).
 */
@Composable
internal fun ThemedDesignWallpaper() {
    val dark = LocalColors.current.dark
    val context = LocalContext.current
    LaunchedEffect(dark, WALLPAPER.version) {
        val want = withContext(Dispatchers.IO) {
            if (!OwnWallpaper.placedByDesign(context.filesDir)) return@withContext null
            liteDashWallpaper(context)?.let { id -> themedWallpaper(id, dark)?.takeIf { it != id } }
        } ?: return@LaunchedEffect
        putLiteDashWallpaper(context, want, byDesign = true)
    }
}

/**
 * Antes de que un Diseño cambie el fondo: tu foto se guarda aparte ([OwnWallpaper.keep]). false si no se pudo copiar:
 * entonces no se debe cambiar el fondo (se perdería). Desde el hilo principal.
 */
internal suspend fun keepOwnWallpaper(context: Context): Boolean = withContext(NonCancellable) {
    WALLPAPER.lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                OwnWallpaper.keep(context.filesDir)
                true
            } catch (e: IOException) {
                Log.w(LOG_TAG, "No se pudo guardar tu fondo para deshacer", e)
                false
            }
        }
    }
}

/** Regresa tu fondo de antes de los Diseños, si el de ahora sigue siendo de un Diseño. Desde el hilo principal. */
internal suspend fun restoreOwnWallpaper(context: Context) = withContext(NonCancellable) {
    WALLPAPER.lock.withLock {
        val changed = withContext(Dispatchers.IO) {
            try {
                OwnWallpaper.restore(context.filesDir)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "No se pudo regresar tu fondo", e)
                false
            }
        }
        if (changed) WALLPAPER.bump()
    }
}

/**
 * Sin fondo de imagen (queda el resplandor del tema): lo que pone un Diseño mientras su fondo espera internet. Solo
 * después de [keepOwnWallpaper] (si no, se perdería tu foto). Desde el hilo principal.
 */
internal suspend fun clearWallpaper(context: Context) = withContext(NonCancellable) {
    WALLPAPER.lock.withLock {
        withContext(Dispatchers.IO) {
            WALLPAPER.file(context).delete()
            wallpaperMarker(context).delete()
            placeSafely(context)
        }
        WALLPAPER.bump()
    }
}

/** Anota que el fondo de ahora lo puso un Diseño (si no se puede, al deshacer solo no se regresa tu foto). Solo en IO. */
private fun placeSafely(context: Context) {
    try {
        OwnWallpaper.placed(context.filesDir)
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo anotar el fondo del Diseño", e)
    }
}

/** El crédito de los fondos hechos por LiteDash en index.json: ese no se muestra. */
private const val OWN_CREDIT = "LiteDash"

/** Ancho de cada miniatura de los fondos de LiteDash (16:9). */
private val WALLPAPER_THUMB_WIDTH = 144.dp

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
    val tile = d.dashboard.tile(tileId)
    if (tile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    PanelScaffold(stringResource(R.string.image_title), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(stringResource(R.string.image_help), Typography.body, c.textMuted)
            val name = ImageData.name(tile.data)
            val tires = TileTires.parse(tile.data)
            ImageEditor(tileId, name) { d.dashboard.setData(tileId, ImageData.encode(it, tires)) }
            // Las llantas en sus esquinas (solo con Llantas prendido); de fábrica no.
            if (d.hasTpmsSource) {
                Label(stringResource(R.string.tile_tires), Typography.label, c.textMuted)
                SwitchRow(stringResource(R.string.tires_show_pressure), showsTires(d, tile), { d.dashboard.setData(tileId, ImageData.encode(name, it)) })
            }
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
        value = picked?.let { withContext(Dispatchers.IO) { decodePicked(context, it, CUT_MAX_PX, lowMemory = false) } }
    }
    var removing by remember(key) { mutableStateOf(false) }
    var level by remember(key) { mutableIntStateOf(1) }
    // Lo que hay guardado: -1 = la original; si no, la intensidad con que se quitó el fondo.
    var saved by remember(key) { mutableIntStateOf(-1) }
    var applyFailed by remember(key) { mutableStateOf(false) }
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
        applyFailed = false
    }, onRemoved = { onRemoved() })
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
            Segmented(List(CUT_LEVELS) { it to stringResource(CUT_STRENGTHS[it]) }, level, { level = it })
            if (ready != null && ready.bitmap == null) Label(stringResource(R.string.image_cut_failed), Typography.small, c.danger)
        }
        val cut = ready?.bitmap
        if (chosen != saved && (chosen < 0 || cut != null)) {
            Button(stringResource(R.string.image_apply), {
                val uri = picked ?: return@Button
                applyFailed = false
                scope.launch {
                    withContext(NonCancellable) {
                        val ok = target.lock.withLock {
                            (if (cut == null) save(context, uri, target) else write(context, cut, target)).also { ok -> if (ok) target.bump() }
                        }
                        if (ok) saved = chosen
                        applyFailed = !ok
                    }
                }
            }, primary = true)
        }
        if (applyFailed) Label(stringResource(R.string.image_unusable), Typography.small, c.danger)
    }
}

/** Los textos de cada intensidad de "quitar fondo", de suave a máxima: uno por cada nivel de [CUT_LEVELS]. */
private val CUT_STRENGTHS = listOf(
    R.string.image_strength_low,
    R.string.image_strength_medium,
    R.string.image_strength_high,
    R.string.image_strength_max,
)

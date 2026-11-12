package com.irvingguerra.litedash.feature.car

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Anchos de la pieza que hay que arrastrar para dar una vuelta completa. */
private const val WIDTHS_PER_TURN = 0.83f

/** Cuadros que se preparan adelante, hacia donde va el dedo. */
private const val PREFETCH = 3

/** El cuadro de tres cuartos con el frente a la izquierda (3/8 de vuelta): donde empieza el auto. */
private fun restFrame(count: Int) = count * 3 / 8

/**
 * El auto del paquete, con su [paint]: gira con el dedo a los lados (como
 * una vuelta real, con su sombra y su reflejo en el piso) y se queda donde lo
 * dejas. [angle] es la vuelta en fracción (0..1; null = tres cuartos con el
 * frente a la izquierda) y [onAngle] la recibe al soltar para guardarla.
 * [onReady] se llama una vez, cuando ya se ve (para animar sin que se vea vacío).
 */
@Composable
fun CarTurntable(
    repo: CarRepository,
    p: CarPackage,
    paint: CarPaint,
    modifier: Modifier = Modifier,
    angle: Float? = null,
    onAngle: ((Float) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
    wheels: (@Composable (spots: () -> WheelSpots) -> Unit)? = null,
) {
    val frames = remember(p) { spinFrames(p) }
    val n = frames.size
    var size by remember { mutableStateOf(IntSize.Zero) }
    val widthPx = size.width
    val saved = angle?.let { (it * n).roundToInt().mod(n) } ?: restFrame(n)
    // Un solo estado por auto: el gesto sigue moviendo este mismo aunque cambie el
    // ángulo guardado (si se recreara al guardar, el dedo movería uno que ya no se dibuja).
    var frame by remember(p) { mutableIntStateOf(saved) }
    // El cuadro que ya se ve (el que se pidió puede seguir cargando): las ruedas siguen a ese.
    var shown by remember(p) { mutableIntStateOf(saved) }
    var direction by remember(p) { mutableIntStateOf(1) }
    var dragging by remember(p) { mutableStateOf(false) }
    // Si lo giraron en otra pantalla (Ajustes o la vista en grande), aquí se pone igual.
    LaunchedEffect(saved) { if (!dragging) frame = saved }
    // Se preparan los cuadros que siguen (con sus capas), para que aparezcan al instante.
    // Solo al cambiar de cuadro: al arrastrar el selector de color no se precargan colores de paso.
    val currentPaint by rememberUpdatedState(paint)
    LaunchedEffect(p, frame, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        for (k in 1..PREFETCH) repo.layers(p, frames[(frame + direction * k + n) % n], currentPaint, widthPx)
    }
    val turn = Modifier.pointerInput(p) {
        // La misma vuelta por arrastre, tenga los cuadros que tenga (más fluidez = pasos más chicos).
        val step = size.width * WIDTHS_PER_TURN / n
        var accumulated = 0f
        fun release() {
            dragging = false
            onAngle?.invoke(frame.toFloat() / n)
        }
        detectHorizontalDragGestures(
            onDragStart = { accumulated = 0f; dragging = true },
            onDragEnd = ::release,
            onDragCancel = ::release,
        ) { change, dx ->
            change.consume()
            accumulated += dx
            while (accumulated > step) { accumulated -= step; frame = (frame - 1 + n) % n; direction = -1 }
            while (accumulated < -step) { accumulated += step; frame = (frame + 1) % n; direction = 1 }
        }
    }
    Box(modifier.onSizeChanged { size = it }.then(turn)) {
        CarLayersImage(repo, p, frames[frame], paint, widthPx, Modifier, onReady, onShown = { shown = frames.indexOf(it) })
        // Los datos van en medios pasos (00, m00, 01…): en calidad normal, un cuadro sí y uno no. El
        // cuadro se lee al acomodar (no al componer): al girar las pastillas solo se mueven.
        val w = p.wheels
        if (wheels != null && w != null && widthPx > 0) {
            val spots: () -> WheelSpots = remember(w, size, p.high) {
                val box = Size(size.width.toFloat(), size.height.toFloat())
                val read: () -> WheelSpots = { w.spots(if (p.high) shown else shown * 2, box) }
                read
            }
            wheels(spots)
        }
    }
}

/** La vuelta en que el auto se ve de espaldas (en 0.25 se ve de frente; en 3/8, de tres cuartos). */
const val REAR_TURN = 0.75f

/**
 * El auto quieto, con su [paint]: en tres cuartos para las tarjetas de la lista
 * de modelos, o en la vuelta [turn] (como [REAR_TURN], de espaldas en la carretera).
 */
@Composable
fun CarStill(
    repo: CarRepository,
    p: CarPackage,
    paint: CarPaint,
    modifier: Modifier = Modifier,
    turn: Float? = null,
    onReady: (() -> Unit)? = null,
    reflection: Boolean = true,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val frame = turn?.let { (it * p.spin).roundToInt().mod(p.spin) } ?: restFrame(p.spin)
    CarLayersImage(repo, p, baseFrame(frame), paint, widthPx, modifier.onSizeChanged { widthPx = it.width }, onReady, reflection = reflection)
}

/** El auto desde arriba ([CarViews.TOP_VIEW] del paquete) pintado del [color] (satinado); solo si [CarPackage.topView]. */
@Composable
internal fun CarTopImage(repo: CarRepository, p: CarPackage, color: Color, modifier: Modifier = Modifier, onReady: (() -> Unit)? = null) {
    var widthPx by remember { mutableIntStateOf(0) }
    CarLayersImage(repo, p, CarViews.TOP_VIEW, CarPaint(color), widthPx, modifier.onSizeChanged { widthPx = it.width }, onReady)
}

/**
 * Dibuja la imagen [name] con sus capas: si trae piso, la sombra y el reflejo
 * van debajo y se salen de la caja hacia abajo y a los lados, así el auto parece
 * parado en el fondo de la pantalla y no dentro de una tarjeta. Mientras carga
 * la siguiente se queda la anterior (el giro no parpadea).
 */
@Composable
private fun CarLayersImage(
    repo: CarRepository,
    p: CarPackage,
    name: String,
    paint: CarPaint,
    widthPx: Int,
    modifier: Modifier,
    onReady: (() -> Unit)?,
    reflection: Boolean = true,
    onShown: ((String) -> Unit)? = null,
) {
    var layers by remember(p.id) { mutableStateOf<CarLayers?>(null) }
    val shown by rememberUpdatedState(onShown)
    var notified by remember(p.id) { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    val wanted by rememberUpdatedState(Wanted(name, paint, widthPx))
    LaunchedEffect(p) {
        // La imagen que se está cargando siempre termina y luego se pasa a la más reciente
        // (las de en medio se saltan): al girar rápido o al arrastrar el selector de color,
        // el auto avanza al ritmo en que se pintan, sin apilar trabajo. Si cada cambio
        // cancelara al anterior, ninguno terminaría y el auto se quedaría quieto.
        snapshotFlow { wanted }.conflate().collect { w ->
            if (w.widthPx <= 0) return@collect
            layers = repo.layers(p, w.name, w.paint, w.widthPx) ?: return@collect
            shown?.invoke(w.name)
            if (!notified) {
                notified = true
                ready?.invoke()
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        drawCarLayers(layers ?: return@Canvas, reflection)
    }
}

/** El auto con su sombra y (si [reflection]) su reflejo, centrado en el lienzo. */
private fun DrawScope.drawCarLayers(c: CarLayers, reflection: Boolean = true) {
    val scale = minOf(size.width / c.car.width, size.height / c.car.height)
    val w = c.car.width * scale
    val h = c.car.height * scale
    drawCarLayersAt(c, Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h)), reflection)
}

/** Las capas con el auto en [car]: la sombra y el reflejo se salen de esa caja. */
private fun DrawScope.drawCarLayersAt(c: CarLayers, car: Rect, reflection: Boolean) {
    c.floorBox(car)?.let { box ->
        c.shadow?.let { drawLayer(it, box) }
        if (reflection) c.reflection?.let { drawLayer(it, box) }
    }
    drawLayer(c.car, car)
}

/** Dónde caen la sombra y el reflejo si el auto ocupa [car]; null sin piso. */
private fun CarLayers.floorBox(car: Rect) =
    floor?.let { f -> Rect(Offset(car.left + f.x * car.width, car.top + f.y * car.width), Size(f.width * car.width, f.height * car.width)) }

/**
 * El auto quieto en tres cuartos, como en el inicio, en una imagen suelta de [widthPx] de ancho (el auto; la
 * sombra y el reflejo pueden agrandarla): para LiteDash Connect. Sin [p], el sedán genérico. Fondo transparente.
 */
suspend fun CarRepository.snapshot(p: CarPackage?, paint: CarPaint, widthPx: Int): Bitmap? {
    if (p == null) {
        val image = ImageBitmap(widthPx, (widthPx / SEDAN_ASPECT).roundToInt())
        withContext(Dispatchers.Default) { image.drawOffscreen { drawSedan(paint.color) } }
        return image.asAndroidBitmap()
    }
    val c = layers(p, baseFrame(restFrame(p.spin)), paint, widthPx) ?: return null
    val car = Rect(Offset.Zero, Size(widthPx.toFloat(), widthPx * c.car.height.toFloat() / c.car.width))
    val all = c.floorBox(car)?.let { car.union(it) } ?: car
    val image = ImageBitmap(all.width.roundToInt(), all.height.roundToInt())
    return withContext(Dispatchers.Default) {
        image.drawOffscreen { drawCarLayersAt(c, car.translate(-all.left, -all.top), reflection = true) }
        image.asAndroidBitmap().trimmed()
    }
}

/**
 * Lista para mostrarse sobre cualquier fondo: la sombra y el reflejo traen un velo casi invisible que llena su caja
 * y corta de golpe en la orilla (en fondo claro se ve como un rectángulo), así que se le resta [HAZE_ALPHA] a la
 * transparencia; y sin el aire de las orillas (la caja del piso es mucho más grande que lo que se ve), con un margen
 * chico, para que el auto llene el espacio donde lo pongan.
 */
private fun Bitmap.trimmed(): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    var top = height
    var bottom = -1
    var left = width
    var right = -1
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        val alpha = pixels[i] ushr 24
        if (alpha == 255) {
            // Lo sólido (el auto) no cambia.
        } else if (alpha <= HAZE_ALPHA) {
            pixels[i] = 0
            continue
        } else {
            val faded = (alpha - HAZE_ALPHA) * 255 / (255 - HAZE_ALPHA)
            pixels[i] = (faded shl 24) or (pixels[i] and 0xFFFFFF)
        }
        if (y < top) top = y
        bottom = y
        if (x < left) left = x
        if (x > right) right = x
    }
    if (bottom < 0) return this
    val margin = width / 50
    val x0 = (left - margin).coerceAtLeast(0)
    val y0 = (top - margin).coerceAtLeast(0)
    val x1 = (right + margin).coerceAtMost(width - 1)
    val y1 = (bottom + margin).coerceAtMost(height - 1)
    val out = Bitmap.createBitmap(x1 - x0 + 1, y1 - y0 + 1, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, y0 * width + x0, width, 0, 0, out.width, out.height)
    return out
}

/** El velo de la sombra y el reflejo (de 255): menos que esto no se nota en fondo oscuro y en claro estorba. */
private const val HAZE_ALPHA = 10

private fun Rect.union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

private fun ImageBitmap.drawOffscreen(block: DrawScope.() -> Unit) =
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(this), Size(width.toFloat(), height.toFloat()), block)

/** Qué imagen debe verse: cuadro, pintura y ancho. */
private data class Wanted(val name: String, val paint: CarPaint, val widthPx: Int)

private fun DrawScope.drawLayer(img: ImageBitmap, box: Rect) = drawImage(
    img,
    dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
    dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
    filterQuality = FilterQuality.Medium,
)

/** Los cuadros del giro en orden: los del paquete ("NN") y, en calidad alta, el de en medio de cada par ("mNN"). */
private fun spinFrames(p: CarPackage): List<String> = (0 until p.spin).flatMap { i ->
    if (p.high) listOf(baseFrame(i), "${CarFiles.SPIN}/m%02d".format(i)) else listOf(baseFrame(i))
}

private fun baseFrame(i: Int) = "${CarFiles.SPIN}/%02d".format(i)

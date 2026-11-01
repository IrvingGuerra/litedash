package com.irvingguerra.litedash.feature.car

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/** Anchos de la pieza que hay que arrastrar para dar una vuelta completa. */
private const val WIDTHS_PER_TURN = 0.83f

/** Lo que tarda el auto en asentarse en el cuadro más cercano al soltar (calidad alta). */
private const val SETTLE_MS = 140

/** Cuadros que se preparan adelante, hacia donde va el dedo. */
private const val PREFETCH = 3

/** El cuadro de tres cuartos con el frente a la izquierda (3/8 de vuelta): donde empieza el auto. */
private fun restFrame(count: Int) = count * 3 / 8

/**
 * El auto del paquete, pintado del [color]: gira con el dedo a los lados (como
 * una vuelta real, con su sombra y su reflejo en el piso) y se queda donde lo
 * dejas. [angle] es la vuelta en fracción (0..1; null = tres cuartos con el
 * frente a la izquierda) y [onAngle] la recibe al soltar para guardarla.
 * [onReady] se llama una vez, cuando ya se ve (para animar sin que se vea vacío).
 * En calidad alta, entre un cuadro y el siguiente se funden según el dedo (y al
 * soltar se asienta en el más cercano): el giro se ve continuo, sin escalones.
 */
@Composable
fun CarTurntable(
    repo: CarRepository,
    p: CarPackage,
    color: Color,
    modifier: Modifier = Modifier,
    angle: Float? = null,
    onAngle: ((Float) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
) {
    val frames = remember(p) { spinFrames(p) }
    val n = frames.size
    var widthPx by remember { mutableIntStateOf(0) }
    val saved = angle?.let { (it * n).roundToInt().mod(n) } ?: restFrame(n)
    // Un solo estado por auto: el gesto sigue moviendo este mismo aunque cambie el
    // ángulo guardado (si se recreara al guardar, el dedo movería uno que ya no se dibuja).
    var frame by remember(p) { mutableIntStateOf(saved) }
    // Cuánto se ha avanzado hacia el cuadro siguiente (0..1): solo en calidad alta y
    // solo se lee al dibujar, así moverlo no recompone nada.
    var blend by remember(p) { mutableFloatStateOf(0f) }
    var settling by remember(p) { mutableStateOf<Job?>(null) }
    var direction by remember(p) { mutableIntStateOf(1) }
    var dragging by remember(p) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Si lo giraron en otra pantalla (Ajustes o la vista en grande), aquí se pone igual.
    LaunchedEffect(saved) { if (!dragging) frame = saved }
    // Se preparan los cuadros que siguen (con sus capas), para que aparezcan al instante.
    // Solo al cambiar de cuadro: al arrastrar el selector de color no se precargan colores de paso.
    val currentColor by rememberUpdatedState(color)
    LaunchedEffect(p, frame, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        for (k in 1..PREFETCH) repo.layers(p, frames[(frame + direction * k + n) % n], currentColor, widthPx)
    }
    // Pasa al cuadro más cercano del fundido y lo deja sin fundir.
    fun settle() {
        if (blend >= 0.5f) frame = (frame + 1) % n
        blend = 0f
    }
    val turn = Modifier.pointerInput(p) {
        // La misma vuelta por arrastre, tenga los cuadros que tenga (más fluidez = pasos más chicos).
        val step = size.width * WIDTHS_PER_TURN / n
        // Posición del dedo en cuadros desde donde empezó el arrastre (positivo = hacia "frame + 1").
        var moved = 0f
        var start = 0
        fun release() {
            dragging = false
            if (p.high && blend > 0f) {
                // Se asienta en el cuadro más cercano con una animación corta.
                val target = blend.roundToInt().toFloat()
                settling = scope.launch {
                    animate(blend, target, animationSpec = tween(SETTLE_MS)) { v, _ -> blend = v }
                    settle()
                    onAngle?.invoke(frame.toFloat() / n)
                }
            } else {
                onAngle?.invoke(frame.toFloat() / n)
            }
        }
        detectHorizontalDragGestures(
            onDragStart = {
                // Si aún se estaba asentando, se queda en el cuadro más cercano y de ahí sigue.
                settling?.cancel()
                settle()
                moved = 0f
                start = frame
                dragging = true
            },
            onDragEnd = ::release,
            onDragCancel = ::release,
        ) { change, dx ->
            change.consume()
            val before = moved
            moved -= dx / step
            direction = if (moved > before) 1 else -1
            if (p.high) {
                // El de abajo es el cuadro entero y encima se funde el siguiente.
                val whole = floor(moved).toInt()
                frame = (start + whole).mod(n)
                blend = moved - whole
            } else {
                // Calidad baja: cuadro por cuadro, como siempre.
                frame = (start + moved.toInt()).mod(n)
            }
        }
    }
    CarLayersImage(
        repo, p, frames[frame], frames[(frame + 1) % n].takeIf { p.high }, { blend }, color, widthPx,
        modifier.onSizeChanged { widthPx = it.width }.then(turn), onReady,
    )
}

/** El auto quieto en tres cuartos, pintado del [color]: para las tarjetas de la lista de modelos. */
@Composable
fun CarStill(repo: CarRepository, p: CarPackage, color: Color, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableIntStateOf(0) }
    CarLayersImage(repo, p, baseFrame(restFrame(p.spin)), null, { 0f }, color, widthPx, modifier.onSizeChanged { widthPx = it.width }, onReady = null)
}

/**
 * Dibuja la imagen [name] con sus capas: si trae piso, la sombra y el reflejo
 * van debajo y se salen de la caja hacia abajo y a los lados, así el auto parece
 * parado en el fondo de la pantalla y no dentro de una tarjeta. Mientras carga
 * la siguiente se queda la anterior (el giro no parpadea). Con [next], esa imagen
 * se funde encima según [blend] (0..1, leído solo al dibujar).
 */
@Composable
private fun CarLayersImage(
    repo: CarRepository,
    p: CarPackage,
    name: String,
    next: String?,
    blend: () -> Float,
    color: Color,
    widthPx: Int,
    modifier: Modifier,
    onReady: (() -> Unit)?,
) {
    var layers by remember(p.id) { mutableStateOf<Loaded?>(null) }
    var notified by remember(p.id) { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    val wanted by rememberUpdatedState(Wanted(name, next, color, widthPx))
    LaunchedEffect(p) {
        // La imagen que se está cargando siempre termina y luego se pasa a la más reciente
        // (las de en medio se saltan): al girar rápido o al arrastrar el selector de color,
        // el auto avanza al ritmo en que se pintan, sin apilar trabajo. Si cada cambio
        // cancelara al anterior, ninguno terminaría y el auto se quedaría quieto.
        snapshotFlow { wanted }.conflate().collect { w ->
            if (w.widthPx <= 0) return@collect
            val base = repo.layers(p, w.name, w.color, w.widthPx) ?: return@collect
            layers = Loaded(w.name, base, w.next?.let { repo.layers(p, it, w.color, w.widthPx) })
            if (!notified) {
                notified = true
                ready?.invoke()
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val (loaded, base, over) = layers ?: return@Canvas
        // El fundido es del cuadro que pide el dedo: si el que está listo aún es otro
        // (giro muy rápido), se ve ese solo, sin fundir, hasta que llegue el bueno.
        val t = if (over == null || loaded != wanted.name) 0f else blend().coerceIn(0f, 1f)
        val scale = minOf(size.width / base.car.width, size.height / base.car.height)
        val w = base.car.width * scale
        val h = base.car.height * scale
        val origin = Offset((size.width - w) / 2, (size.height - h) / 2)
        // El piso de los dos se reparte (si no, a media vuelta la sombra se oscurece);
        // el auto de abajo va completo y el de encima se funde (si no, se transparenta).
        drawFloor(base, origin, w, 1f - t)
        if (over != null && t > 0f) drawFloor(over, origin, w, t)
        drawLayer(base.car, Rect(origin, Size(w, h)))
        if (over != null && t > 0f) drawLayer(over.car, Rect(origin, Size(w, h)), t)
    }
}

private fun DrawScope.drawFloor(c: CarLayers, origin: Offset, w: Float, alpha: Float) {
    val f = c.floor ?: return
    val box = Rect(Offset(origin.x + f.x * w, origin.y + f.y * w), Size(f.width * w, f.height * w))
    c.shadow?.let { drawLayer(it, box, alpha) }
    c.reflection?.let { drawLayer(it, box, alpha) }
}

/** Qué imagen debe verse: cuadro, color y ancho. */
private data class Wanted(val name: String, val next: String?, val color: Color, val widthPx: Int)

/** Lo que ya está listo para dibujar: el cuadro [name] y, si se funde, el siguiente. */
private data class Loaded(val name: String, val base: CarLayers, val over: CarLayers?)

private fun DrawScope.drawLayer(img: ImageBitmap, box: Rect, alpha: Float = 1f) = drawImage(
    img,
    dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
    dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
    alpha = alpha,
    filterQuality = FilterQuality.Medium,
)

/** Los cuadros del giro en orden: los del paquete ("NN") y, en calidad alta, el de en medio de cada par ("mNN"). */
private fun spinFrames(p: CarPackage): List<String> = (0 until p.spin).flatMap { i ->
    if (p.high) listOf(baseFrame(i), "${CarFiles.SPIN}/m%02d".format(i)) else listOf(baseFrame(i))
}

private fun baseFrame(i: Int) = "${CarFiles.SPIN}/%02d".format(i)

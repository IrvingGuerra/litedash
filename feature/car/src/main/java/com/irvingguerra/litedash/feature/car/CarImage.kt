package com.irvingguerra.litedash.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Anchos de la pieza que hay que arrastrar para dar una vuelta completa. */
private const val WIDTHS_PER_TURN = 0.83f

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
    /** Camino a las llantas: gira solo y sube la cámara (solo el auto en grande). */
    rise: CarRise? = null,
) {
    val frames = remember(p) { spinFrames(p) }
    val n = frames.size
    var widthPx by remember { mutableIntStateOf(0) }
    val saved = angle?.let { (it * n).roundToInt().mod(n) } ?: restFrame(n)
    // Un solo estado por auto: el gesto sigue moviendo este mismo aunque cambie el
    // ángulo guardado (si se recreara al guardar, el dedo movería uno que ya no se dibuja).
    var frame by remember(p) { mutableIntStateOf(saved) }
    var direction by remember(p) { mutableIntStateOf(1) }
    var dragging by remember(p) { mutableStateOf(false) }
    // Si lo giraron en otra pantalla (Ajustes o la vista en grande), aquí se pone igual.
    LaunchedEffect(saved) { if (!dragging && rise?.active != true) frame = saved }
    // Se preparan los cuadros que siguen (con sus capas), para que aparezcan al instante.
    // Solo al cambiar de cuadro: al arrastrar el selector de color no se precargan colores de paso.
    val currentColor by rememberUpdatedState(color)
    LaunchedEffect(p, frame, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        for (k in 1..PREFETCH) repo.layers(p, frames[(frame + direction * k + n) % n], currentColor, widthPx)
    }
    val turn = Modifier.pointerInput(p) {
        // La misma vuelta por arrastre, tenga los cuadros que tenga (más fluidez = pasos más chicos).
        val step = size.width * WIDTHS_PER_TURN / n
        var accumulated = 0f
        fun release() {
            dragging = false
            // Camino a las llantas no se guarda nada.
            if (rise?.active != true) onAngle?.invoke(frame.toFloat() / n)
        }
        detectHorizontalDragGestures(
            onDragStart = { accumulated = 0f; dragging = true },
            onDragEnd = ::release,
            onDragCancel = ::release,
        ) { change, dx ->
            change.consume()
            if (rise?.active == true) return@detectHorizontalDragGestures
            accumulated += dx
            while (accumulated > step) { accumulated -= step; frame = (frame - 1 + n) % n; direction = -1 }
            while (accumulated < -step) { accumulated += step; frame = (frame + 1) % n; direction = 1 }
        }
    }
    CarLayersImage(repo, p, frames[frame], color, widthPx, modifier.onSizeChanged { widthPx = it.width }.then(turn), onReady, rise)
}

/** La vuelta en que el auto se ve de espaldas (en 0.25 se ve de frente; en 3/8, de tres cuartos). */
const val REAR_TURN = 0.75f

/**
 * El auto quieto, pintado del [color]: en tres cuartos para las tarjetas de la lista
 * de modelos, o en la vuelta [turn] (como [REAR_TURN], de espaldas en la carretera).
 */
@Composable
fun CarStill(repo: CarRepository, p: CarPackage, color: Color, modifier: Modifier = Modifier, turn: Float? = null, onReady: (() -> Unit)? = null) {
    var widthPx by remember { mutableIntStateOf(0) }
    val frame = turn?.let { (it * p.spin).roundToInt().mod(p.spin) } ?: restFrame(p.spin)
    CarLayersImage(repo, p, baseFrame(frame), color, widthPx, modifier.onSizeChanged { widthPx = it.width }, onReady)
}

/** El auto desde arriba ([CarViews.TOP_VIEW] del paquete) pintado del [color]; solo si [CarPackage.topView]. */
@Composable
internal fun CarTopImage(repo: CarRepository, p: CarPackage, color: Color, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableIntStateOf(0) }
    CarLayersImage(repo, p, CarViews.TOP_VIEW, color, widthPx, modifier.onSizeChanged { widthPx = it.width }, null)
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
    color: Color,
    widthPx: Int,
    modifier: Modifier,
    onReady: (() -> Unit)?,
    rise: CarRise? = null,
) {
    var layers by remember(p.id) { mutableStateOf<CarLayers?>(null) }
    var notified by remember(p.id) { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    val wanted by rememberUpdatedState(Wanted(name, color, widthPx))
    LaunchedEffect(p) {
        // La imagen que se está cargando siempre termina y luego se pasa a la más reciente
        // (las de en medio se saltan): al girar rápido o al arrastrar el selector de color,
        // el auto avanza al ritmo en que se pintan, sin apilar trabajo. Si cada cambio
        // cancelara al anterior, ninguno terminaría y el auto se quedaría quieto.
        snapshotFlow { wanted }.conflate().collect { w ->
            if (w.widthPx <= 0) return@collect
            layers = repo.layers(p, w.name, w.color, w.widthPx) ?: return@collect
            if (!notified) {
                notified = true
                ready?.invoke()
            }
        }
    }
    val place = if (rise == null) Modifier else Modifier.onGloballyPositioned { rise.canvasOrigin = it.positionInRoot() }
    Canvas(modifier.fillMaxSize().then(place)) {
        if (rise != null && rise.active && drawRise(rise, layers)) return@Canvas
        drawCarLayers(layers ?: return@Canvas)
    }
}

/**
 * Camino a las llantas: el auto gira en grande hasta quedar de espaldas ([CarRise.spin]), luego el cuadro
 * de la subida que toca según [CarRise.progress] y, con [CarRise.morph], el auto viajando a la
 * vista de las llantas (sin subida, solo se encoge un poco). Devuelve si ya dibujó.
 */
private fun DrawScope.drawRise(r: CarRise, layers: CarLayers?): Boolean {
    val g = r.spin()
    val t = r.progress()
    val m = r.morph()
    val turn = r.turnFrames
    if (t <= 0f || !r.hasFrames) {
        val s = 1f - 0.12f * m
        // El cuadro del giro que toca (ya pintado en grande: solo se dibuja); al empezar, el del usuario.
        val step = (g * turn.size).toInt().coerceAtMost(turn.size)
        val now = if (step == 0) layers else turn[step - 1]
        withTransform({ scale(s, s, pivot = center) }) { now?.let { drawCarLayers(it) } }
        return true
    }
    val img = r.frames[((t * (r.count - 1)).roundToInt()).coerceIn(0, r.count - 1)] ?: return false
    val scale = minOf(size.width / img.width, size.height / img.height)
    val w = img.width * scale
    val h = img.height * scale
    val box = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
    val top = r.topBox
    val goal = r.target
    if (m > 0f && top != null && !goal.isEmpty) {
        // El auto del último cuadro (en este lienzo) va hacia el de la vista de las llantas.
        val from = Rect(box.left + top.left * w, box.top + top.top * h, box.left + top.right * w, box.top + top.bottom * h)
        val to = goal.translate(-r.canvasOrigin)
        val s = 1f + (to.height / from.height - 1f) * m
        withTransform({
            translate((to.center.x - from.center.x) * m, (to.center.y - from.center.y) * m)
            scale(s, s, pivot = from.center)
        }) { drawLayer(img, box) }
    } else drawLayer(img, box)
    return true
}

/** El auto con su sombra y su reflejo, centrado en el lienzo. */
private fun DrawScope.drawCarLayers(c: CarLayers) {
    val scale = minOf(size.width / c.car.width, size.height / c.car.height)
    val w = c.car.width * scale
    val h = c.car.height * scale
    val origin = Offset((size.width - w) / 2, (size.height - h) / 2)
    c.floor?.let { f ->
        val box = Rect(Offset(origin.x + f.x * w, origin.y + f.y * w), Size(f.width * w, f.height * w))
        c.shadow?.let { drawLayer(it, box) }
        c.reflection?.let { drawLayer(it, box) }
    }
    drawLayer(c.car, Rect(origin, Size(w, h)))
}

/** Qué imagen debe verse: cuadro, color y ancho. */
private data class Wanted(val name: String, val color: Color, val widthPx: Int)

private fun DrawScope.drawLayer(img: ImageBitmap, box: Rect) = drawImage(
    img,
    dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
    dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
    filterQuality = FilterQuality.Medium,
)

/**
 * Los cuadros del giro, por el lado corto, de la vuelta [from] (donde lo dejó el usuario) a [to]
 * (sin el de salida; con el de llegada). Solo los del paquete (cada 15°): girar en grande no
 * necesita los de en medio y así son pocos que preparar. Si el usuario lo dejó en un cuadro de en
 * medio (calidad alta), se sale del de al lado hacia el destino: sin brincar medio cuadro de más.
 */
fun spinPath(p: CarPackage, from: Float?, to: Float): List<String> {
    val n = p.spin
    // La vuelta en cuadros del paquete; casi entera cuenta como entera (redondeo del guardado).
    val at = from?.let { (it * n).mod(n.toFloat()).let { x -> if (abs(x - x.roundToInt()) < 0.01f) x.roundToInt().toFloat() else x } }
        ?: restFrame(n).toFloat()
    val goal = (to * n).roundToInt().mod(n)
    // Hacia dónde queda el destino por el lado corto (en cuadros, con fracción).
    val ahead = (goal - at).mod(n.toFloat()).let { if (it > n / 2f) it - n else it }
    val start = (if (ahead >= 0) floor(at) else ceil(at)).toInt()
    val diff = (goal - start).mod(n).let { if (it > n / 2) it - n else it }
    val step = if (diff >= 0) 1 else -1
    return (1..abs(diff)).map { baseFrame((start + step * it).mod(n)) }
}

/** Los cuadros del giro en orden: los del paquete ("NN") y, en calidad alta, el de en medio de cada par ("mNN"). */
private fun spinFrames(p: CarPackage): List<String> = (0 until p.spin).flatMap { i ->
    if (p.high) listOf(baseFrame(i), "${CarFiles.SPIN}/m%02d".format(i)) else listOf(baseFrame(i))
}

private fun baseFrame(i: Int) = "${CarFiles.SPIN}/%02d".format(i)

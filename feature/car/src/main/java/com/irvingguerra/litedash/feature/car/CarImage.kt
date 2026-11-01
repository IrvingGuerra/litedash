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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlin.math.roundToInt

/** El giro completo: 96 posiciones, una cada 3.75°. El paquete trae una de cada 4. */
private const val FULL_SPIN = 96

/** Pausa antes de repintar con otro color: al arrastrar el selector solo se tiñe el último. */
private const val COLOR_SETTLE_MS = 60L

/**
 * El auto del paquete, pintado del [color]: gira con el dedo a los lados (como
 * una vuelta real, con su sombra y su reflejo en el piso) y se queda donde lo
 * dejas. [angle] es la vuelta en fracción (0..1; null = tres cuartos con el
 * frente a la izquierda) y [onAngle] la recibe al soltar para guardarla.
 * [onReady] se llama una vez, cuando ya se ve (para animar sin que se vea vacío).
 * Un paquete sin giro muestra su primera vista fija.
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
    if (n == 0) {
        val view = p.views.firstOrNull() ?: return
        CarLayersImage(repo, p, view, color, widthPx, modifier.onSizeChanged { widthPx = it.width }, onReady)
        return
    }
    val saved = angle?.let { (it * n).roundToInt().mod(n) } ?: (n * 3 / 8)
    // Un solo estado por auto: el gesto sigue moviendo este mismo aunque cambie el
    // ángulo guardado (si se recreara al guardar, el dedo movería uno que ya no se dibuja).
    var frame by remember(p) { mutableIntStateOf(saved) }
    var direction by remember(p) { mutableIntStateOf(1) }
    var dragging by remember(p) { mutableStateOf(false) }
    // Si lo giraron en otra pantalla (Ajustes o la vista en grande), aquí se pone igual.
    LaunchedEffect(saved) { if (!dragging) frame = saved }
    // Mientras giras, los cuadros van a media resolución (salen al instante y en movimiento
    // no se nota); al soltar, el cuadro donde se queda se carga completo.
    val drawWidth = if (dragging) widthPx / 2 else widthPx
    // Se preparan los cuadros que siguen (con sus capas), para que aparezcan al instante.
    LaunchedEffect(p, frame, color, drawWidth) {
        if (drawWidth <= 0) return@LaunchedEffect
        for (k in 1..3) repo.layers(p, frames[(frame + direction * k + n) % n], color, drawWidth)
    }
    val turn = Modifier.pointerInput(p) {
        // Una vuelta completa por cada 1.2 anchos de la pieza, tenga los cuadros que tenga.
        val step = size.width / (n * 1.2f)
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
    CarLayersImage(repo, p, frames[frame], color, drawWidth, modifier.onSizeChanged { widthPx = it.width }.then(turn), onReady)
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
) {
    var layers by remember(p.id) { mutableStateOf<CarLayers?>(null) }
    var shownColor by remember(p.id) { mutableStateOf(color) }
    var notified by remember(p.id) { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    val wanted by rememberUpdatedState(Wanted(name, color, widthPx))
    LaunchedEffect(p) {
        // La imagen que se está cargando siempre termina y luego se pasa a la más reciente
        // (las de en medio se saltan). Si cada cuadro nuevo cancelara al anterior, al girar
        // más rápido de lo que tarda uno en cargar, ninguno terminaría y el auto se quedaría quieto.
        snapshotFlow { wanted }.conflate().collect { w ->
            if (w.widthPx <= 0) return@collect
            // Solo al cambiar de color: al girar, cada cuadro debe verse al momento.
            if (layers != null && w.color != shownColor) delay(COLOR_SETTLE_MS)
            layers = repo.layers(p, w.name, w.color, w.widthPx) ?: return@collect
            shownColor = w.color
            if (!notified) {
                notified = true
                ready?.invoke()
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val c = layers ?: return@Canvas
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
 * Los cuadros del giro en orden de ángulo, según la [Smoothness] instalada: las
 * del paquete son una de cada 4 ("NN"), la fluidez media agrega las de en medio
 * ("mNN") y la máxima las que quedan ("xNN"). Paquetes con otro número de
 * cuadros, tal cual vienen.
 */
fun spinFrames(p: CarPackage): List<String> {
    if (p.spin * 4 != FULL_SPIN) return (0 until p.spin).map { "${CarFiles.SPIN}/%02d".format(it) }
    return (0 until FULL_SPIN).mapNotNull { i ->
        when {
            i % 4 == 0 -> "${CarFiles.SPIN}/%02d".format(i / 4)
            i % 4 == 2 -> if (p.smoothness >= Smoothness.MEDIUM) "${CarFiles.SPIN}/m%02d".format(i / 4) else null
            else -> if (p.smoothness >= Smoothness.MAX) "${CarFiles.SPIN}/x%02d".format(i / 2) else null
        }
    }
}

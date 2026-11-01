package com.irvingguerra.litedash.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class Layers(val car: ImageBitmap, val shadow: ImageBitmap?, val reflection: ImageBitmap?)

/**
 * Dibuja una imagen del paquete con la pintura del [color] elegido (ya teñida
 * por el repositorio). Si la vista trae piso, debajo van su sombra y su reflejo
 * tenue, que se salen de la caja hacia abajo y a los lados: así el auto parece
 * estar parado en el fondo de la pantalla, no dentro de una tarjeta.
 */
@Composable
fun CarImage(
    repo: CarRepository,
    p: CarPackage,
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    /**
     * Para los cuadros sin piso (el giro en el inicio): el mismo auto volteado y
     * desvanecido bajo las llantas, como el reflejo tenue de la vista con piso.
     * [ground] es la línea de las llantas en fracción del alto de la imagen.
     */
    ground: Float? = null,
    /** Se llama cuando ya está pintada (para animar sin que se vea vacía). */
    onReady: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val floor = p.floor[name]
        // Se queda la imagen anterior mientras carga la nueva (el giro no parpadea).
        var layers by remember(p.id) { mutableStateOf<Layers?>(null) }
        var paintedColor by remember(p.id) { mutableStateOf<Color?>(null) }
        LaunchedEffect(p, name, color, widthPx) {
            // Al arrastrar el selector de color llegan muchos colores seguidos: solo
            // se tiñe el último (si cambia antes, este se cancela aquí sin trabajar).
            // Solo al cambiar de color: al girar, cada cuadro debe verse al momento.
            if (layers != null && paintedColor != null && paintedColor != color) delay(60)
            paintedColor = color
            val car = repo.painted(p, name, color, widthPx) ?: return@LaunchedEffect
            layers = if (floor == null) Layers(car, null, null) else {
                // Las capas del piso son suaves: van a la mitad de resolución.
                val floorWidth = (widthPx * floor.width / 2).roundToInt()
                Layers(car, repo.image(p, name + CarViews.SHADOW_SUFFIX, floorWidth), repo.painted(p, name + CarViews.REFLECTION_SUFFIX, color, floorWidth))
            }
            onReady?.invoke()
        }
        layers?.let { c ->
            Canvas(Modifier.fillMaxSize()) {
                val scale = minOf(size.width / c.car.width, size.height / c.car.height)
                val w = c.car.width * scale
                val h = c.car.height * scale
                val origin = Offset((size.width - w) / 2, (size.height - h) / 2)
                if (floor != null) {
                    val box = Rect(Offset(origin.x + floor.x * w, origin.y + floor.y * w), Size(floor.width * w, floor.height * w))
                    c.shadow?.let { drawLayer(it, box) }
                    c.reflection?.let { drawLayer(it, box) }
                }
                if (floor == null && ground != null) {
                    val line = origin.y + ground * h
                    // Como el reflejo horneado de la vista con piso: fuerte junto a las llantas y
                    // se apaga hacia abajo (casi exponencial) en poco más de medio auto.
                    drawContext.canvas.saveLayer(Rect(0f, line, size.width, line + h * 0.6f), Paint())
                    withTransform({ scale(1f, -1f, Offset(0f, line)) }) {
                        drawLayer(c.car, Rect(origin, Size(w, h)), alpha = REFLECTION_ALPHA)
                    }
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            0.35f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                            startY = line,
                            endY = line + h * 0.6f,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    drawContext.canvas.restore()
                }
                drawLayer(c.car, Rect(origin, Size(w, h)))
            }
        }
    }
}

private const val REFLECTION_ALPHA = 0.36f

private fun DrawScope.drawLayer(img: ImageBitmap, box: Rect, alpha: Float = 1f) = drawImage(
    img,
    dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
    dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
    alpha = alpha,
    filterQuality = FilterQuality.Medium,
)

/**
 * Dónde tocan el piso las llantas en un cuadro del giro (fracción del alto): la
 * última fila con auto. Se mide una vez por auto (la cámara del giro no cambia).
 */
private suspend fun groundLine(img: ImageBitmap): Float = withContext(Dispatchers.Default) {
    val w = img.width
    val h = img.height
    val row = IntArray(w)
    for (y in h - 1 downTo 0) {
        img.readPixels(row, startY = y, width = w, height = 1)
        if (row.any { (it ushr 24) > 40 }) return@withContext (y + 1f) / h
    }
    1f
}

/**
 * El auto del inicio: quieto se ve la vista con piso ([rest]); al arrastrar a los
 * lados gira como en Ajustes (con su reflejo) y al soltar regresa girando a su
 * lugar y vuelve el piso. Los cuadros del giro ya vienen en el paquete del auto.
 */
@Composable
fun CarTurntable(
    repo: CarRepository,
    p: CarPackage,
    rest: String,
    color: Color,
    modifier: Modifier = Modifier,
    onReady: (() -> Unit)? = null,
) {
    if (p.spin <= 0) {
        CarImage(repo, p, rest, color, modifier, onReady = onReady)
        return
    }
    val frames = remember(p) { spinFrames(p) }
    val n = frames.size
    // El cuadro de tres cuartos, el mismo con el que empieza el giro de Ajustes.
    val home = n / 8
    var frame by remember(p) { mutableIntStateOf(home) }
    var spinning by remember(p) { mutableStateOf(false) }
    var ground by remember(p.id) { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var back by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(p, widthPx) {
        if (widthPx <= 0 || ground != null) return@LaunchedEffect
        repo.painted(p, frames[home], color, widthPx)?.let { ground = groundLine(it) }
    }
    val restAlpha by animateFloatAsState(if (spinning) 0f else 1f, tween(220), label = "piso")
    Box(
        modifier.onSizeChanged { widthPx = it.width }.pointerInput(p) {
            val step = size.width / (n * 1.2f)
            var accumulated = 0f
            detectHorizontalDragGestures(
                onDragStart = { back?.cancel(); spinning = true; accumulated = 0f },
                onDragEnd = {
                    // De regreso por el lado corto, cuadro por cuadro (~0.3 s la media vuelta).
                    back = scope.launch {
                        val wait = (600L / n).coerceAtLeast(8L)
                        while (frame != home) {
                            val ahead = (home - frame + n) % n
                            frame = if (ahead <= n / 2) (frame + 1) % n else (frame - 1 + n) % n
                            delay(wait)
                        }
                        spinning = false
                    }
                },
                onDragCancel = { frame = home; spinning = false },
            ) { change, dx ->
                change.consume()
                accumulated += dx
                while (accumulated > step) { accumulated -= step; frame = (frame - 1 + n) % n }
                while (accumulated < -step) { accumulated += step; frame = (frame + 1) % n }
            }
        },
    ) {
        if (restAlpha < 1f) CarImage(repo, p, frames[frame], color, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - restAlpha }, ground)
        if (restAlpha > 0f) CarImage(repo, p, rest, color, Modifier.fillMaxSize().graphicsLayer { alpha = restAlpha }, onReady = onReady)
    }
}

/**
 * Los cuadros del giro en orden de ángulo, según la [Smoothness] instalada. El
 * giro completo tiene 96 posiciones (cada 3.75°): las del paquete son una de
 * cada 4 ("NN"), la fluidez media agrega las de en medio ("mNN") y la máxima
 * las que quedan ("xNN").
 */
fun spinFrames(p: CarPackage): List<String> {
    if (p.spin != 24) return (0 until p.spin).map { "${CarFiles.SPIN}/%02d".format(it) }
    return (0 until 96).mapNotNull { i ->
        when {
            i % 4 == 0 -> "${CarFiles.SPIN}/%02d".format(i / 4)
            i % 4 == 2 -> if (p.smoothness >= Smoothness.MEDIUM) "${CarFiles.SPIN}/m%02d".format(i / 4) else null
            else -> if (p.smoothness >= Smoothness.MAX) "${CarFiles.SPIN}/x%02d".format(i / 2) else null
        }
    }
}

/**
 * Giro falso 3D: arrastrar a los lados cambia de cuadro (una vuelta completa
 * por cada 0.8 anchos, tenga los cuadros que tenga). Solo carga el cuadro que se
 * ve (con caché), así que una pantalla de 1 GB lo aguanta.
 */
@Composable
fun CarSpin(repo: CarRepository, p: CarPackage, color: Color, modifier: Modifier = Modifier) {
    if (p.spin <= 0) {
        CarImage(repo, p, p.views.firstOrNull() ?: CarViews.THREE_QUARTER, color, modifier)
        return
    }
    val frames = remember(p) { spinFrames(p) }
    val n = frames.size
    // Se empieza en tres cuartos (-90 + 45 grados), sin importar la fluidez.
    var frame by remember(p) { mutableStateOf(n / 8) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    // Mientras giras, se preparan los cuadros que siguen (bajados y teñidos) para
    // que cada uno aparezca al instante.
    var widthPx by remember { mutableStateOf(0) }
    var direction by remember { mutableStateOf(1) }
    LaunchedEffect(p, frame, color, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        for (k in 1..3) repo.painted(p, frames[(frame + direction * k + n) % n], color, widthPx)
    }
    Box(
        modifier.onSizeChanged { widthPx = it.width }.pointerInput(p) {
            val step = size.width / (n * 1.2f)
            detectHorizontalDragGestures { change, dx ->
                change.consume()
                accumulated += dx
                while (accumulated > step) { accumulated -= step; frame = (frame - 1 + n) % n; direction = -1 }
                while (accumulated < -step) { accumulated += step; frame = (frame + 1) % n; direction = 1 }
            }
        },
    ) {
        CarImage(repo, p, frames[frame.coerceIn(0, n - 1)], color, Modifier.fillMaxSize())
    }
}

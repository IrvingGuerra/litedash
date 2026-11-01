package com.irvingguerra.litedash.feature.car

import androidx.compose.foundation.Canvas
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
fun CarImage(repo: CarRepository, p: CarPackage, name: String, color: Color, modifier: Modifier = Modifier) {
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
                drawLayer(c.car, Rect(origin, Size(w, h)))
            }
        }
    }
}

private fun DrawScope.drawLayer(img: ImageBitmap, box: Rect) = drawImage(
    img,
    dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
    dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
    filterQuality = FilterQuality.Medium,
)

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

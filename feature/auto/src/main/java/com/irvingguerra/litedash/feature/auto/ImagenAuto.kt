package com.irvingguerra.litedash.feature.auto

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class Capas(val auto: ImageBitmap, val sombra: ImageBitmap?, val reflejo: ImageBitmap?)

/**
 * Dibuja una imagen del paquete con la pintura del [color] elegido (ya teñida
 * por el repositorio). Si la vista trae piso, debajo van su sombra y su reflejo
 * tenue, que se salen de la caja hacia abajo y a los lados: así el auto parece
 * estar parado en el fondo de la pantalla, no dentro de una tarjeta.
 */
@Composable
fun ImagenAuto(repo: RepositorioAutos, p: PaqueteAuto, nombre: String, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val anchoPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val piso = p.piso[nombre]
        // Se queda la imagen anterior mientras carga la nueva (el giro no parpadea).
        var capas by remember(p.id) { mutableStateOf<Capas?>(null) }
        LaunchedEffect(p, nombre, color, anchoPx) {
            // Al arrastrar el selector de color llegan muchos colores seguidos: solo
            // se tiñe el último (si cambia antes, este se cancela aquí sin trabajar).
            if (capas != null) delay(60)
            val auto = repo.pintada(p, nombre, color, anchoPx) ?: return@LaunchedEffect
            capas = if (piso == null) Capas(auto, null, null) else {
                // Las capas del piso son suaves: van a la mitad de resolución.
                val anchoPiso = (anchoPx * piso.ancho / 2).roundToInt()
                Capas(auto, repo.imagen(p, "$nombre-sombra", anchoPiso), repo.pintada(p, "$nombre-reflejo", color, anchoPiso))
            }
        }
        capas?.let { c ->
            Canvas(Modifier.fillMaxSize()) {
                val escala = minOf(size.width / c.auto.width, size.height / c.auto.height)
                val w = c.auto.width * escala
                val h = c.auto.height * escala
                val origen = Offset((size.width - w) / 2, (size.height - h) / 2)
                if (piso != null) {
                    val caja = Rect(Offset(origen.x + piso.x * w, origen.y + piso.y * w), Size(piso.ancho * w, piso.alto * w))
                    c.sombra?.let { dibujar(it, caja) }
                    c.reflejo?.let { dibujar(it, caja) }
                }
                dibujar(c.auto, Rect(origen, Size(w, h)))
            }
        }
    }
}

private fun DrawScope.dibujar(img: ImageBitmap, caja: Rect) = drawImage(
    img,
    dstOffset = IntOffset(caja.left.roundToInt(), caja.top.roundToInt()),
    dstSize = IntSize(caja.width.roundToInt(), caja.height.roundToInt()),
    filterQuality = FilterQuality.Medium,
)

/**
 * Los cuadros del giro en orden de ángulo, según la [Fluidez] instalada. El
 * giro completo tiene 96 posiciones (cada 3.75°): las del paquete son una de
 * cada 4 ("NN"), la fluidez media agrega las de en medio ("mNN") y la máxima
 * las que quedan ("xNN").
 */
fun cuadrosGiro(p: PaqueteAuto): List<String> {
    if (p.giro != 24) return (0 until p.giro).map { "giro/%02d".format(it) }
    return (0 until 96).mapNotNull { i ->
        when {
            i % 4 == 0 -> "giro/%02d".format(i / 4)
            i % 4 == 2 -> if (p.fluidez >= Fluidez.MEDIA) "giro/m%02d".format(i / 4) else null
            else -> if (p.fluidez >= Fluidez.MAXIMA) "giro/x%02d".format(i / 2) else null
        }
    }
}

/**
 * Giro falso 3D: arrastrar a los lados cambia de cuadro (una vuelta completa
 * por cada 0.8 anchos, tenga los cuadros que tenga). Solo carga el cuadro que se
 * ve (con caché), así que una pantalla de 1 GB lo aguanta.
 */
@Composable
fun GiroAuto(repo: RepositorioAutos, p: PaqueteAuto, color: Color, modifier: Modifier = Modifier) {
    if (p.giro <= 0) {
        ImagenAuto(repo, p, p.vistas.firstOrNull() ?: "tres_cuartos", color, modifier)
        return
    }
    val cuadros = remember(p) { cuadrosGiro(p) }
    val n = cuadros.size
    // Se empieza en tres cuartos (-90 + 45 grados), sin importar la fluidez.
    var cuadro by remember(p) { mutableStateOf(n / 8) }
    var acumulado by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.pointerInput(p) {
            val paso = size.width / (n * 1.2f)
            detectHorizontalDragGestures { cambio, dx ->
                cambio.consume()
                acumulado += dx
                while (acumulado > paso) { acumulado -= paso; cuadro = (cuadro - 1 + n) % n }
                while (acumulado < -paso) { acumulado += paso; cuadro = (cuadro + 1) % n }
            }
        },
    ) {
        ImagenAuto(repo, p, cuadros[cuadro.coerceIn(0, n - 1)], color, Modifier.fillMaxSize())
    }
}

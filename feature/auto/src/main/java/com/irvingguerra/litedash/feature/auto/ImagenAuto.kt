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
 * Giro falso 3D: arrastrar a los lados cambia de cuadro. Solo carga el cuadro
 * que se ve (con caché), así que una pantalla de 1 GB lo aguanta.
 */
@Composable
fun GiroAuto(repo: RepositorioAutos, p: PaqueteAuto, color: Color, modifier: Modifier = Modifier) {
    if (p.giro <= 0) {
        ImagenAuto(repo, p, p.vistas.firstOrNull() ?: "tres_cuartos", color, modifier)
        return
    }
    // El cuadro de tres cuartos (-90 + 55 grados) es el que se ve primero.
    var cuadro by remember(p.id) { mutableStateOf((p.giro * 55 / 360) % p.giro) }
    var acumulado by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.pointerInput(p.id) {
            val paso = size.width / (p.giro * 1.2f)
            detectHorizontalDragGestures { cambio, dx ->
                cambio.consume()
                acumulado += dx
                while (acumulado > paso) { acumulado -= paso; cuadro = (cuadro - 1 + p.giro) % p.giro }
                while (acumulado < -paso) { acumulado += paso; cuadro = (cuadro + 1) % p.giro }
            }
        },
    ) {
        ImagenAuto(repo, p, "giro/%02d".format(cuadro), color, Modifier.fillMaxSize())
    }
}

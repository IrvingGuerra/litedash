package com.irvingguerra.litedash.feature.auto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Filtro que lleva la pintura gris del render al [color]: imagen × color / gris. */
private fun tinte(color: Color, gris: Float) = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            color.red / gris, 0f, 0f, 0f, 0f,
            0f, color.green / gris, 0f, 0f, 0f,
            0f, 0f, color.blue / gris, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private data class Par(val base: ImageBitmap, val mascara: ImageBitmap)

/**
 * Dibuja una imagen del paquete con la pintura del [color] elegido: primero el
 * render tal cual, y encima el mismo render teñido, recortado por la máscara.
 * Es una sola capa estática: no cuesta nada después de pintarse.
 */
@Composable
fun ImagenAuto(repo: RepositorioAutos, p: PaqueteAuto, nombre: String, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val anchoPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        // Se queda la imagen anterior mientras carga la nueva (el giro no parpadea).
        var par by remember(p.id) { mutableStateOf<Par?>(null) }
        LaunchedEffect(p, nombre, anchoPx) {
            val base = repo.imagen(p, nombre, anchoPx)
            val mascara = repo.imagen(p, "$nombre-pintura", anchoPx)
            if (base != null && mascara != null) par = Par(base, mascara)
        }
        par?.let { Pintado(it, color, p.gris) }
    }
}

@Composable
private fun Pintado(par: Par, color: Color, gris: Float) {
    val filtro = remember(color, gris) { tinte(color, gris) }
    Canvas(Modifier.fillMaxSize()) { dibujar(par.base) }
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        dibujar(par.base, filtro)
        dibujar(par.mascara, blend = BlendMode.DstIn)
    }
}

/** Imagen centrada y ajustada a la caja sin deformarse. */
private fun DrawScope.dibujar(img: ImageBitmap, filtro: ColorFilter? = null, blend: BlendMode = BlendMode.SrcOver) {
    val escala = minOf(size.width / img.width, size.height / img.height)
    val w = (img.width * escala).roundToInt()
    val h = (img.height * escala).roundToInt()
    val origen = Offset((size.width - w) / 2, (size.height - h) / 2)
    drawImage(
        img,
        dstOffset = IntOffset(origen.x.roundToInt(), origen.y.roundToInt()),
        dstSize = IntSize(w, h),
        colorFilter = filtro,
        blendMode = blend,
    )
}

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

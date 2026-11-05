package com.irvingguerra.litedash.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

// El mismo sedán concepto visto desde arriba (el frente hacia arriba), con el lenguaje de la
// maqueta: una sola forma con volumen, cristales oscuros, sin manijas ni faros. Caja de
// 200 × 420; las zonas del Taller se señalan con fracciones de esta misma caja.
private const val TOP_WIDTH = 200f
private const val TOP_HEIGHT = 420f

/** Proporción (ancho / alto) de la caja del auto visto desde arriba. */
const val CAR_TOP_ASPECT = TOP_WIDTH / TOP_HEIGHT

private object TopPaths {
    val body = path("M42 26 C64 12 136 12 158 26 C178 40 185 72 186 122 L186 318 C185 368 178 394 160 404 C140 414 60 414 40 404 C22 394 15 368 14 318 L14 122 C15 72 22 40 42 26 Z")
    // Parabrisas, techo y medallón: la cabina hundida, como en el perfil.
    val windshield = path("M36 124 C72 114 128 114 164 124 L158 178 C128 171 72 171 42 178 Z")
    val roof = path("M42 178 C72 171 128 171 158 178 L158 298 C128 305 72 305 42 298 Z")
    val rearWindow = path("M42 298 C72 305 128 305 158 298 L162 344 C128 337 72 337 38 344 Z")
    // Espejos.
    val mirrors = path("M14 146 L3 142 C0 143 0 157 3 158 L14 160 Z M186 146 L197 142 C200 143 200 157 197 158 L186 160 Z")
    // Llantas: apenas asoman por los costados.
    val wheels = listOf(Offset(14f, 118f), Offset(186f, 118f), Offset(14f, 302f), Offset(186f, 302f))

    private fun path(d: String) = PathParser().parsePathString(d).toPath()
}

// Luz del cielo: más clara adelante y al centro, más oscura hacia atrás y a las orillas.
private val topShade = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.35f),
    0.35f to Color.White.copy(alpha = 0.08f),
    1f to Color.Black.copy(alpha = 0.25f),
    startY = 20f,
    endY = 412f,
)
private val edgeShade = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.18f to Color.Transparent,
    0.82f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.3f),
    startX = 14f,
    endX = 186f,
)
private val hoodGlow = Offset(100f, 70f)
private val hoodShine = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent), hoodGlow, 70f)
private val trunkGlow = Offset(100f, 372f)
private val trunkShine = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent), trunkGlow, 50f)
private val glassShade = Brush.verticalGradient(
    0f to Color(0xFF151A22),
    0.5f to Color(0xFF2A313C),
    1f to Color(0xFF12161C),
    startY = 114f,
    endY = 346f,
)
private val glassShine = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.18f),
    0.5f to Color.Transparent,
    1f to Color.White.copy(alpha = 0.06f),
    start = Offset(36f, 114f),
    end = Offset(164f, 346f),
)
private val tire = Color(0xFF2A2E34)

/**
 * El auto desde arriba para el Taller: el render del paquete de [p] si lo trae (lo baja
 * aparte [CarRepository.fetchTopView]) o, si no, la silueta dibujada. Los dos en la misma
 * caja ([CAR_TOP_ASPECT]), así las zonas del Taller caen en el mismo lugar.
 */
@Composable
fun CarTop(repo: CarRepository, p: CarPackage?, color: Color, modifier: Modifier = Modifier, onReady: (() -> Unit)? = null) {
    // Con la llave del paquete: si se actualiza (llega sin vista desde arriba), se vuelve a pedir.
    LaunchedEffect(p) { repo.fetchTopView(p) }
    if (p?.topView != true) {
        // El dibujo ya está listo al pintarse.
        LaunchedEffect(Unit) { onReady?.invoke() }
        return CarTopDrawing(color, modifier)
    }
    // La sombra sale junto con el auto (sola, un instante, se veía rara).
    var ready by remember(p) { mutableStateOf(false) }
    Box(modifier.aspectRatio(CAR_TOP_ASPECT)) {
        if (ready) Canvas(Modifier.fillMaxSize()) {
            val s = size.width / TOP_WIDTH
            withTransform({ scale(s, s, pivot = Offset.Zero) }) { floorShadow() }
        }
        CarTopImage(repo, p, color, Modifier.fillMaxSize()) {
            ready = true
            onReady?.invoke()
        }
    }
}

/** Sombra suave en el piso, apenas más grande que el auto (en las medidas de la caja). */
private fun DrawScope.floorShadow() {
    val floor = Offset(TOP_WIDTH / 2, TOP_HEIGHT / 2 + 6f)
    withTransform({ scale(1f, TOP_HEIGHT / TOP_WIDTH, pivot = floor) }) {
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), floor, 118f), 118f, floor)
    }
}

/**
 * El auto desde arriba pintado del [color] elegido (el mismo en el Taller y en Llantas).
 * Dibujo estático: se pinta una vez y no cuesta nada hasta que cambie.
 */
@Composable
private fun CarTopDrawing(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(CAR_TOP_ASPECT)) {
        val s = size.width / TOP_WIDTH
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            floorShadow()
            for (w in TopPaths.wheels) drawRoundRect(tire, Offset(w.x - 9f, w.y - 32f), Size(18f, 64f), androidx.compose.ui.geometry.CornerRadius(6f))
            drawPath(TopPaths.mirrors, color)
            drawPath(TopPaths.body, color)
            drawPath(TopPaths.body, topShade)
            clipPath(TopPaths.body) {
                drawRect(edgeShade)
                drawCircle(hoodShine, 70f, hoodGlow)
                drawCircle(trunkShine, 50f, trunkGlow)
            }
            // Cristales: parabrisas y medallón oscuros, techo del color del auto un poco más claro.
            drawPath(TopPaths.windshield, glassShade)
            drawPath(TopPaths.rearWindow, glassShade)
            drawPath(TopPaths.roof, Color.White.copy(alpha = 0.1f))
            drawPath(TopPaths.windshield, glassShine)
            drawPath(TopPaths.rearWindow, glassShine)
            drawPath(TopPaths.roof, Color.Black.copy(alpha = 0.18f), style = Stroke(1.2f))
            // Contorno apenas visible: un auto oscuro no se pierde en el tema oscuro.
            drawPath(TopPaths.body, Color.White.copy(alpha = 0.14f), style = Stroke(1f))
        }
    }
}

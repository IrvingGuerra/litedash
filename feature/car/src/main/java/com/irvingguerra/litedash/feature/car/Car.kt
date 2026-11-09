package com.irvingguerra.litedash.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/** Proporción (ancho / alto) de la caja donde se dibuja el auto en su pieza. */
const val CAR_ASPECT = 1.9f

/** Colores de pintura que se ofrecen; el usuario elige uno para su auto. */
val CarColors = listOf(
    Color(0xFFC9CED4), // plata
    Color(0xFFF4F6F8), // blanco
    Color(0xFF7A8590), // gris
    Color(0xFF1D2026), // negro
    Color(0xFFA3141D), // rojo
    Color(0xFF2457A6), // azul
)

// Auto concepto de perfil, estilo maqueta de arcilla: una sola forma con volumen
// (luz y sombra), sin manijas, líneas ni faros. Se dibuja en la franja y = 30…210
// de una caja de 481 de ancho.
private const val WIDTH = 481f
private const val TOP = 30f
private const val HEIGHT = 180f
private const val WHEEL_Y = 154f

private object CarPaths {
    val body = path("M14 150 C8 140 10 124 22 114 C40 104 90 92 150 74 C180 58 225 44 265 43 C310 43 360 52 398 63 C425 70 448 74 458 80 C470 90 472 120 468 142 C466 158 460 166 446 170 L439.2 170 A47 47 0 1 0 350.8 170 L149.2 170 A47 47 0 1 0 60.8 170 L34 168 C24 164 17 158 14 150 Z")
    // Arcos con el mismo centro que la llanta: el hueco la abraza parejo.
    val wells = path("M60.8 170 A47 47 0 1 1 149.2 170 Z M350.8 170 A47 47 0 1 1 439.2 170 Z")
    // El costado (plano vertical, más oscuro) y la cabina hundida.
    val side = path("M10 128 C120 114 300 100 474 96 L476 180 L8 180 Z")
    val canopy = path("M160 88 C200 64 240 54 280 53 C330 53 380 64 418 82 C360 88 250 92 160 88 Z")
    val roof = path("M150 74 C180 58 225 44 265 43 C310 43 360 52 398 63")
    val crease = path("M40 116 C120 100 200 96 260 95 C340 94 410 88 456 84")
    val wheels = listOf(105f, 395f)
}

/** Un trazo SVG ("M10 20…") listo para dibujar: las siluetas del auto de lado y desde arriba. */
internal fun path(d: String) = PathParser().parsePathString(d).toPath()

// Luz de arriba y sombra abajo sobre la pintura: sirve para cualquier color.
private val bodyShade = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.4f),
    0.45f to Color.White.copy(alpha = 0.05f),
    1f to Color.Black.copy(alpha = 0.3f),
    startY = 40f,
    endY = 175f,
)
private val sideShade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.2f),
    0.4f to Color.Black.copy(alpha = 0.08f),
    1f to Color.Black.copy(alpha = 0.3f),
    startY = 96f,
    endY = 176f,
)
// Brillo de las salpicaderas: lo que da el volumen de maqueta.
private val frontGlow = Offset(110f, 104f)
private val rearGlow = Offset(400f, 92f)
private val frontFender = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), frontGlow, 80f)
private val rearFender = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent), rearGlow, 80f)
private val canopyShade = Brush.linearGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.5f to Color.Black.copy(alpha = 0.14f),
    1f to Color.Black.copy(alpha = 0.32f),
    start = Offset(160f, 53f),
    end = Offset(418f, 90f),
)
// La línea del hombro, que se desvanece en las puntas.
private val creaseShine = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.3f to Color.White.copy(alpha = 0.3f),
    0.75f to Color.White.copy(alpha = 0.3f),
    1f to Color.Transparent,
    startX = 40f,
    endX = 456f,
)
private val tire = Color(0xFF2A2E34)

/**
 * El auto pintado del [color] elegido. Es un dibujo estático: se pinta una vez
 * y no vuelve a costar nada hasta que cambie el color o el tamaño.
 */
@Composable
fun CarDrawing(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(WIDTH / HEIGHT)) {
        val s = size.width / WIDTH
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            translate(0f, -TOP)
        }) {
            // Sombra en el piso: un círculo con degradado aplastado a elipse.
            val floor = Offset(240f, 196f)
            withTransform({ scale(1f, 12f / 250f, pivot = floor) }) {
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), floor, 250f), 250f, floor)
            }
            drawPath(CarPaths.wells, Color(0xFF0B0D10))
            drawPath(CarPaths.body, color)
            drawPath(CarPaths.body, bodyShade)
            clipPath(CarPaths.body) {
                drawPath(CarPaths.side, sideShade)
                drawCircle(frontFender, 80f, frontGlow)
                drawCircle(rearFender, 80f, rearGlow)
            }
            drawPath(CarPaths.canopy, canopyShade)
            drawPath(CarPaths.roof, Color.White.copy(alpha = 0.35f), style = Stroke(1.5f))
            drawPath(CarPaths.crease, creaseShine, style = Stroke(1.2f))
            // Contorno apenas visible: un auto oscuro no se pierde en el tema oscuro.
            drawPath(CarPaths.body, Color.White.copy(alpha = 0.12f), style = Stroke(1f))
            for (cx in CarPaths.wheels) {
                val c = Offset(cx, WHEEL_Y)
                drawCircle(tire, 41f, c)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFEEF0F2), Color(0xFF9AA2AB)), Offset(cx - 7f, WHEEL_Y - 10f), 52f),
                    35f,
                    c,
                )
                drawCircle(Color.Black.copy(alpha = 0.2f), 35f, c, style = Stroke(1.5f))
            }
        }
    }
}

/**
 * Pieza del tablero: el auto y su nombre. Tocarla hace [onClick] (en el inicio,
 * verlo en grande). [drawing] reemplaza al sedán genérico cuando hay un auto del
 * catálogo instalado.
 */
@Composable
fun CarTile(
    name: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    drawing: (@Composable (Modifier) -> Unit)? = null,
    /** El dibujo trae piso: sin nombre debajo (ahí caen la sombra y el reflejo). */
    hasFloor: Boolean = false,
) {
    val c = LocalColors.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val height = maxHeight
        val showName = height > 140.dp && !hasFloor
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (drawing != null) {
                // Los renders traen más aire arriba y abajo que el dibujo: un poco más alto.
                drawing(Modifier.widthIn(max = height * 2f).fillMaxWidth().aspectRatio(CAR_ASPECT).padding(horizontal = 8.dp))
            } else {
                CarDrawing(color, Modifier.widthIn(max = height * 2.4f).fillMaxWidth().padding(horizontal = 8.dp))
            }
            if (showName) Label(name, Typography.subtitle, c.textMuted, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

// Sedán eléctrico de perfil (sin marca), en una caja de 820 × 300.
private const val WIDTH = 820f
private const val HEIGHT = 300f
private const val WHEEL_Y = 228f

private object CarPaths {
    // Sedán eléctrico: frente corto, parabrisas muy adelante, cabina larga con techo de cristal y cola corta.
    val body = path("M60 222 C48 214 44 196 50 182 C56 170 74 164 104 160 C160 152 210 146 262 136 C300 112 350 84 420 70 C470 62 530 62 575 68 C640 80 690 100 728 118 C744 122 760 124 772 126 C786 132 792 148 790 168 C788 190 780 206 766 216 L704 224 A64 64 0 0 0 576 224 L249 224 A64 64 0 0 0 121 224 L72 224 C66 224 62 223 60 222 Z")
    val wells = path("M121 224 A64 64 0 0 1 249 224 Z M576 224 A64 64 0 0 1 704 224 Z")
    val glass = path("M270 135 C306 111 354 86 420 73 C470 65 530 65 574 71 C638 83 686 102 722 119 C640 123 420 130 270 135 Z")
    val glassShine = path("M330 104 C380 84 440 74 500 70")
    val shoulder = path("M104 163 C300 151 560 140 772 129")
    val creases = path("M260 204 C380 198 500 198 572 204 M468 130 L464 216 M294 136 C290 170 292 200 296 214")
    val handles = path("M396 152 L420 151 M536 148 L560 147")
    val sill = path("M249 214 L576 214 L576 224 L249 224 Z")
    val mirror = path("M292 132 C296 124 306 121 314 124 L312 134 L296 136 Z")
    val headlight = path("M58 178 C72 170 92 166 120 164")
    val taillight = path("M760 124 C774 127 782 132 785 140")
    val intake = path("M58 206 C70 210 88 212 108 211")
    val wheels = listOf(185f, 640f)
    // Una ranura del rin aerodinámico, relativa al centro de la rueda; se repite cinco veces.
    val slot = path("M0 -28 C11 -26 19 -19 21 -11")

    private fun path(d: String) = PathParser().parsePathString(d).toPath()
}

private fun Color.scaled(f: Float) = Color(red * f, green * f, blue * f, alpha)

// Luz de arriba y sombra abajo sobre la pintura, sirve para cualquier color.
private val bodyShade = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.25f),
    0.35f to Color.Transparent,
    0.55f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.45f),
    startY = 60f,
    endY = WHEEL_Y,
)
private val glassPaint = Brush.linearGradient(
    listOf(Color(0xFF4A5562), Color(0xFF1A1F26), Color(0xFF0B0E12)),
    Offset(270f, 70f),
    Offset(722f, 130f),
)
private val rimPaint = Brush.verticalGradient(listOf(Color(0xFFD5DAE0), Color(0xFF5E6670)), 186f, 270f)
private val tire = Color(0xFF0B0D10)
private val dark = Color(0xFF2A2F36)

/**
 * El auto pintado del [color] elegido. Es un dibujo estático: se pinta una vez
 * y no vuelve a costar nada hasta que cambie el color o el tamaño.
 */
@Composable
fun CarDrawing(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(WIDTH / HEIGHT)) {
        val s = size.width / WIDTH
        // Sombra en el piso: un círculo con degradado aplastado a elipse.
        val center = Offset(412 * s, 282 * s)
        withTransform({ scale(1f, 16f / 380f, pivot = center) }) {
            drawCircle(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center, 380 * s),
                380 * s,
                center,
            )
        }
        scale(s, s, pivot = Offset.Zero) {
            drawPath(CarPaths.wells, tire)
            drawPath(CarPaths.body, color)
            drawPath(CarPaths.body, bodyShade)
            drawPath(CarPaths.body, Color.White.copy(alpha = 0.14f), style = Stroke(2f))
            drawPath(CarPaths.glass, glassPaint)
            drawPath(CarPaths.glassShine, Color.White.copy(alpha = 0.18f), style = Stroke(3f, cap = StrokeCap.Round))
            drawPath(CarPaths.shoulder, Color.White.copy(alpha = 0.35f), style = Stroke(1.5f))
            drawPath(CarPaths.creases, Color.Black.copy(alpha = 0.2f), style = Stroke(1.5f))
            drawPath(CarPaths.handles, Color.Black.copy(alpha = 0.35f), style = Stroke(3f, cap = StrokeCap.Round))
            drawPath(CarPaths.sill, Color(0xFF111418))
            drawPath(CarPaths.mirror, color.scaled(0.75f))
            // Barra de luz en una ranura oscura: se ve igual en un auto blanco que en uno negro.
            drawPath(CarPaths.headlight, Color(0xFF15181C), style = Stroke(7f, cap = StrokeCap.Round))
            drawPath(CarPaths.headlight, Color(0xFFF4F8FF), style = Stroke(3f, cap = StrokeCap.Round))
            drawPath(CarPaths.taillight, Color(0xFFE0262F), style = Stroke(4f, cap = StrokeCap.Round))
            drawPath(CarPaths.intake, Color(0xFF0E1114), style = Stroke(5f, cap = StrokeCap.Round))
            for (cx in CarPaths.wheels) {
                val c = Offset(cx, WHEEL_Y)
                drawCircle(tire, 54f, c)
                drawCircle(rimPaint, 42f, c)
                translate(cx, WHEEL_Y) {
                    repeat(5) { i ->
                        rotate(i * 72f, pivot = Offset.Zero) {
                            drawPath(CarPaths.slot, dark, style = Stroke(4f, cap = StrokeCap.Round))
                        }
                    }
                }
                drawCircle(tire.copy(alpha = 0.4f), 42f, c, style = Stroke(2f))
                drawCircle(dark, 8f, c)
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

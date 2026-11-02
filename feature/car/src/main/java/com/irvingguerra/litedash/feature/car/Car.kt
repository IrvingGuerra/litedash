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

// Sedán concepto de perfil (sin marca), en una caja de 820 × 300.
private const val WIDTH = 820f
private const val HEIGHT = 300f
private const val WHEEL_Y = 224f

private object CarPaths {
    // Sedán eléctrico de líneas limpias: nariz baja, techo de cristal en arco y cola corta.
    val body = path("M52 212 C44 198 44 182 54 172 C70 160 120 150 200 143 C260 138 320 132 362 125 C420 92 470 74 540 70 C620 68 692 86 752 116 C766 122 778 125 787 129 C792 142 790 170 782 192 C776 206 768 214 756 218 L690 220 A65 65 0 0 0 560 220 L255 220 A65 65 0 0 0 125 220 L72 220 C62 219 55 216 52 212 Z")
    val wells = path("M125 220 A65 65 0 0 1 255 220 Z M560 220 A65 65 0 0 1 690 220 Z")
    val glass = path("M374 124 C430 93 480 78 540 75 C620 74 686 90 738 117 C690 118 600 121 520 122 C460 123 412 124 374 124 Z")
    val glassShine = path("M430 100 C480 82 530 78 570 78")
    val shoulder = path("M120 158 C300 148 560 138 762 132")
    val creases = path("M262 200 C380 192 500 192 556 198 M470 126 L468 210 M330 132 L330 206")
    val handles = path("M392 150 L414 149 M500 146 L522 145")
    val sill = path("M255 210 L560 210 L560 220 L255 220 Z")
    val mirror = path("M386 124 C390 116 400 113 408 116 L406 127 L390 128 Z")
    val headlight = path("M56 168 C80 160 112 155 152 152")
    val taillight = path("M763 124 C778 128 786 132 789 139")
    val intake = path("M58 202 C72 205 92 206 110 205")
    val wheels = listOf(190f, 625f)
    // Una ranura del rin aerodinámico, relativa al centro de la rueda; se repite cinco veces.
    val slot = path("M0 -30 C12 -28 20 -20 22 -12")

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
    Offset(374f, 70f),
    Offset(738f, 124f),
)
private val rimPaint = Brush.verticalGradient(listOf(Color(0xFFD5DAE0), Color(0xFF5E6670)), 180f, 268f)
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
        val center = Offset(410 * s, 274 * s)
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
                drawCircle(tire, 58f, c)
                drawCircle(rimPaint, 45f, c)
                translate(cx, WHEEL_Y) {
                    repeat(5) { i ->
                        rotate(i * 72f, pivot = Offset.Zero) {
                            drawPath(CarPaths.slot, dark, style = Stroke(4f, cap = StrokeCap.Round))
                        }
                    }
                }
                drawCircle(tire.copy(alpha = 0.4f), 45f, c, style = Stroke(2f))
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

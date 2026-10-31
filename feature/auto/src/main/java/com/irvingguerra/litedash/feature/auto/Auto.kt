package com.irvingguerra.litedash.feature.auto

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Texto
import kotlin.math.cos
import kotlin.math.sin

/** Colores de pintura que se ofrecen; el usuario elige uno para su auto. */
val ColoresAuto = listOf(
    Color(0xFFC9CED4), // plata
    Color(0xFFF4F6F8), // blanco
    Color(0xFF7A8590), // gris
    Color(0xFF1D2026), // negro
    Color(0xFFA3141D), // rojo
    Color(0xFF2457A6), // azul
)

// Sedán genérico de perfil (nivel 0 del plan), en una caja de 820 × 300.
private const val ANCHO = 820f
private const val ALTO = 300f

private object Trazos {
    val cuerpo = ruta("M70 228 L60 212 C56 196 58 182 68 172 C92 160 160 150 236 143 L330 131 C370 104 410 84 452 73 C502 62 570 62 612 70 C652 80 690 104 714 122 C732 124 748 126 757 130 C765 146 767 176 761 200 L752 226 L682 228 A62 62 0 0 0 558 228 L252 228 A62 62 0 0 0 128 228 Z")
    val vidrio = ruta("M356 131 C388 108 420 91 454 82 C502 72 566 72 602 79 C634 88 664 105 684 121 C600 124 480 128 356 131 Z")
    val pilar = ruta("M520 75 L534 75 L540 127 L522 128 Z")
    val lineas = ruta("M444 136 L440 222 M566 132 L570 220 M96 170 C260 158 520 148 748 146")
    val brillo = ruta("M96 168 C260 156 520 146 748 144")
    val manijas = ruta("M466 156 L488 155 M588 152 L610 151")
    val espejo = ruta("M372 128 C380 120 392 118 398 122 L396 134 L374 136 Z")
    val faro = ruta("M70 174 C92 165 124 160 150 158 L142 171 C116 176 92 179 72 181 Z")
    val calavera = ruta("M742 134 C750 135 756 137 759 140 L760 156 C752 154 744 152 738 150 Z")
    val parrilla = ruta("M62 192 L108 194 L112 216 L66 214 Z")
    val ruedas = listOf(190f, 620f)
    val rayos = ruedas.map { cx ->
        Path().apply {
            for (i in 0 until 5) {
                val a = Math.toRadians(i * 72.0 - 90)
                moveTo(cx + 9 * cos(a).toFloat(), 228 + 9 * sin(a).toFloat())
                lineTo(cx + 34 * cos(a).toFloat(), 228 + 34 * sin(a).toFloat())
            }
        }
    }

    private fun ruta(d: String) = PathParser().parsePathString(d).toPath()
}

private fun Color.por(f: Float) = Color(red * f, green * f, blue * f, alpha)

/**
 * El auto pintado del [color] elegido. Es un dibujo estático: se pinta una vez
 * y no vuelve a costar nada hasta que cambie el color o el tamaño.
 */
@Composable
fun DibujoAuto(color: Color, modifier: Modifier = Modifier) {
    val carroceria = remember(color) {
        Brush.verticalGradient(listOf(color, color.por(0.86f), color.por(0.58f)), startY = 62f, endY = 228f)
    }
    Canvas(modifier.aspectRatio(ANCHO / ALTO)) {
        val s = size.width / ANCHO
        // Sombra en el piso: un círculo con degradado aplastado a elipse.
        val centro = Offset(410 * s, 272 * s)
        withTransform({ scale(1f, 18f / 380f, pivot = centro) }) {
            drawCircle(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), centro, 380 * s),
                380 * s,
                centro,
            )
        }
        scale(s, s, pivot = Offset.Zero) {
            for (cx in Trazos.ruedas) drawCircle(Color(0xFF0B0D10), 60f, Offset(cx, 228f))
            drawPath(Trazos.cuerpo, carroceria)
            drawPath(Trazos.cuerpo, Color.White.copy(alpha = 0.14f), style = Stroke(2f))
            drawPath(
                Trazos.vidrio,
                Brush.linearGradient(listOf(Color(0xFF3B4450), Color(0xFF0D1014)), Offset(356f, 70f), Offset(684f, 131f)),
            )
            drawPath(Trazos.pilar, color.por(0.75f))
            drawPath(Trazos.parrilla, Color(0xFF15181C))
            drawPath(Trazos.lineas, Color.Black.copy(alpha = 0.22f), style = Stroke(2f))
            drawPath(Trazos.brillo, Color.White.copy(alpha = 0.35f), style = Stroke(2f))
            drawPath(Trazos.manijas, Color.Black.copy(alpha = 0.35f), style = Stroke(4f, cap = StrokeCap.Round))
            drawPath(Trazos.espejo, color.por(0.8f))
            drawPath(Trazos.faro, Color(0xFFEEF3F8))
            drawPath(Trazos.calavera, Color(0xFFC0262D))
            Trazos.ruedas.forEachIndexed { i, cx ->
                val c = Offset(cx, 228f)
                drawCircle(Color(0xFF15181C), 50f, c)
                drawCircle(Brush.verticalGradient(listOf(Color(0xFFC9D0D8), Color(0xFF6B7480)), 191f, 265f), 37f, c)
                drawPath(Trazos.rayos[i], Color(0xFF2A2F36), style = Stroke(7f, cap = StrokeCap.Round))
                drawCircle(Color(0xFF2A2F36), 8f, c)
            }
        }
    }
}

/**
 * Pieza del tablero: el auto y su nombre. Tocarla abre "Tu auto". [dibujo]
 * reemplaza al sedán genérico cuando hay un auto del catálogo instalado.
 */
@Composable
fun PiezaAuto(
    nombre: String,
    color: Color,
    alTocar: () -> Unit,
    modifier: Modifier = Modifier,
    dibujo: (@Composable (Modifier) -> Unit)? = null,
) {
    val c = LocalColores.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = alTocar),
        contentAlignment = Alignment.Center,
    ) {
        val alto = maxHeight
        val conNombre = alto > 140.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (dibujo != null) {
                // Los renders traen más aire arriba y abajo que el dibujo: un poco más alto.
                dibujo(Modifier.widthIn(max = alto * 2f).fillMaxWidth().aspectRatio(1.9f).padding(horizontal = 8.dp))
            } else {
                DibujoAuto(color, Modifier.widthIn(max = alto * 2.4f).fillMaxWidth().padding(horizontal = 8.dp))
            }
            if (conNombre) Etiqueta(nombre, Texto.subtitulo, c.textoSuave, maxLineas = 1, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

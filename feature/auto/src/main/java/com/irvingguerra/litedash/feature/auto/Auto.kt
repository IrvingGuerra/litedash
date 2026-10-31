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
    // Sedán moderno de líneas redondas: cofre corto, techo en arco y cajuela.
    val cuerpo = ruta("M70 220 C56 212 52 192 56 176 C60 160 78 150 110 145 C170 136 250 130 312 124 C340 121 356 116 372 106 C410 82 450 66 500 60 C560 53 620 58 660 76 C684 87 700 100 716 110 C730 116 752 117 766 121 C778 126 784 140 784 156 C784 180 778 200 768 214 L762 226 L688 228 A68 68 0 0 0 552 228 L258 228 A68 68 0 0 0 122 228 L82 227 Z")
    val vidrio = ruta("M386 118 C420 92 456 74 502 68 C560 62 616 66 652 84 C668 92 682 102 694 112 C600 115 480 117 386 118 Z")
    val pilar = ruta("M532 64 L546 64 L550 115 L534 116 Z")
    val lineas = ruta("M456 124 L454 222 M584 120 L588 220 M100 170 C300 158 560 148 778 148")
    val brillo = ruta("M100 168 C300 156 560 146 778 146")
    val manijas = ruta("M478 146 L500 145 M606 142 L628 141")
    val espejo = ruta("M392 114 C398 106 410 104 416 108 L414 120 L394 122 Z")
    val faro = ruta("M60 170 C80 158 108 151 140 148 L134 159 C108 163 84 168 62 176 Z")
    val calavera = ruta("M762 122 C772 125 779 131 782 140 L781 146 C772 143 762 140 752 138 Z")
    val parrilla = ruta("M58 196 C70 198 96 199 116 198 L112 212 C92 213 72 212 62 210 Z")
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
        Brush.verticalGradient(listOf(color, color.por(0.86f), color.por(0.58f)), startY = 53f, endY = 228f)
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
                Brush.linearGradient(listOf(Color(0xFF3B4450), Color(0xFF0D1014)), Offset(386f, 60f), Offset(694f, 118f)),
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
    /** El dibujo trae piso: sin nombre debajo (ahí caen la sombra y el reflejo). */
    conPiso: Boolean = false,
) {
    val c = LocalColores.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = alTocar),
        contentAlignment = Alignment.Center,
    ) {
        val alto = maxHeight
        val conNombre = alto > 140.dp && !conPiso
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

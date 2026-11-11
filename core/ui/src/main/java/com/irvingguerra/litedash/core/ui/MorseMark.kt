package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Proporción (ancho / alto) de lo que se ve de [MorseMark]: 184 unidades de ancho por
 * las 36 de alto de la raya y los puntos. Para darle a su caja la medida justa.
 */
const val MORSE_ASPECT = 184f / 36f

/**
 * La marca suelta —·· (brand/logo/litedash-marca-*.svg), dibujada sin imágenes.
 * Mismas proporciones que el SVG: lienzo de 184 × 48 unidades.
 */
@Composable
fun MorseMark(modifier: Modifier = Modifier, color: Color = LocalColors.current.text, light: Color = Brand.Amber) {
    Canvas(modifier) {
        val u = size.width / 184f
        val height = 36f * u
        val y = (size.height - height) / 2f
        drawRoundRect(color, Offset(6f * u, y), Size(76f * u, height), CornerRadius(height / 2f))
        drawCircle(color, 17f * u, Offset(113f * u, size.height / 2f))
        drawCircle(light, 17f * u, Offset(161f * u, size.height / 2f))
    }
}

/** Lo que tarda [MorseMarkIntro] en armarse (ms). */
const val MORSE_INTRO_MS = 1400

/**
 * La marca —·· armándose (animación de entrada): el guion entra desde la izquierda y los
 * dos puntos caen saltando, uno tras otro, hasta su lugar. [timeMs] (0..[MORSE_INTRO_MS])
 * se lee al dibujar, así cada cuadro no recompone. Maqueta: marca/animacion/splash.html.
 */
@Composable
fun MorseMarkIntro(timeMs: () -> Float, modifier: Modifier = Modifier, color: Color = LocalColors.current.text, light: Color = Brand.Amber) {
    Canvas(modifier) {
        val t = timeMs()
        val u = size.width / 184f
        val height = 36f * u
        val mid = size.height / 2f
        val slide = outBack(phase(t, 0f, 600f))
        val x = mix(6f * u - size.width * 1.1f, 6f * u, slide)
        drawRoundRect(color, Offset(x, mid - height / 2f), Size(76f * u, height), CornerRadius(height / 2f), alpha = phase(t, 0f, 160f))
        hoppingDot(color, 113f * u, mid, u, phase(t, 480f, 1180f))
        hoppingDot(light, 161f * u, mid, u, phase(t, 700f, 1400f))
    }
}

/** Un punto que cae y rebota dos veces, apenas aplastado al tocar el piso ([t] de 0 a 1). */
private fun DrawScope.hoppingDot(color: Color, cx: Float, cy: Float, u: Float, t: Float) {
    if (t <= 0f) return
    var y = 0f
    var squash = 0f
    for (i in BOUNCES.indices step 3) {
        val start = BOUNCES[i]
        val end = BOUNCES[i + 1]
        if (t >= end) continue
        val p = (t - start) / (end - start)
        val first = i == 0
        val high = BOUNCES[i + 2]
        y = if (first) -high * (1f - p * p) else -high * 4f * p * (1f - p)
        val landing = max(0f, 1f - abs(p - if (first) 1f else 0f) * 6f)
        squash = (if (first) 0.16f else 0.07f) * landing
        break
    }
    val radius = 17f * u
    val w = 2f * radius * (1f + squash)
    val h = 2f * radius * (1f - squash)
    val floor = cy + radius + y * u
    drawOval(color, Offset(cx - w / 2f, floor - h), Size(w, h), alpha = phase(t, 0f, 0.12f))
}

// Rebotes del punto, de tres en tres: inicio, fin y altura (en unidades del logo).
private val BOUNCES = floatArrayOf(0f, 0.46f, 95f, 0.46f, 0.74f, 22f, 0.74f, 0.92f, 6f)

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun outBack(t: Float): Float {
    val c = 1.25f
    return 1f + (c + 1f) * (t - 1f).pow(3) + c * (t - 1f).pow(2)
}

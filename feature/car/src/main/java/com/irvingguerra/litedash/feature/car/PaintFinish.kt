package com.irvingguerra.litedash.feature.car

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Desde aquí los brillos de la pintura teñida se acercan a blanco sin cortarse. */
private const val KNEE = 0.78f

/** Brillo mínimo de la pintura (el selector libre permite negro puro). */
private const val PAINT_FLOOR = 0.07f

/** Brillo de fábrica del deslizador (Brillante y Metálico). */
const val DEFAULT_GLOSS = 0.5f

/** Pasos del deslizador de brillo que se pintan (la caché no se llena de brillos casi iguales). */
private const val GLOSS_STEPS = 20

/** Acabado de la pintura del auto; Satinado es la pintura tal como viene en el paquete. */
enum class PaintFinish(val stored: String) {
    MATTE("matte"),
    SATIN("satin"),
    GLOSS("gloss"),
    METALLIC("metallic");

    /** El deslizador de brillo cambia algo en este acabado. */
    val shines get() = this == GLOSS || this == METALLIC

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: SATIN
    }
}

/** Cómo se pinta el auto: [color], [finish] y [gloss] (0–1, solo en Brillante y Metálico). */
@Immutable
data class CarPaint(val color: Color, val finish: PaintFinish = PaintFinish.SATIN, val gloss: Float = DEFAULT_GLOSS) {
    /** El brillo en los pasos que se pintan. */
    internal val glossStep get() = (gloss.coerceIn(0f, 1f) * GLOSS_STEPS).roundToInt()

    /** Lo que distingue el acabado en la llave de la caché ("" en Satinado: las llaves de siempre). */
    internal val cacheTag
        get() = when (finish) {
            PaintFinish.SATIN -> ""
            PaintFinish.MATTE -> "~" + finish.stored
            else -> "~" + finish.stored + glossStep
        }
}

/**
 * Pinta un renglón de píxeles del [color] (r, g, b de 0 a 1) con el acabado [finish]; puro (sin
 * Android) para probarlo. T(x, c) = la pintura de fábrica: color = imagen × color / [gray], con los
 * brillos suaves arriba de [KNEE]. Con capas (brillo y espejo, gris 0–255):
 * mate = imagen − brillo; Mate = 0.95·T(mate); Brillante = T(mate) + espejo^1.6·(0.45 + 0.8·g);
 * Metálico = (0.55 − 0.2·g)·T(mate) + espejo^1.3·(0.25 + 2.4·c)·(0.6 + 0.8·g) + espejo³·0.35.
 * Todo en tablas de 256 (se arman una vez por cuadro).
 */
internal class PaintShader(color: FloatArray, gray: Float, private val finish: PaintFinish, glossStep: Int) {
    /** T por canal, en 0–255. */
    private val tone = Array(3) { ch ->
        // Nunca negro puro: el auto perdería todo su volumen y quedaría como silueta.
        val base = PAINT_FLOOR + color[ch] * (1 - PAINT_FLOOR)
        IntArray(256) { i ->
            val v = i / 255f * base / gray
            val r = if (v <= KNEE) v else KNEE + (1 - KNEE) * (1 - exp(-(v - KNEE) / (1 - KNEE)))
            (r * 255).roundToInt().coerceIn(0, 255)
        }
    }
    private val g = glossStep / GLOSS_STEPS.toFloat()

    /** T(mate) ya escalado por el acabado (0–255). */
    private val scaled: Array<IntArray>

    /** Lo que suma el espejo por canal (0–255 y más: se recorta al final). */
    private val lift: Array<IntArray>

    init {
        val scale = when (finish) {
            PaintFinish.MATTE -> 0.95f
            PaintFinish.METALLIC -> 0.55f - 0.2f * g
            else -> 1f
        }
        scaled = Array(3) { ch -> IntArray(256) { (tone[ch][it] * scale).roundToInt() } }
        lift = Array(3) { ch ->
            IntArray(256) { i ->
                val h = i / 255f
                val add = when (finish) {
                    PaintFinish.GLOSS -> h.pow(1.6f) * (0.45f + 0.8f * g)
                    PaintFinish.METALLIC -> h.pow(1.3f) * (0.25f + 2.4f * color[ch]) * (0.6f + 0.8f * g) + h * h * h * 0.35f
                    else -> 0f
                }
                (add * 255).roundToInt()
            }
        }
    }

    /**
     * Pinta [px] donde [mask] (alfa) tiene pintura; [shine] y [mirror] son las capas del acabado
     * (null = Satinado, lo de siempre). Devuelve si cambió algún píxel.
     */
    fun row(px: IntArray, mask: IntArray, shine: IntArray?, mirror: IntArray?, width: Int): Boolean {
        val layered = finish != PaintFinish.SATIN && shine != null && mirror != null
        var touched = false
        for (i in 0 until width) {
            val m = mask[i] ushr 24
            if (m == 0) continue
            touched = true
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val tr: Int
            val tg: Int
            val tb: Int
            if (layered) {
                // Las capas son gris: cualquier canal sirve.
                val s = shine!![i] and 0xFF
                val h = mirror!![i] and 0xFF
                tr = (scaled[0][(r - s).coerceAtLeast(0)] + lift[0][h]).coerceIn(0, 255)
                tg = (scaled[1][(g - s).coerceAtLeast(0)] + lift[1][h]).coerceIn(0, 255)
                tb = (scaled[2][(b - s).coerceAtLeast(0)] + lift[2][h]).coerceIn(0, 255)
            } else {
                tr = tone[0][r]
                tg = tone[1][g]
                tb = tone[2][b]
            }
            val rr = r + (tr - r) * m / 255
            val gg = g + (tg - g) * m / 255
            val bb = b + (tb - b) * m / 255
            px[i] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        return touched
    }
}

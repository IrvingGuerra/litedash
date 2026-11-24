package com.irvingguerra.litedash.feature.map

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Los colores del mapa dibujado (ARGB): tierra, agua, parques, y por tipo de calle ([RoadTier]) su orilla y su
 * relleno; los nombres de las calles con su halo. La paleta de fábrica es la «clásica» de Horizon Maps (GPL-3.0):
 * noche azul pizarra y día claro con avenidas amarillas y autopistas naranjas. Las demás salen de un color base
 * ([derive]): tierra, calles y autopistas en su tono; agua y parques los de fábrica (así siempre se leen).
 */
class MapPalette(
    val land: Int,
    val water: Int,
    val park: Int,
    val casings: IntArray,
    val roads: IntArray,
    val label: Int,
    val halo: Int,
) {
    companion object {
        // Valores de Horizon Maps (GPL-3.0), `MapBinding.recolourClassic` y `OfflineMap.kt`.
        val NIGHT = MapPalette(
            land = 0xFF2D3358.toInt(), water = 0xFF2A6682.toInt(), park = 0xFF2E5C5E.toInt(),
            casings = argb(0xFF2B3052, 0xFF2B3052, 0xFF262B4B, 0xFF262B4B, 0xFF262B4B),
            roads = argb(0xFF5E67A0, 0xFF5E67A0, 0xFF525A8D, 0xFF464E7C, 0xFF3D4470),
            label = 0xFFD3D8F0.toInt(), halo = 0xFF252A48.toInt(),
        )
        val DAY = MapPalette(
            land = 0xFFF4F2EE.toInt(), water = 0xFFAAD3E6.toInt(), park = 0xFFCFE6C0.toInt(),
            casings = argb(0xFFD9923A, 0xFFDDB85E, 0xFFDDB85E, 0xFFCBC6BB, 0xFFCBC6BB),
            roads = argb(0xFFF9C27A, 0xFFFFF1C4, 0xFFFFF1C4, 0xFFFFFFFF, 0xFFFAF9F7),
            label = 0xFF3E4459.toInt(), halo = 0xFFF4F6FB.toInt(),
        )

        /**
         * La vista de tu auto (calles a escala real): solo el fondo gris de siempre y los nombres en gris; sus calles
         * llevan sus propios colores. No cambia con la paleta elegida.
         */
        val CAR_NIGHT = MapPalette(0xFF1B1C20.toInt(), 0, 0, NIGHT.casings, NIGHT.roads, 0xFFC3C6CD.toInt(), 0xFF1B1C20.toInt())
        val CAR_DAY = MapPalette(0xFFE9EBEF.toInt(), 0, 0, DAY.casings, DAY.roads, 0xFF4E535C.toInt(), 0xFFE9EBEF.toInt())

        /** La de fábrica de noche ([dark]) o de día. */
        fun factory(dark: Boolean) = if (dark) NIGHT else DAY

        /**
         * La paleta en el tono de [base] (RGB, el alfa se ignora), con la misma luz que la de fábrica en cada parte
         * (así el contraste no depende del tono: un amarillo no queda más claro que un azul): de noche la tierra muy
         * oscura, las calles con más luz y las autopistas más saturadas y claras; de día la tierra casi blanca, calles
         * blancas con su orilla y autopistas de color. Un gris da un mapa gris. Contraste comprobado en `MapPaletteTest`.
         */
        fun derive(base: Int, dark: Boolean): MapPalette {
            val (h, s) = hueSat(base)
            val f = factory(dark)
            // Lo apagado (tierra, calles) lleva poco color; lo de realce (autopistas), más.
            val soft = minOf(s, if (dark) 0.4f else 0.35f)
            val bold = if (s < 0.05f) s else minOf(s * 1.2f + 0.1f, if (dark) 0.55f else 0.85f)
            fun t(sat: Float, lum: Double) = tone(h, sat, lum)
            return if (dark) {
                val edge = t(soft, 0.026)
                val majorEdge = t(soft, 0.032)
                MapPalette(
                    land = t(soft, 0.037), water = f.water, park = f.park,
                    casings = intArrayOf(majorEdge, majorEdge, edge, edge, edge),
                    roads = intArrayOf(t(bold, 0.16), t(bold, 0.14), t(soft, 0.105), t(soft * 0.9f, 0.08), t(soft * 0.9f, 0.07)),
                    label = t(minOf(s, 0.5f), 0.68), halo = t(soft, 0.024),
                )
            } else {
                val edge = t(soft * 0.6f + 0.04f, 0.56)
                val midEdge = t(minOf(s, 0.6f), 0.5)
                MapPalette(
                    land = t(soft, 0.89), water = f.water, park = f.park,
                    casings = intArrayOf(t(minOf(s, 0.6f), 0.35), midEdge, midEdge, edge, edge),
                    roads = intArrayOf(t(bold, 0.6), t(minOf(s, 0.9f), 0.87), t(minOf(s, 0.9f), 0.87), 0xFFFFFFFF.toInt(), t(soft, 0.95)),
                    label = t(minOf(s, 0.25f), 0.06), halo = t(soft, 0.92),
                )
            }
        }

        private fun argb(vararg v: Long) = IntArray(v.size) { v[it].toInt() }
    }
}

/** Tono (0–360) y saturación (0–1, como HSL) de un color RGB. */
private fun hueSat(rgb: Int): Pair<Float, Float> {
    val r = (rgb shr 16 and 0xFF) / 255f
    val g = (rgb shr 8 and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    if (d < 1e-4f) return 0f to 0f
    val l = (max + min) / 2
    val s = d / (1 - abs(2 * l - 1))
    val h = when (max) {
        r -> 60 * (((g - b) / d) % 6)
        g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }
    return (if (h < 0) h + 360 else h) to s.coerceIn(0f, 1f)
}

/** El color de tono [h] y saturación [s] (HSL) con la luminancia [lum] (WCAG, 0–1), buscando su luz por mitades. */
private fun tone(h: Float, s: Float, lum: Double): Int {
    var lo = 0f
    var hi = 1f
    repeat(20) {
        val mid = (lo + hi) / 2
        if (luminance(hsl(h, s, mid)) < lum) lo = mid else hi = mid
    }
    return hsl(h, s, (lo + hi) / 2)
}

/** Luminancia relativa (WCAG) de un color RGB. */
internal fun luminance(rgb: Int): Double {
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) + 0.0722 * ch(rgb and 0xFF)
}

/** Un color ARGB opaco de tono [h], saturación [s] y luz [l] (HSL). */
internal fun hsl(h: Float, s: Float, l: Float): Int {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

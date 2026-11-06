package com.irvingguerra.litedash.feature.car

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt

class PaintShaderTest {
    private val color = floatArrayOf(0.8f, 0.2f, 0.5f)
    private val gray = 0.45f

    /** La fórmula de antes de los acabados (1.4.95), copiada tal cual: Satinado debe dar lo mismo. */
    private fun oldTint(px: IntArray, mask: IntArray) {
        val tables = color.map { channel ->
            val base = 0.07f + channel * (1 - 0.07f)
            IntArray(256) { i ->
                val v = i / 255f * base / gray
                val r = if (v <= 0.78f) v else 0.78f + (1 - 0.78f) * (1 - exp(-(v - 0.78f) / (1 - 0.78f)))
                (r * 255).roundToInt().coerceIn(0, 255)
            }
        }
        for (i in px.indices) {
            val m = mask[i] ushr 24
            if (m == 0) continue
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val rr = r + (tables[0][r] - r) * m / 255
            val gg = g + (tables[1][g] - g) * m / 255
            val bb = b + (tables[2][b] - b) * m / 255
            px[i] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int) = argb(255, v, v, v)

    @Test
    fun satinIsUnchanged() {
        val pixels = IntArray(256) { argb(255 - it / 2, it, (it * 7) % 256, 255 - it) }
        val mask = IntArray(256) { (it * 13 % 256) shl 24 }
        val expected = pixels.copyOf().also { oldTint(it, mask) }
        // Sin capas y aunque se las pasen: Satinado no las mira.
        val plain = pixels.copyOf()
        PaintShader(color, gray, PaintFinish.SATIN, 10).row(plain, mask, null, null, 256)
        assertArrayEquals(expected, plain)
        val withLayers = pixels.copyOf()
        PaintShader(color, gray, PaintFinish.SATIN, 10).row(withLayers, mask, IntArray(256) { gray(40) }, IntArray(256) { gray(160) }, 256)
        assertArrayEquals(expected, withLayers)
    }

    @Test
    fun matteBelowSatinBelowGloss() {
        val mask = intArrayOf(255 shl 24)
        val shine = intArrayOf(gray(20))
        val mirror = intArrayOf(gray(160))
        fun paint(finish: PaintFinish): Int {
            val px = intArrayOf(gray(150))
            PaintShader(color, gray, finish, 10).row(px, mask, shine, mirror, 1)
            return px[0]
        }
        val matte = paint(PaintFinish.MATTE)
        val satin = paint(PaintFinish.SATIN)
        val gloss = paint(PaintFinish.GLOSS)
        for (shift in intArrayOf(16, 8, 0)) {
            val m = (matte shr shift) and 0xFF
            val s = (satin shr shift) and 0xFF
            val g = (gloss shr shift) and 0xFF
            assertTrue("canal $shift: mate $m ≤ satinado $s ≤ brillante $g", m <= s && s <= g)
        }
        // El alfa del cuadro se conserva.
        assertTrue(matte ushr 24 == 255 && gloss ushr 24 == 255)
    }

    @Test
    fun outsidePaintUntouched() {
        val px = intArrayOf(gray(150))
        val touched = PaintShader(color, gray, PaintFinish.METALLIC, 10).row(px, intArrayOf(0), intArrayOf(gray(20)), intArrayOf(gray(160)), 1)
        assertTrue(!touched && px[0] == gray(150))
    }
}

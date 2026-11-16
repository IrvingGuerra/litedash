package com.irvingguerra.litedash.feature.car

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintShaderTest {
    private val color = floatArrayOf(0.8f, 0.2f, 0.5f)
    private val gray = 0.45f

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int) = argb(255, v, v, v)

    /**
     * Satinado da lo mismo que la fórmula de antes de los acabados (1.4.95): píxeles con su valor fijo, sacados de
     * esa fórmula (grises de negro a blanco, uno de color y uno con media máscara y medio alfa).
     */
    @Test
    fun satinIsUnchanged() {
        val pixels = intArrayOf(gray(0), gray(60), gray(115), gray(150), gray(200), gray(255), argb(255, 200, 40, 90), argb(128, 30, 220, 140))
        val mask = intArrayOf(255 shl 24, 255 shl 24, 255 shl 24, 255 shl 24, 255 shl 24, 255 shl 24, 128 shl 24, 200 shl 24)
        val expected = intArrayOf(
            0xFF000000.toInt(), 0xFF6D2247.toInt(), 0xFFCF4189.toInt(), 0xFFF055B2.toInt(),
            0xFFFC72E3.toInt(), 0xFFFE91F6.toInt(), 0xFFE22062.toInt(), 0x803092A0.toInt(),
        )
        // Sin capas y aunque se las pasen: Satinado no las mira.
        val plain = pixels.copyOf()
        PaintShader(color, gray, PaintFinish.SATIN, 10).row(plain, mask, null, null, pixels.size)
        assertArrayEquals(expected, plain)
        val withLayers = pixels.copyOf()
        PaintShader(color, gray, PaintFinish.SATIN, 10).row(withLayers, mask, IntArray(pixels.size) { gray(40) }, IntArray(pixels.size) { gray(160) }, pixels.size)
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

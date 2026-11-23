package com.irvingguerra.litedash

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRemovalTest {
    private val w = 200
    private val h = 120

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Imagen [w]×[h] con [background] por renglón y un "auto" oscuro de 80×40 en (60, 30). */
    private fun photo(background: (y: Int) -> Int): IntArray {
        val p = IntArray(w * h) { background(it / w) }
        for (y in 30 until 70) for (x in 60 until 140) p[y * w + x] = gray(0x30)
        return p
    }

    @Test
    fun fondoLisoSeQuitaYSeRecortaAlAuto() {
        val cut = removeBackground(photo { gray(0xEE) }, w, h, level = 0)
        assertNotNull(cut)
        cut!!
        assertEquals(80, cut.width)
        assertEquals(40, cut.height)
        // Adentro opaco y con su color; la orilla, suavizada.
        assertEquals(gray(0x30), cut.pixels[20 * 80 + 40])
        assertTrue((cut.pixels[0] ushr 24) < 255)
        assertTrue((cut.pixels[40] ushr 24) in 1 until 255)
    }

    @Test
    fun degradadoSeQuitaConMasIntensidad() {
        // De 220 arriba a 161 abajo, como una pared con luz desde arriba.
        val p = photo { y -> gray(220 - y / 2) }
        val cut = removeBackground(p, w, h, level = 1)
        assertNotNull(cut)
        assertEquals(80, cut!!.width)
        assertEquals(40, cut.height)
    }

    @Test
    fun reflejoDelPisoSoloSeQuitaEnNivelAlto() {
        val p = photo { gray(0xEE) }
        // Reflejo gris claro pegado abajo del auto.
        for (y in 70 until 80) for (x in 60 until 140) p[y * w + x] = gray(0xA0)
        assertEquals(50, removeBackground(p, w, h, level = 0)!!.height)
        assertEquals(40, removeBackground(p, w, h, level = 3)!!.height)
    }

    @Test
    fun sinFondoClaroSeRechaza() {
        // Todo del mismo color: se iría todo.
        assertNull(removeBackground(IntArray(w * h) { gray(0xEE) }, w, h, level = 0))
        // Ruido: no hay fondo que seguir.
        val random = Random(7)
        assertNull(removeBackground(IntArray(w * h) { (0xFF shl 24) or random.nextInt(0x1000000) }, w, h, level = 0))
    }

    @Test
    fun loTransparenteDeAdentroSigueTransparente() {
        // Un logo calado: un hueco transparente (negro por debajo) en medio del "auto", y una franja a medias.
        val p = photo { gray(0xEE) }
        for (y in 45 until 55) for (x in 90 until 110) p[y * w + x] = 0x00000000
        for (y in 32 until 34) for (x in 62 until 138) p[y * w + x] = (0x80 shl 24) or 0x303030
        val cut = removeBackground(p, w, h, level = 0)!!
        assertEquals(80, cut.width)
        // (100, 50) en la foto = (40, 20) en el recorte: el hueco no se vuelve negro opaco.
        assertEquals(0, cut.pixels[20 * 80 + 40] ushr 24)
        // La franja a medias no sube a opaca.
        assertTrue((cut.pixels[3 * 80 + 40] ushr 24) <= 0x80)
    }
}

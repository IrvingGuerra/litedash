package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {
    private fun tiles(p: MapProjection, ts: Float = 256f) = p.visibleTiles(10.5, 20.5, ts).toList().chunked(2).map { it[0] to it[1] }

    @Test
    fun flatIsTheScreenRectangle() {
        // 1024 × 600 con tu posición al centro de una tesela de 256: 5 columnas (−2…+2) y 3 filas.
        val p = MapProjection(1024f, 600f, 512f, 360f, turn = 0f, tilted = false, density = 1f)
        val t = tiles(p)
        assertEquals(5 * 3, t.size)
        assertEquals((8..12).toSet(), t.map { it.first }.toSet())
        assertEquals((19..21).toSet(), t.map { it.second }.toSet())
    }

    @Test
    fun turnedDropsTheCornersOffScreen() {
        // Girado 45°, el rectángulo que encierra lo visible trae esquinas que no se ven.
        val p = MapProjection(1024f, 600f, 512f, 360f, turn = 45f, tilted = false, density = 1f)
        val t = tiles(p)
        val xs = t.map { it.first }
        val ys = t.map { it.second }
        assertTrue(t.size < (xs.max() - xs.min() + 1) * (ys.max() - ys.min() + 1))
    }

    @Test
    fun tiltedLooksAheadAndFarIsSmaller() {
        val p = MapProjection(1024f, 600f, 512f, 468f, turn = 0f, tilted = true, density = 1f)
        // Lo de adelante (arriba) se aleja: 256 px del plano ocupan menos en pantalla.
        assertTrue(p.edge < 468f)
        val t = tiles(p)
        assertTrue(t.any { it.second <= 20 - 3 })
        // Lo que se pide nunca pasa de lo que cabe en memoria con su holgura sin crecer sin fin.
        assertTrue(t.size in 10..80)
    }

    @Test
    fun tiltedTallViewStillSeesAhead() {
        // Vistas altas (más de ~620 dp): antes las esquinas de arriba quedaban arriba del horizonte y su
        // inversa salía volteada (sin teselas de adelante). Ahora la cámara se aleja con el alto.
        for ((w, h, density) in listOf(Triple(1200f, 1600f, 1.5f), Triple(800f, 1280f, 1f), Triple(1920f, 1080f, 1f))) {
            val p = MapProjection(w, h, w / 2, h * 0.78f, turn = 30f, tilted = true, density = density)
            val ts = tilePx(density)
            val t = p.visibleTiles(10.5, 20.5, ts).toList().chunked(2)
            val ahead = (h * 0.78f) / ts // filas hasta la orilla de arriba si fuera plano
            assertTrue("$w×$h: ${t.size}", t.any { it[1] < 20.5 - ahead })
            assertTrue("$w×$h: ${t.size}", t.size in 10..250)
        }
    }
}

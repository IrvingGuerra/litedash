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

    /** Las teselas que se piden con menos detalle a lo lejos (como el mapa): una por cada "madre" o "abuela". */
    private fun withDetail(p: MapProjection, ts: Float): Int {
        val span = p.visibleTiles(10.5, 20.5, ts)
        val picked = HashSet<Triple<Int, Int, Int>>()
        for (i in span.indices step 2) {
            val drop = detailDrop(p.scaleOf(10.5, 20.5, ts, span[i], span[i + 1]))
            picked += Triple(drop, Math.floorDiv(span[i], 1 shl drop), Math.floorDiv(span[i + 1], 1 shl drop))
        }
        return picked.size
    }

    @Test
    fun farTilesUseLessDetail() {
        // Plano: todo de cerca, mismo número de teselas.
        val flat = MapProjection(1024f, 600f, 512f, 360f, turn = 0f, tilted = false, density = 1f)
        assertEquals(tiles(flat).size, withDetail(flat, 256f))
        // Inclinado: muchas menos, sobre todo en pantallas anchas (antes 100–120 en 1920×720).
        for ((size, density) in listOf((1024f to 600f) to 1f, (1920f to 720f) to 1.5f, (1920f to 1080f) to 1f, (800f to 1280f) to 1f)) {
            val (w, h) = size
            val ts = 256f * density
            val p = MapProjection(w, h, w / 2, h * 0.78f, turn = 0f, tilted = true, density = density)
            val all = tiles(p, ts).size
            val fewer = withDetail(p, ts)
            assertTrue("${w}x$h: $fewer de $all", fewer <= all * 0.65)
        }
        assertEquals(0, detailDrop(1f))
        assertEquals(1, detailDrop(0.5f))
        assertEquals(2, detailDrop(0.2f))
    }
}

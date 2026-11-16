package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {
    private fun span(p: MapProjection, fx: Double, fy: Double, ts: Float) = IntList().also { p.visibleTiles(fx, fy, ts, it) }

    private fun tiles(p: MapProjection, ts: Float = 256f): List<Pair<Int, Int>> {
        val s = span(p, 10.5, 20.5, ts)
        return (0 until s.size step 2).map { s[it] to s[it + 1] }
    }

    private fun projection(w: Float, h: Float, atX: Float, atY: Float, turn: Float, tilt: Float, density: Float = 1f) =
        MapProjection.of(w, h, atX, atY, turn, tilt, density)

    @Test
    fun flatIsTheScreenRectangle() {
        // 1024 × 600 con tu posición al centro de una tesela de 256: 5 columnas (−2…+2) y 3 filas.
        val t = tiles(projection(1024f, 600f, 512f, 360f, turn = 0f, tilt = 0f))
        assertEquals(5 * 3, t.size)
        assertEquals((8..12).toSet(), t.map { it.first }.toSet())
        assertEquals((19..21).toSet(), t.map { it.second }.toSet())
    }

    @Test
    fun turnedDropsTheCornersOffScreen() {
        // Girado 45°, el rectángulo que encierra lo visible trae esquinas que no se ven.
        val t = tiles(projection(1024f, 600f, 512f, 360f, turn = 45f, tilt = 0f))
        val xs = t.map { it.first }
        val ys = t.map { it.second }
        assertTrue(t.size < (xs.max() - xs.min() + 1) * (ys.max() - ys.min() + 1))
    }

    @Test
    fun tiltedLooksAheadAndFarIsSmaller() {
        val p = projection(1024f, 600f, 512f, 468f, turn = 0f, tilt = 1f)
        // Lo de adelante (arriba) se aleja: 256 px del plano ocupan menos en pantalla.
        assertTrue(p.edge < 468f)
        val t = tiles(p)
        assertTrue(t.any { it.second <= 20 - 3 })
        // Lo que se pide nunca pasa de lo que cabe en memoria con su holgura sin crecer sin fin.
        assertTrue(t.size in 10..120)
    }

    @Test
    fun tiltedTallViewStillSeesAhead() {
        // Vistas altas (más de ~620 dp): antes las esquinas de arriba quedaban arriba del horizonte y su
        // inversa salía volteada (sin teselas de adelante). Ahora la cámara se aleja con el alto.
        for ((w, h, density) in listOf(Triple(1200f, 1600f, 1.5f), Triple(800f, 1280f, 1f), Triple(1920f, 1080f, 1f))) {
            val p = projection(w, h, w / 2, h * 0.78f, turn = 30f, tilt = 1f, density = density)
            val ts = tilePx(density)
            val s = span(p, 10.5, 20.5, ts)
            val rows = (0 until s.size step 2).map { s[it + 1] }
            val ahead = (h * 0.78f) / ts // filas hasta la orilla de arriba si fuera plano
            assertTrue("$w×$h: ${rows.size}", rows.any { it < 20.5 - ahead })
            assertTrue("$w×$h: ${rows.size}", rows.size in 10..250)
        }
    }

    @Test
    fun tiltedSquareFillsToTheTop() {
        // Widget cuadrado (480 × 480): antes el mapa acababa a ~26 % del alto y arriba solo había cielo.
        for ((w, h) in listOf(480f to 480f, 1024f to 600f, 600f to 800f, 1920f to 1080f, 1920f to 720f, 800f to 1280f)) {
            val p = projection(w, h, w / 2, h * 0.78f, turn = 0f, tilt = 1f)
            val real = plan(p, 19.4326, -99.1332, 16, 256f)
            assertTrue("${w}x$h: edge ${p.edge}", p.edge <= h * 0.1f)
            assertTrue("${w}x$h: ${real.count}", real.count <= 120)
        }
    }

    @Test
    fun partialTiltMovesSmoothly() {
        // Al alejar con dos dedos se va acostando: con inclinación a medias el final del mapa se mueve poco a poco, sin
        // brincos (entre 0.49 y 0.51 casi nada) y lo que se pide sigue acotado.
        val w = 1024f
        val h = 600f
        var lastEdge = Float.NEGATIVE_INFINITY
        var lastTilt = 0f
        for (step in 0..100) {
            val tilt = step / 100f
            val atY = h * (0.6f + 0.22f * tilt)
            val p = projection(w, h, w / 2, atY, turn = 0f, tilt = tilt)
            if (tilt > 0f) {
                // Más inclinado, el final del mapa baja hacia la vista (plano queda muy arriba): nunca al revés ni de golpe.
                if (lastTilt > 0f) assertTrue("$tilt: ${p.edge} vs $lastEdge", p.edge >= lastEdge && p.edge - lastEdge < h * 0.05f)
                lastEdge = p.edge
                lastTilt = tilt
            }
            val real = plan(p, 19.4326, -99.1332, 19, 256f)
            assertTrue("$tilt: ${real.count}", real.count in 1..120)
        }
        val a = projection(w, h, w / 2, h * 0.71f, turn = 0f, tilt = 0.49f)
        val b = projection(w, h, w / 2, h * 0.71f, turn = 0f, tilt = 0.51f)
        assertTrue(kotlin.math.abs(a.edge - b.edge) < h * 0.05f)
    }

    @Test
    fun reusedProjectionMatchesANewOne() {
        // La misma, rehecha con otros datos, da lo mismo que una nueva (no le queda nada de antes).
        val reused = projection(800f, 480f, 400f, 300f, turn = 70f, tilt = 1f, density = 2f)
        assertTrue(reused.set(1024f, 600f, 512f, 420f, -30f, 0.4f, 1f))
        assertFalse(reused.set(1024f, 600f, 512f, 420f, -30f, 0.4f, 1f))
        val fresh = projection(1024f, 600f, 512f, 420f, turn = -30f, tilt = 0.4f)
        assertArrayEquals(fresh.values, reused.values, 1e-5f)
        assertEquals(fresh.edge, reused.edge, 1e-3f)
        val a = FloatArray(3)
        val b = FloatArray(3)
        assertTrue(reused.toPlane(300f, 200f, a) && fresh.toPlane(300f, 200f, b))
        assertEquals(b[0], a[0], 1e-2f)
        assertEquals(b[1], a[1], 1e-2f)
        // Ida y vuelta: de la pantalla al plano y de regreso, el mismo punto.
        assertTrue(reused.toScreen(a[0], a[1], b))
        assertEquals(300f, b[0], 0.05f)
        assertEquals(200f, b[1], 0.05f)
    }

    /** El plan real del mapa (MapFrame.kt); dónde se dibuja no importa aquí. */
    private fun plan(p: MapProjection, lat: Double, lon: Double, z: Int, ts: Float): TilePlan =
        TilePlan().also { it.fill(p, tileX(lon, z), tileY(lat, z), z, 0f, 0f, ts) }

    @Test
    fun planListsEachTileOnceFarFirst() {
        val z = 16
        val p = projection(1920f, 720f, 960f, 590f, turn = 25f, tilt = 1f, density = 1.5f)
        val real = plan(p, 19.4326, -99.1332, z, 384f)
        val keys = (0 until real.count).map { real.key(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.toSet(), real.orderedKeys())
        // Lo lejano (menos zoom) va antes que lo cercano: lo cercano se dibuja encima.
        val zs = (0 until real.count).map { real.z[it] }
        assertEquals(zs.sorted(), zs)
        // Rellenar otra vez con lo mismo da lo mismo (se reusan los arreglos).
        real.fill(p, tileX(-99.1332, z), tileY(19.4326, z), z, 0f, 0f, 384f)
        assertEquals(keys, (0 until real.count).map { real.key(it) })
    }

    @Test
    fun farTilesUseLessDetail() {
        val lat = 19.4326
        val lon = -99.1332
        val z = 16
        // Plano: todo de cerca, con todo el detalle y una tesela por cada una visible.
        val flat = projection(1024f, 600f, 512f, 360f, turn = 0f, tilt = 0f)
        val flatPlan = plan(flat, lat, lon, z, 256f)
        assertTrue((0 until flatPlan.count).all { flatPlan.z[it] == z })
        assertEquals(span(flat, tileX(lon, z), tileY(lat, z), 256f).size / 2, flatPlan.count)
        // Inclinado: lo lejano (arriba) sale con menos zoom que lo cercano, y se piden muchas menos
        // (sobre todo en pantallas anchas: antes 100–120 en 1920×720).
        for ((size, density) in listOf((1024f to 600f) to 1f, (1920f to 720f) to 1.5f, (1920f to 1080f) to 1f, (800f to 1280f) to 1f)) {
            val (w, h) = size
            val ts = 256f * density
            val p = projection(w, h, w / 2, h * 0.82f, turn = 0f, tilt = 1f, density = density)
            val fy = tileY(lat, z)
            val all = span(p, tileX(lon, z), fy, ts).size / 2
            val real = plan(p, lat, lon, z, ts)
            val n = real.count
            val label = "${w}x$h"
            assertTrue("$label: $n de $all", n <= all * 0.7)
            // Fila de arriba de cada tesela en el zoom de la vista (una "madre" cubre 2 filas, una "abuela" 4).
            val row = IntArray(n) { real.y[it] shl (z - real.z[it]) }
            // Lo de cerca (de una fila arriba de tu posición para abajo) va con todo el detalle.
            val near = (0 until n).filter { row[it] >= fy.toInt() - 1 }
            assertTrue(label, near.isNotEmpty() && near.all { real.z[it] == z })
            // Lo más lejano (la fila de más arriba) va con menos zoom que lo cercano.
            val farthest = (0 until n).minBy { row[it] }
            assertTrue("$label: zoom lejano ${real.z[farthest]}", real.z[farthest] < z)
            // Y nunca al revés: una tesela más lejana no lleva más detalle que una más cercana.
            for (a in 0 until n) for (b in 0 until n) {
                val aBottom = row[a] + (1 shl (z - real.z[a]))
                if (aBottom <= row[b]) assertTrue("$label: ${real.z[a]} > ${real.z[b]}", real.z[a] <= real.z[b])
            }
        }
        assertEquals(0, detailDrop(1f))
        assertEquals(1, detailDrop(0.5f))
        assertEquals(2, detailDrop(0.2f))
    }
}

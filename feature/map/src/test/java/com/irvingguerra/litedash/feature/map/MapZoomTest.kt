package com.irvingguerra.litedash.feature.map

import kotlin.math.abs
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZoomTest {
    private fun level(step: ZoomStep) = step.level + log2(step.scale)

    @Test
    fun zoomChangesLevelHalfwayWithoutJumps() {
        // De 20 a 15 de corrido: el nivel cambia a la mitad entre dos y nivel + escala nunca brinca.
        var last = 20f
        for (i in 0..500) {
            val z = 20f - 5f * i / 500
            val step = zoomStep(z, MIN_ZOOM, MAX_ZOOM)
            assertEquals(z, level(step), 1e-4f)
            assertTrue(step.scale in SCALE_DOWN - 1e-4f..SCALE_UP + 1e-4f)
            assertTrue(abs(level(step) - last) < 0.02f)
            last = level(step)
        }
        assertEquals(18, zoomStep(18.49f, MIN_ZOOM, MAX_ZOOM).level)
        assertEquals(19, zoomStep(18.51f, MIN_ZOOM, MAX_ZOOM).level)
    }

    @Test
    fun pinchPassesToTheNextLevelHalfway() {
        assertEquals(17, pinchStep(1f, 1.4f, 17, MIN_ZOOM, MAX_ZOOM).level)
        val up = pinchStep(1f, 1.5f, 17, MIN_ZOOM, MAX_ZOOM)
        assertEquals(18, up.level)
        assertEquals(17 + log2(1.5f), level(up), 1e-4f)
        val down = pinchStep(1f, 0.6f, 17, MIN_ZOOM, MAX_ZOOM)
        assertEquals(16, down.level)
        assertEquals(17 + log2(0.6f), level(down), 1e-4f)
    }

    @Test
    fun manySmallPinchesEqualOneBig() {
        // Varios movimientos antes de recomponer dan lo mismo que uno grande.
        var step = ZoomStep(16, 1f)
        repeat(20) { step = pinchStep(step.scale, 1.1f, step.level, MIN_ZOOM, MAX_ZOOM) }
        val once = pinchStep(1f, Math.pow(1.1, 20.0).toFloat(), 16, MIN_ZOOM, MAX_ZOOM)
        assertEquals(once.level, step.level)
        assertEquals(once.scale, step.scale, 1e-3f)
    }

    @Test
    fun pinchOnlyGivesALittleAtTheEdges() {
        val closest = pinchStep(1f, 3f, MAX_ZOOM, MIN_ZOOM, MAX_ZOOM)
        assertEquals(MAX_ZOOM, closest.level)
        assertEquals(1.15f, closest.scale, 1e-6f)
        val farthest = pinchStep(1f, 0.2f, MIN_ZOOM, MIN_ZOOM, MAX_ZOOM)
        assertEquals(MIN_ZOOM, farthest.level)
        assertEquals(0.85f, farthest.scale, 1e-6f)
    }

    @Test
    fun tiltGoesFlatOneLevelOut() {
        assertEquals(1f, tiltFor(20f, 20f), 0f)
        assertEquals(0f, tiltFor(19f, 20f), 0f)
        assertEquals(0.5f, tiltFor(19.5f, 20f), 1e-6f)
        assertEquals(0f, tiltFor(15f, 20f), 0f)
        assertEquals(1f, tiltFor(20.2f, 20f), 0f)
        // Monótono y continuo al alejar con los dedos.
        var last = 1f
        for (i in 0..200) {
            val t = tiltFor(20f - i / 100f, 20f)
            assertTrue(t <= last && last - t < 0.011f)
            last = t
        }
    }

    @Test
    fun navigationZoomGoesOutWithSpeed() {
        // Detenido, un nivel más cerca que la fuente (como Waze); a 110 km/h o más, tres niveles más lejos que eso; en
        // medio, en línea y en pasos de 0.05.
        assertEquals(21f, navZoomFor(null, 20, turnAhead = false, northUp = false), 0f)
        assertEquals(21f, navZoomFor(0f, 20, turnAhead = false, northUp = false), 0f)
        assertEquals(19.5f, navZoomFor(55f, 20, turnAhead = false, northUp = false), 1e-4f)
        assertEquals(18f, navZoomFor(110f, 20, turnAhead = false, northUp = false), 1e-4f)
        assertEquals(18f, navZoomFor(180f, 20, turnAhead = false, northUp = false), 1e-4f)
        assertEquals(20.6f, navZoomFor(14f, 20, turnAhead = false, northUp = false), 1e-4f)
        // Cerca de la vuelta se acerca, sin pasar de lo más cerca de la fuente (más cerca no se vería la esquina); al norte
        // (plano), uno más lejos.
        assertEquals(18.4f, navZoomFor(110f, 20, turnAhead = true, northUp = false), 1e-4f)
        assertEquals(20f, navZoomFor(0f, 20, turnAhead = true, northUp = false), 0f)
        assertEquals(20f, navZoomFor(0f, 20, turnAhead = false, northUp = true), 0f)
    }

    @Test
    fun overzoomScalesTheLastLevel() {
        // Más cerca que la fuente: el último nivel, agrandado (hasta ×2 por nivel de más).
        val step = zoomStep(20f + NAV_OVERZOOM, MIN_ZOOM, 20)
        assertEquals(20, step.level)
        assertEquals(2f, step.scale, 1e-4f)
        // Al soltarlo con los dedos no brinca: pellizcar para acercar no pasa de ahí y para alejar baja desde ahí.
        assertEquals(2f, pinchStep(2f, 1.3f, 20, MIN_ZOOM, 20).scale, 1e-4f)
        assertEquals(1.6f, pinchStep(2f, 0.8f, 20, MIN_ZOOM, 20).scale, 1e-4f)
        // Sin navegación, el tope de siempre (`EDGE_UP`).
        assertEquals(1.15f, pinchStep(1f, 2f, 20, MIN_ZOOM, 20).scale, 1e-4f)
    }

    @Test
    fun approachStopsAtTheGoal() {
        assertEquals(18.6f, approach(18f, 20f, 0.6f), 1e-6f)
        assertEquals(19.4f, approach(20f, 18f, 0.6f), 1e-6f)
        assertEquals(20f, approach(19.8f, 20f, 0.6f), 0f)
        assertEquals(0f, approach(0.3f, 0f, 0.5f), 0f)
    }

    @Test
    fun glideTurnsTheShortWay() {
        val from = Pose().apply { set(19.0, -99.0, 350f) }
        val to = Pose().apply { set(19.001, -99.001, 10f) }
        val out = Pose()
        between(from, to, 0.5f, out)
        // De 350° a 10° pasa por 0°, no por 180°.
        assertEquals(360f, out.bearing, 1e-3f)
        assertEquals(19.0005, out.lat, 1e-9)
        // Sin rumbo nuevo, sin rumbo; sin rumbo de antes, el nuevo directo.
        between(from, Pose().apply { set(19.0, -99.0, Float.NaN) }, 0.5f, out)
        assertTrue(out.bearing.isNaN())
        between(Pose().apply { set(19.0, -99.0, Float.NaN) }, to, 0.3f, out)
        assertEquals(10f, out.bearing, 0f)
        assertEquals(-20f, shortTurn(10f, 350f), 1e-4f)
        assertEquals(20f, shortTurn(350f, 10f), 1e-4f)
    }

    @Test
    fun fallbackTakesTheClosestParentThatIsThere() {
        val z = 18
        val x = 59_003
        val y = 116_731
        val have = setOf(tileKey(z - 3, x shr 3, y shr 3), tileKey(z - 5, x shr 5, y shr 5))
        assertEquals(3, parentFallback(z, x, y) { it in have })
        assertEquals(0, parentFallback(z, x, y) { false })
        // No más de FALLBACK_LEVELS arriba.
        assertEquals(0, parentFallback(z, x, y) { it == tileKey(z - 6, x shr 6, y shr 6) })
        // Ni arriba del zoom 0.
        assertEquals(0, parentFallback(0, 0, 0) { true })
        assertEquals(2, parentFallback(2, 3, 1) { it == tileKey(0, 0, 0) })
    }
}

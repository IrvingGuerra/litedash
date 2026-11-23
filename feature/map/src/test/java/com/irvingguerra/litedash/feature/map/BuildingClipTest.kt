package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingClipTest {
    private val out = FloatArray(64)
    private val temp = FloatArray(64)

    @Test
    fun aShapeInsideStaysTheSame() {
        val quad = floatArrayOf(10f, 10f, 50f, 10f, 50f, 50f, 10f, 50f)
        assertEquals(4, clipPolygon(quad, 4, 0f, 0f, 100f, 100f, out, temp))
        assertEquals(quad.toList(), out.copyOf(8).toList())
    }

    @Test
    fun aHugeWallNearTheCameraIsCutToTheView() {
        // Un muro de 20 000 px (pegado a la cámara): queda dentro de la vista con margen.
        val wall = floatArrayOf(-10_000f, 50f, 10_000f, 50f, 10_000f, 9_000f, -10_000f, 9_000f)
        val n = clipPolygon(wall, 4, -64f, -64f, 864f, 544f, out, temp)
        assertTrue(n >= 3)
        for (i in 0 until n) {
            assertTrue(out[2 * i] in -64f..864f)
            assertTrue(out[2 * i + 1] in -64f..544f)
        }
    }

    @Test
    fun aShapeOutsideDisappears() {
        val far = floatArrayOf(2_000f, 2_000f, 2_100f, 2_000f, 2_100f, 2_100f)
        assertEquals(0, clipPolygon(far, 3, 0f, 0f, 100f, 100f, out, temp))
    }
}

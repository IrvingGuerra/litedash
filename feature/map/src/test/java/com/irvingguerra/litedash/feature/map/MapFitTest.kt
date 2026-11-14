package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class MapFitTest {
    private val cdmx = 19.43

    /** Diámetro del círculo en px al zoom [z] (como lo dibuja el mapa). */
    private fun diameterPx(km: Float, lat: Double, z: Int, tilePx: Float): Double {
        val kmPerPx = 2 * PI * 6371.0 * cos(Math.toRadians(lat)) / ((1 shl z) * tilePx)
        return 2 * km / kmPerPx
    }

    @Test
    fun circleFitsWithRoom() {
        for (km in listOf(10f, 25f, 50f)) {
            val z = zoomToFit(km, cdmx, 600f, 512f)
            assertTrue("$km km en z$z", diameterPx(km, cdmx, z, 512f) <= 600 * 0.8)
            // Uno más cerca ya no cabría: es el más cercano posible.
            if (z < FIT_MAX_ZOOM) assertTrue(diameterPx(km, cdmx, z + 1, 512f) > 600 * 0.8)
        }
    }

    @Test
    fun biggerRadiusIsFarther() {
        val zooms = listOf(10f, 25f, 50f).map { zoomToFit(it, cdmx, 600f, 512f) }
        assertEquals(zooms.sortedDescending(), zooms)
        assertTrue(zooms.first() > zooms.last())
    }

    @Test
    fun staysInRange() {
        assertEquals(FIT_MIN_ZOOM, zoomToFit(200f, cdmx, 300f, 512f))
        assertEquals(FIT_MAX_ZOOM, zoomToFit(0.01f, cdmx, 2000f, 256f))
        assertEquals(FIT_MAX_ZOOM, zoomToFit(10f, cdmx, 0f, 256f))
    }

    @Test
    fun higherLatitudeZoomsOut() {
        // Más al norte el mismo radio ocupa más del mapa (Mercator): toca alejar igual o más.
        assertTrue(zoomToFit(25f, 60.0, 600f, 512f) <= zoomToFit(25f, 0.0, 600f, 512f))
    }
}

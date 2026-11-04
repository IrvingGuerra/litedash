package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {
    @Test
    fun projectionKnownPoints() {
        // Zócalo de la CDMX en zoom 16: tesela 14721 × 29160.
        assertEquals(14721, tileX(-99.1332, 16).toInt())
        assertEquals(29160, tileY(19.4326, 16).toInt())
        // Greenwich y el ecuador caen justo a la mitad.
        assertEquals(0.5, tileX(0.0, 0), 1e-12)
        assertEquals(0.5, tileY(0.0, 0), 1e-12)
        assertEquals(0.0, tileY(85.05112878, 0), 1e-6)
        // Más allá del límite de Mercator no se va al infinito.
        assertEquals(0.0, tileY(90.0, 3), 1e-6)
    }

    @Test
    fun projectionRoundTrip() {
        for (z in listOf(0, 10, 17)) for ((lat, lon) in listOf(19.4326 to -99.1332, -33.86 to 151.21, 64.1 to -21.9)) {
            assertEquals(lon, lonOf(tileX(lon, z), z), 1e-9)
            assertEquals(lat, latOf(tileY(lat, z), z), 1e-9)
        }
    }

    @Test
    fun tileRangeCoversOnlyVisible() {
        // Posición en la orilla de una tesela, al centro de 512 px con teselas de 256: una a cada lado.
        assertEquals(9..10, tileRange(10.0, 256f, 512f, 256f))
        // Una vista de 300 px con la posición a 180 px: de 9.297 a 10.47 → teselas 9 y 10.
        assertEquals(9..10, tileRange(10.0, 180f, 300f, 256f))
    }

    @Test
    fun keysRoundTrip() {
        val k = tileKey(17, 33_000, 131_071)
        assertEquals(17, keyZ(k))
        assertEquals(33_000, keyX(k))
        assertEquals(131_071, keyY(k))
    }

    @Test
    fun zoomBySpeed() {
        assertEquals(17, autoZoom(null, 17))
        assertEquals(17, autoZoom(20f, 17))
        assertEquals(16, autoZoom(40f, 17))
        assertEquals(15, autoZoom(90f, 17))
        // Cerca del umbral no brinca: a 33 sigue en 16, a 28 regresa a 17.
        assertEquals(16, autoZoom(33f, 16))
        assertEquals(17, autoZoom(28f, 16))
        assertEquals(15, autoZoom(68f, 15))
        assertEquals(16, autoZoom(60f, 15))
    }

    @Test
    fun tapCyclesZoom() {
        assertEquals(16, nextZoom(17))
        assertEquals(14, nextZoom(15))
        assertNull(nextZoom(14))
    }

    @Test
    fun cacheControl() {
        val now = 1_000_000L
        assertEquals(now + 604_800_000, expiresAt(now, "max-age=604800, stale-while-revalidate=604800", null))
        // max-age gana sobre Expires.
        assertEquals(now + 60_000, expiresAt(now, "public, max-age=60", "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(now, expiresAt(now, "no-cache", null))
        assertEquals(now, expiresAt(now, "private, no-store", null))
        assertEquals(now + DEFAULT_TTL_MS, expiresAt(now, null, null))
        assertEquals(now + DEFAULT_TTL_MS, expiresAt(now, "public", null))
    }

    @Test
    fun expiresHeader() {
        val now = 1_000_000L
        // Relativo a la hora del servidor: 1 h después de su Date.
        assertEquals(now + 3_600_000, expiresAt(now, null, "Wed, 04 Nov 2026 19:00:00 GMT", "Wed, 04 Nov 2026 18:00:00 GMT"))
        // Sin Date, contra el reloj del equipo.
        val at = httpDate("Wed, 04 Nov 2026 19:00:00 GMT")!!
        assertEquals(at, expiresAt(now, null, "Wed, 04 Nov 2026 19:00:00 GMT"))
        assertEquals(1_793_818_800_000L, at)
        // Expires inválido ("0") = ya venció.
        assertEquals(now, expiresAt(now, null, "0"))
    }

    @Test
    fun tileFileRoundTrip() {
        val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
        val back = TileFile.decode(TileFile(42L, "\"abc\"", "Wed, 04 Nov 2026 18:00:00 GMT", png).encode())!!
        assertEquals(42L, back.expires)
        assertEquals("\"abc\"", back.etag)
        assertEquals("Wed, 04 Nov 2026 18:00:00 GMT", back.lastModified)
        assertArrayEquals(png, back.png)
        val bare = TileFile.decode(TileFile(1L, null, null, png).encode())!!
        assertNull(bare.etag)
        assertNull(bare.lastModified)
        assertNull(TileFile.decode(byteArrayOf(1, 2)))
    }

    @Test
    fun trimOldestFirst() {
        val files = listOf(CachedTile("c", 30, 3), CachedTile("a", 30, 1), CachedTile("b", 30, 2), CachedTile("d", 30, 4))
        assertTrue(tilesToTrim(files, max = 120, target = 60).isEmpty())
        assertEquals(listOf("a", "b"), tilesToTrim(files, max = 100, target = 60))
        assertEquals(listOf("a"), tilesToTrim(files, max = 100, target = 90))
    }
}

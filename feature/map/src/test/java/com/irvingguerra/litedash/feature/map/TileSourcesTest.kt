package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class TileSourcesTest {
    @Test
    fun closeTilesComeFromTheDataZoom() {
        // Hasta el 14, cada una se lee tal cual (las del 12 y 13 son lo lejano del mapa inclinado).
        for (z in 12..14) assertEquals(tileKey(z, 100, 200), dataOrigin(tileKey(z, 100, 200)))
        // Del 15 en adelante, de la del 14 que la contiene.
        val origin = tileKey(14, 3680, 7290)
        assertEquals(origin, dataOrigin(tileKey(16, 3680 * 4 + 3, 7290 * 4 + 1)))
        assertEquals(origin, dataOrigin(tileKey(20, 3680 * 64, 7290 * 64 + 63)))
    }

    @Test
    fun piecesCoverTheWholeOriginOnce() {
        val origin = tileKey(14, 3680, 7290)
        for (z in 15..20) {
            val side = 1 shl (z - 14)
            val seen = HashSet<Pair<Int, Int>>()
            for (dx in 0 until side step maxOf(1, side / 8)) for (dy in 0 until side step maxOf(1, side / 8)) {
                val key = tileKey(z, 3680 * side + dx, 7290 * side + dy)
                assertEquals(origin, dataOrigin(key))
                val piece = pieceX(key, origin) to pieceY(key, origin)
                assertEquals(dx to dy, piece)
                seen += piece
            }
            assertEquals(minOf(side, 8) * minOf(side, 8), seen.size)
        }
        // La misma tesela es su único pedazo.
        assertEquals(0, pieceX(origin, origin))
        assertEquals(0, pieceY(origin, origin))
    }
}

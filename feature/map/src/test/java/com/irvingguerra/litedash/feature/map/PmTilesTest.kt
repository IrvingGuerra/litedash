package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PmTilesTest {
    @Test
    fun tileIdsFollowTheSpec() {
        // Los de la especificación de PMTiles v3.
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
        assertEquals(19078479L, PmTiles.tileId(12, 3423, 1763))
    }

    @Test
    fun directoryWithContiguousOffsets() {
        // 3 entradas: ids 5, 6 (+1), 10 (+4); corridas 1, 1, 2; largos 100, 50, 70; desplazamientos 0+1, 0 (seguido), 400+1.
        val bytes = byteArrayOf(3, 5, 1, 4, 1, 1, 2, 100, 50, 70, 1, 0, -111, 3)
        val e = PmTiles.parseDirectory(bytes)
        assertEquals(listOf(5L, 6L, 10L), e.map { it.tileId })
        assertEquals(listOf(0L, 100L, 400L), e.map { it.offset })
        assertEquals(10L, PmTiles.findEntry(e, 11)!!.tileId)
        assertEquals(6L, PmTiles.findEntry(e, 8)!!.tileId)
        assertNull(PmTiles.findEntry(e, 4))
    }
}

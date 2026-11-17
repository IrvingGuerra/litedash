package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeAreaTest {
    private fun tile(col: Int, row: Int, w: Int, h: Int) = Tile("t$col$row", TileTypes.CLOCK, col, row, w, h)

    @Test
    fun emptyGridIsAllFree() {
        assertEquals(Cells(0, 0, 20, 10), emptyList<Tile>().freeArea(Grid(20, 10)))
    }

    @Test
    fun widgetsOnTheRightLeaveTheLeft() {
        // Columna de widgets a la derecha (de la 12 a la 19): libre a la izquierda.
        val tiles = listOf(tile(12, 0, 8, 4), tile(12, 4, 8, 6))
        assertEquals(Cells(0, 0, 12, 10), tiles.freeArea(Grid(20, 10)))
    }

    @Test
    fun widgetsBelowLeaveTheTop() {
        val tiles = listOf(tile(0, 12, 10, 8))
        assertEquals(Cells(0, 0, 10, 12), tiles.freeArea(Grid(10, 20)))
    }

    @Test
    fun fullGridHasNoFreeArea() {
        assertNull(listOf(tile(0, 0, 4, 4)).freeArea(Grid(4, 4)))
    }
}

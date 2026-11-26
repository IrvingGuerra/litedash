package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** La migración de la Pila de la 1.5.20: cada Pila guardada queda como un widget normal en su mismo lugar. */
class LegacyStackTest {
    private fun stack(data: String) = Tile("stack-1", "stack", 2, 3, 8, 6, data, plain = true)

    @Test
    fun theFaceInViewTakesThePlaceOfTheStack() {
        val t = migrateStack1520(stack("clock-1|car-1,car,|clock-1,clock,date%3Doff|music-1,music,"))
        assertEquals(Tile("stack-1", TileTypes.CLOCK, 2, 3, 8, 6, "date=off", plain = true), t)
    }

    @Test
    fun withoutCurrentOrWithAnUnknownOneTheFirstFace() {
        assertEquals(TileTypes.CAR to "id=evo", migrateStack1520(stack("|car-1,car,id%3Devo|music-1,music,")).let { it.type to it.data })
        assertEquals(TileTypes.MUSIC, migrateStack1520(stack("gone|bad|music-1,music,|car-1,car,")).type)
    }

    @Test
    fun damagedStacksNeverLeaveAHole() {
        for (data in listOf("", "x", "x|bad|,music,|st-1,stack,|clock-1,clock,%ZZ")) {
            val t = migrateStack1520(stack(data))
            assertEquals(TileTypes.MUSIC, t.type)
            assertEquals(listOf(2, 3, 8, 6), listOf(t.col, t.row, t.width, t.height))
        }
    }

    @Test
    fun savedLayoutsComeBackWithoutStacks() {
        val text = "map-0\tmap\t0\t0\t9\t7\t\t\nstack-1\tstack\t9\t0\t4\t4\t|music-1a,music,|car-1b,car,\t"
        val tiles = parseLayout(text)
        assertEquals(listOf(TileTypes.MAP, TileTypes.MUSIC), tiles.map { it.type })
        assertEquals("stack-1", tiles[1].id)
    }

    @Test
    fun otherTilesAreUntouched() {
        val t = Tile("clock-1", TileTypes.CLOCK, 0, 0, 2, 2, "a=b")
        assertEquals(t, migrateStack1520(t))
    }
}

package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo que la 1.5.16 adopta de los widgets de hasta la 1.5.15 (recorridos del acomodo más nuevo al más viejo). */
class WidgetAdoptionTest {
    private fun tile(type: String, data: String) = Tile("$type-1", type, 0, 0, 4, 2, data)

    private fun adopt(vararg tiles: Tile) = WidgetAdoption().also { a -> tiles.forEach { a.visit(it) } }

    @Test
    fun theFirstAppOfEachWidgetIsAdoptedAndRemovedFromTheWidget() {
        val a = WidgetAdoption()
        assertEquals("x=off", a.visit(tile(TileTypes.PROJECTION, "com.new;x=off")))
        assertEquals("", a.visit(tile(TileTypes.PROJECTION, "com.old")))
        assertEquals("", a.visit(tile(TileTypes.PHONE, "com.dialer")))
        assertNull(a.visit(tile(TileTypes.CLOCK, "com.clock")))
        assertEquals("com.new", a.projection)
        assertEquals("com.dialer", a.phone)
    }

    @Test
    fun theLookOfTheNewestMapIsAdoptedWithoutTheOldDetail() {
        val a = WidgetAdoption()
        // Los datos del Mapa no se tocan (ya no se leen).
        assertNull(a.visit(tile(TileTypes.MAP, "orientation=heading;zoom=mid;style=dark;detail=full")))
        assertNull(a.visit(tile(TileTypes.MAP, "style=light")))
        assertEquals("orientation=heading;zoom=mid;style=dark", a.mapLook)
    }

    @Test
    fun aFactoryMapAdoptsNothingEvenIfAnOlderOneHadALook() {
        // El más nuevo es de fábrica (o solo tenía el «detail» quitado): se queda lo de fábrica.
        assertNull(adopt(tile(TileTypes.MAP, "detail=full"), tile(TileTypes.MAP, "style=dark")).mapLook)
        assertNull(adopt(tile(TileTypes.MAP, ""), tile(TileTypes.MAP, "style=dark")).mapLook)
        assertNull(adopt(tile(TileTypes.CLOCK, "")).mapLook)
    }
}

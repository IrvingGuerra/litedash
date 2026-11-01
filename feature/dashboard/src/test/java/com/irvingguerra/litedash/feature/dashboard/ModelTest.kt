package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    // Las pantallas de la matriz de pruebas, ya sin barra de estado ni dock.
    private val screens = listOf(
        Grid.forSize(1024f, 410f), // 7"
        Grid.forSize(1280f, 530f), // 10"
        Grid.forSize(1333f, 610f), // 11"
        Grid.forSize(1920f, 530f), // ancha
        Grid.forSize(768f, 830f), // vertical
        Grid.forSize(360f, 300f), // teléfono chico
    )

    @Test
    fun gridGrowsWithScreen() {
        assertEquals(Grid(10, 4), screens[0])
        assertTrue(screens[2].columns * screens[2].rows > screens[0].columns * screens[0].rows)
        assertTrue(screens[4].vertical)
    }

    @Test
    fun templatesNeverOverlapOrOverflow() {
        for (c in screens) for (p in Template.entries) {
            val tiles = p.tiles(c)
            assertTrue("$p en ${c.key} vacía", tiles.isNotEmpty())
            for (tile in tiles) assertTrue("$p en ${c.key}: $tile", tiles.fits(tile, c))
            assertEquals("ids repetidos", tiles.size, tiles.map { it.id }.toSet().size)
        }
    }

    @Test
    fun everyTemplateHasCar() {
        for (c in screens) for (p in Template.entries) {
            assertTrue("$p en ${c.key}", p.tiles(c).any { it.type == TileTypes.CAR })
        }
    }

    @Test
    fun doesNotFitWhenOverlapping() {
        val c = Grid(10, 4)
        val a = Tile("a", TileTypes.CAR, 0, 0, 4, 4)
        val list = listOf(a)
        assertFalse(list.fits(Tile("b", TileTypes.MUSIC, 3, 0, 3, 1), c))
        assertTrue(list.fits(Tile("b", TileTypes.MUSIC, 4, 0, 3, 1), c))
        assertFalse(list.fits(Tile("b", TileTypes.MUSIC, 8, 0, 3, 1), c)) // se sale
        assertTrue(list.fits(a.copy(col = 6), c)) // moverse sobre sí misma está bien
    }

    @Test
    fun freeSpotFindsGap() {
        val c = Grid(4, 3)
        val list = listOf(Tile("a", TileTypes.CAR, 0, 0, 4, 2))
        assertEquals(0 to 2, list.freeSpot(2, 1, c))
        assertNull(list.freeSpot(2, 2, c))
    }

    @Test
    fun limitsClamp() {
        assertEquals(3 to 1, SizeLimits(3, 1, 6, 2).clamp(1, 0))
        assertEquals(6 to 2, SizeLimits(3, 1, 6, 2).clamp(9, 9))
    }

    @Test
    fun everyScreenHasSuggestionForItsSize() {
        assertEquals(ScreenSize.SMALL, screens[0].size)
        assertEquals(ScreenSize.LARGE, screens[2].size)
        assertEquals(ScreenSize.VERTICAL, screens[4].size)
        for (c in screens) assertTrue(c.key, c.size in c.suggestedTemplate().sizes)
    }

    @Test
    fun packedFitsHalfScreenWithoutOverlap() {
        val any = { _: String -> SizeLimits(2, 1) }
        for (c in screens) for (p in Template.entries) {
            val half = Grid((c.columns / 2).coerceAtLeast(2), c.rows)
            val packed = p.tiles(c).packed(half, any)
            assertTrue("$p en ${half.key} quedó vacía", packed.isNotEmpty())
            for (tile in packed) assertTrue("$p en ${half.key}: $tile", packed.fits(tile, half))
            // Las primeras (de arriba abajo) son las que se quedan.
            assertEquals(p.tiles(c).minWith(compareBy({ it.row }, { it.col })).id, packed.first().id)
        }
    }
}

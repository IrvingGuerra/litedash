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
        Grid.forSize(1180f, 630f), // 9"
        Grid.forSize(1280f, 530f), // 10"
        Grid.forSize(1333f, 610f), // 11"
        Grid.forSize(1920f, 530f), // ancha
        Grid.forSize(768f, 830f), // vertical
        Grid.forSize(800f, 1180f), // vertical alta
        Grid.forSize(360f, 300f), // teléfono chico
    )

    @Test
    fun gridGrowsWithScreen() {
        assertEquals(Grid(10, 4), screens[0])
        assertTrue(screens[3].columns * screens[3].rows > screens[0].columns * screens[0].rows)
        assertTrue(screens[5].vertical)
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
        // Menos las que son a propósito sin auto.
        for (c in screens) for (p in Template.entries.filter { it.hasCar }) {
            assertTrue("$p en ${c.key}", p.tiles(c).any { it.type == TileTypes.CAR })
        }
    }

    @Test
    fun roadTakesTheCarPlace() {
        for (c in screens) for (p in Template.entries.filter { it.hasCar }) {
            val car = p.tiles(c)
            val road = p.tiles(c, hero = TileTypes.ROAD)
            assertTrue("$p en ${c.key}", road.none { it.type == TileTypes.CAR })
            // Mismo acomodo: solo cambia el tipo de la pieza grande.
            assertEquals(car.map { listOf(it.col, it.row, it.width, it.height) }, road.map { listOf(it.col, it.row, it.width, it.height) })
            assertEquals(car.count { it.type == TileTypes.CAR }, road.count { it.type == TileTypes.ROAD })
        }
    }

    @Test
    fun navigateIsGoneAndMapIsWorthy() {
        for (c in screens) for (p in Template.entries) for (hero in listOf(TileTypes.CAR, TileTypes.ROAD)) {
            val tiles = p.tiles(c, hero)
            assertTrue("$p en ${c.key} pone Navegar", tiles.none { it.type == "navigate" })
            // El mapa, de 3×2 para arriba o nada.
            for (map in tiles.filter { it.type == TileTypes.MAP }) assertTrue("$p en ${c.key}: $map", map.width >= 3 && map.height >= 2)
        }
    }

    // Las de auto (todas menos el teléfono chico), incluidas las verticales.
    private val carScreens = screens.dropLast(1)

    @Test
    fun sideAndPanelLeaveNoGaps() {
        // Con 3 filas el mapa no cabe: va la radio en su lugar y no queda hueco.
        for (c in listOf(Grid(10, 3), Grid(13, 3), Grid(13, 6))) for (p in listOf(Template.SIDE, Template.PANEL)) {
            assertEquals("$p en ${c.key}", c.columns * c.rows, p.tiles(c).sumOf { it.width * it.height })
        }
    }

    @Test
    fun widgetsKeepTheirSizesOnTheirScreens() {
        // En las pantallas de auto (no el teléfono) para las que es cada plantilla:
        // auto de 3×2 o más; música y radio de 3 de ancho y hasta 3 filas.
        for (c in carScreens) for (p in Template.entries.filter { c.size in it.sizes }) for (tile in p.tiles(c)) {
            when (tile.type) {
                TileTypes.CAR -> assertTrue("$p en ${c.key}: $tile", tile.width >= 3 && tile.height >= 2)
                TileTypes.MUSIC, TileTypes.RADIO -> assertTrue("$p en ${c.key}: $tile", tile.width >= 3 && tile.height <= 3)
            }
        }
    }

    @Test
    fun mapTemplatesShowTheMap() {
        val withMap = listOf(Template.MAP_CAR, Template.COPILOT, Template.ROUTE, Template.VERTICAL_MAP, Template.COLUMNS, Template.PANEL, Template.VERTICAL)
        for (c in carScreens) for (p in withMap.filter { c.size in it.sizes }) {
            assertTrue("$p en ${c.key} sin mapa", p.tiles(c).any { it.type == TileTypes.MAP })
        }
        // Las de tarjetas abajo lo ponen desde 5 filas.
        for (c in screens.filter { it.rows >= 5 && !it.vertical }) for (p in listOf(Template.CARDS, Template.CAR_SPEED)) {
            assertTrue("$p en ${c.key} sin mapa", p.tiles(c).any { it.type == TileTypes.MAP })
        }
    }

    @Test
    fun cardsKeepMinimumWidthOnNarrowGrids() {
        // 5 columnas: lo que queda junto a la música no alcanza para velocidad y hora de 2 de ancho.
        for (c in listOf(Grid(5, 3), Grid(5, 4))) {
            val tiles = Template.CARDS.tiles(c)
            for (tile in tiles) assertTrue("CARDS en ${c.key}: $tile", tile.width >= 2)
            assertTrue("CARDS en ${c.key} sin música", tiles.any { it.type == TileTypes.MUSIC })
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
        assertEquals(3 to 1, SizeLimits(3, 1).clamp(1, 0, 6, 2))
        // Sin máximo propio: solo el espacio que queda en la cuadrícula.
        assertEquals(6 to 2, SizeLimits(3, 1).clamp(9, 9, 6, 2))
        assertEquals(5 to 7, SizeLimits(3, 1).clamp(5, 7, 6, 8))
    }

    @Test
    fun nearGridsAreTheSameScreen() {
        assertTrue(Grid(10, 5).near(Grid(10, 6)))
        assertTrue(Grid(10, 5).near(Grid(9, 4)))
        assertFalse(Grid(10, 5).near(Grid(10, 7)))
        assertFalse(Grid(5, 5).near(Grid(6, 4))) // otra orientación
    }

    @Test
    fun adaptKeepsTilesThatFitAndTrimsTheRest() {
        val limits = { type: String -> if (type == TileTypes.CAR) SizeLimits(3, 2) else SizeLimits(1, 1) }
        val saved = listOf(
            Tile("car", TileTypes.CAR, 0, 0, 6, 6),
            Tile("music", TileTypes.MUSIC, 6, 0, 4, 3),
            Tile("clock", TileTypes.CLOCK, 6, 3, 4, 3),
        )
        val out = saved.adaptTo(Grid(10, 5), limits)
        // Una fila menos: todo se queda en su lugar y lo de abajo se recorta.
        assertEquals(Tile("car", TileTypes.CAR, 0, 0, 6, 5), out[0])
        assertEquals(saved[1], out[1])
        assertEquals(Tile("clock", TileTypes.CLOCK, 6, 3, 4, 2), out[2])
        // Una columna menos: la de la orilla se recorta, nada se encima.
        val narrow = saved.adaptTo(Grid(9, 6), limits)
        assertEquals(3, narrow.size)
        narrow.forEach { t -> assertTrue(narrow.fits(t, Grid(9, 6))) }
        // Una fila más: igual que antes.
        assertEquals(saved, saved.adaptTo(Grid(10, 7), limits))
    }

    @Test
    fun everyScreenHasSuggestionForItsSize() {
        assertEquals(ScreenSize.SMALL, screens[0].size)
        assertEquals(ScreenSize.MEDIUM, screens[1].size)
        assertEquals(ScreenSize.LARGE, screens[3].size)
        assertEquals(ScreenSize.VERTICAL, screens[5].size)
        for (c in screens) assertTrue(c.key, c.size in c.suggestedTemplate().sizes)
    }
}

package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    // Las pantallas de la matriz de pruebas, ya sin barra de estado ni dock, en celdas de plantilla
    // (las plantillas se calculan ahí: [Template.layout]; [Template.tiles] las escala a la fina).
    private val fineScreens = listOf(
        Grid.forSize(1024f, 410f), // 7"
        Grid.forSize(1180f, 630f), // 9"
        Grid.forSize(1280f, 530f), // 10"
        Grid.forSize(1333f, 610f), // 11"
        Grid.forSize(1920f, 530f), // ancha
        Grid.forSize(768f, 830f), // vertical
        Grid.forSize(800f, 1180f), // vertical alta
        Grid.forSize(360f, 300f), // teléfono chico
    )
    private val screens = fineScreens.map { it.coarse }

    @Test
    fun gridGrowsWithScreen() {
        assertEquals(Grid(10, 4), screens[0])
        assertTrue(screens[3].columns * screens[3].rows > screens[0].columns * screens[0].rows)
        assertTrue(screens[5].vertical)
    }

    @Test
    fun templatesNeverOverlapOrOverflow() {
        for (c in screens) for (p in Template.entries) {
            val tiles = p.layout(c)
            assertTrue("$p en ${c.key} vacía", tiles.isNotEmpty())
            for (tile in tiles) assertTrue("$p en ${c.key}: $tile", tiles.fits(tile, c))
            assertEquals("ids repetidos", tiles.size, tiles.map { it.id }.toSet().size)
        }
    }

    @Test
    fun everyTemplateHasCar() {
        // Menos las que son a propósito sin auto.
        for (c in screens) for (p in Template.entries.filter { it.hasCar }) {
            assertTrue("$p en ${c.key}", p.layout(c).any { it.type == TileTypes.CAR })
        }
    }

    @Test
    fun roadTakesTheCarPlace() {
        for (c in screens) for (p in Template.entries.filter { it.hasCar }) {
            val car = p.layout(c)
            val road = p.layout(c, hero = TileTypes.ROAD)
            assertTrue("$p en ${c.key}", road.none { it.type == TileTypes.CAR })
            // Mismo acomodo: solo cambia el tipo de la pieza grande.
            assertEquals(car.map { listOf(it.col, it.row, it.width, it.height) }, road.map { listOf(it.col, it.row, it.width, it.height) })
            assertEquals(car.count { it.type == TileTypes.CAR }, road.count { it.type == TileTypes.ROAD })
        }
    }

    @Test
    fun navigateIsGoneAndMapIsWorthy() {
        for (c in screens) for (p in Template.entries) for (hero in listOf(TileTypes.CAR, TileTypes.ROAD)) {
            val tiles = p.layout(c, hero)
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
            assertEquals("$p en ${c.key}", c.columns * c.rows, p.layout(c).sumOf { it.width * it.height })
        }
    }

    @Test
    fun widgetsKeepTheirSizesOnTheirScreens() {
        // En las pantallas de auto (no el teléfono) para las que es cada plantilla:
        // auto de 3×2 o más; música y radio de 3 de ancho y hasta 3 filas.
        for (c in carScreens) for (p in Template.entries.filter { c.size in it.sizes }) for (tile in p.layout(c)) {
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
            assertTrue("$p en ${c.key} sin mapa", p.layout(c).any { it.type == TileTypes.MAP })
        }
        // Las de tarjetas abajo lo ponen desde 5 filas.
        for (c in screens.filter { it.rows >= 5 && !it.vertical }) for (p in listOf(Template.CARDS, Template.CAR_SPEED)) {
            assertTrue("$p en ${c.key} sin mapa", p.layout(c).any { it.type == TileTypes.MAP })
        }
    }

    @Test
    fun cardsKeepMinimumWidthOnNarrowGrids() {
        // 5 columnas: lo que queda junto a la música no alcanza para velocidad y hora de 2 de ancho.
        for (c in listOf(Grid(5, 3), Grid(5, 4))) {
            val tiles = Template.CARDS.layout(c)
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
        // Se compara en celdas de plantilla (±1): la fina 21x13 es la misma pantalla que la 20x10 de antes ×2.
        assertTrue(Grid(20, 10).near(Grid(20, 12)))
        assertTrue(Grid(20, 10).near(Grid(21, 13)))
        assertTrue(Grid(20, 10).near(Grid(18, 8)))
        assertFalse(Grid(20, 10).near(Grid(20, 14)))
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

    @Test
    fun fineGridIsTwiceTheOldOne() {
        assertEquals(Grid(21, 8), fineScreens[0])
        for (c in fineScreens) {
            assertTrue(c.key, c.columns / FINE == c.coarse.columns && c.rows / FINE == c.coarse.rows)
        }
    }

    @Test
    fun templatesOnTheFineGridFillLikeBefore() {
        for (c in fineScreens) for (p in Template.entries) {
            val coarse = p.layout(c.coarse)
            val fine = p.tiles(c)
            assertEquals("$p en ${c.key}", coarse.size, fine.size)
            for (tile in fine) assertTrue("$p en ${c.key}: $tile", fine.fits(tile, c))
            // Lo que llenaba la cuadrícula de antes llena la fina (las orillas van a las orillas).
            if (coarse.sumOf { it.width * it.height } == c.coarse.columns * c.coarse.rows) {
                assertEquals("$p en ${c.key}", c.columns * c.rows, fine.sumOf { it.width * it.height })
            }
        }
    }

    @Test
    fun arrangePushesWhatIsInTheWay() {
        val c = Grid(8, 4)
        val a = Tile("a", TileTypes.MUSIC, 0, 0, 4, 2)
        val b = Tile("b", TileTypes.CLOCK, 4, 0, 4, 2)
        // "a" pasa encima de "b": "b" se hace a un lado (abajo, el hueco más cercano).
        val out = listOf(a, b).arrange(a.copy(col = 4), c)!!
        assertEquals(a.copy(col = 4), out[0])
        assertEquals(b.copy(row = 2), out[1])
        for (t in out) assertTrue(out.fits(t, c))
        // Sin lugar para la que estorba: no se puede.
        val full = listOf(a.copy(height = 4), b.copy(height = 4))
        assertNull(full.arrange(full[0].copy(col = 2), c))
        // Fuera de la cuadrícula, tampoco.
        assertNull(listOf(a).arrange(a.copy(col = 6), c))
        // Lleno, de tamaños iguales: se intercambian de lugar.
        val swapped = full.arrange(full[0].copy(col = 4), c)!!
        assertEquals(full[1].copy(col = 0), swapped[1])
        // En cadena: "b" no tiene hueco libre, pero empuja a "d" (que sí lo tiene) y se queda en su lugar.
        val g = Grid(8, 6)
        val m = Tile("m", TileTypes.MUSIC, 0, 0, 4, 2)
        val bb = Tile("b", TileTypes.CLOCK, 4, 0, 4, 2)
        val d = Tile("d", TileTypes.PHONE, 4, 2, 4, 2)
        val big = Tile("big", TileTypes.CAR, 0, 2, 4, 4)
        val chained = listOf(m, bb, d, big).arrange(m.copy(col = 4), g)!!
        for (t in chained) assertTrue("$t", chained.fits(t, g))
        assertEquals(4, chained.size)
        // Sin estorbos, nada más se mueve.
        assertEquals(listOf(a.copy(row = 2), b), listOf(a, b).arrange(a.copy(row = 2), c))
    }

    @Test
    fun oldLayoutsDoubleOnTheFineGrid() {
        val text = "car-0\tcar\t0\t0\t5\t3\t\t\nmusic-1\tmusic\t5\t3\t4\t1\tstyle=dial\t1"
        assertEquals(
            "car-0\tcar\t0\t0\t10\t6\t\t\nmusic-1\tmusic\t10\t6\t8\t2\tstyle=dial\t1",
            scaleLines(text, FINE),
        )
    }
}

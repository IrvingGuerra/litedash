package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los acomodos de los Diseños contra su especificación ejecutable (litedash-claude docs/disenos/acomodos.py): en las
 * pantallas de referencia (ancha 13×7, muy ancha 19×6, de pie 7×11) y en las que el script revisa (10×4, 12×4, 10×5,
 * 16×9, 6×9) dan lo mismo, sin huecos (menos Ruta: el mapa de fondo es el hueco) y siempre con su pieza principal.
 */
class DesignLayoutsTest {
    private val grids = listOf(Grid(13, 7), Grid(19, 6), Grid(7, 11), Grid(10, 4), Grid(12, 4), Grid(10, 5), Grid(16, 9), Grid(6, 9))

    /** Sin OBD: el viaje se queda como viaje (el script no conoce los módulos). */
    private val noObd = { type: String -> type == TileTypes.GAUGE }

    private fun describe(tiles: List<Tile>) = tiles.joinToString(" ") { "${it.type}@${it.col},${it.row},${it.width}x${it.height}${if (it.plain) "p" else ""}" }

    @Test
    fun matchTheSpecification() {
        for (t in DESIGN_TEMPLATES) for (c in grids) {
            assertEquals("$t ${c.key}", EXPECTED.getValue("${t.name} ${c.key}"), describe(t.layout(c, skip = noObd)))
        }
    }

    @Test
    fun leaveNoGapsAndKeepTheirMainPiece() {
        for (t in DESIGN_TEMPLATES) for (c in grids) {
            val tiles = t.layout(c, skip = noObd)
            for (tile in tiles) assertTrue("$t ${c.key}: $tile", tiles.fits(tile, c))
            if (t != Template.DESIGN_ROUTE) assertEquals("$t ${c.key} con hueco", c.columns * c.rows, tiles.sumOf { it.width * it.height })
            assertTrue("$t ${c.key} sin pieza principal", tiles.any { it.type in setOf(TileTypes.MAP, TileTypes.SPEEDOMETER, TileTypes.CAR) })
        }
    }

    @Test
    fun whereTheStackWasGoesItsFirstFaceThatIsOnAndNotRepeated() {
        // Grafito ancho: la música (la primera de música, auto y llantas).
        assertTrue(Template.DESIGN_GRAPHITE.layout(Grid(13, 7), skip = noObd).any { it.type == TileTypes.MUSIC && it.col == 9 && it.row == 0 })
        // Bosque sin música: la radio en su lugar.
        val forest = Template.DESIGN_FOREST.layout(Grid(13, 7), skip = { it == TileTypes.MUSIC })
        assertTrue(forest.any { it.type == TileTypes.RADIO && it.col == 7 && it.row == 5 })
        // Grafito de pie donde el mapa no cabe: el auto ya está, va la siguiente (las llantas o, apagadas, nada repetido).
        val tiny = Template.DESIGN_GRAPHITE.layout(Grid(4, 6), skip = { it == TileTypes.TIRES || it == TileTypes.GAUGE })
        assertEquals(tiny.map { it.type }.distinct(), tiny.map { it.type })
    }

    @Test
    fun noDesignRepeatsAWidget() {
        val more = grids + listOf(Grid(5, 3), Grid(4, 6), Grid(8, 3), Grid(3, 5))
        val modules = listOf(noObd, { type: String -> type == TileTypes.TIRES || type == TileTypes.GAUGE }, { _: String -> false })
        for (t in DESIGN_TEMPLATES) for (g in more) for (skip in modules) {
            val types = t.layout(g, skip = skip).map { it.type }
            // Neón de pie pone el clima donde el mapa no cabe y abajo el suyo (su especificación; no venía de la Pila).
            if (t == Template.DESIGN_NEON) continue
            assertEquals("${t.name} ${g.key}: $types", types.distinct(), types)
        }
    }

    @Test
    fun speedometersCarryTheirLookAndObdSwapsTheTrip() {
        val track = Template.DESIGN_TRACK.layout(Grid(13, 7))
        assertEquals("style=needle;color=red;numbers=1;redline=1", track.single { it.type == TileTypes.SPEEDOMETER }.data)
        // Con OBD el viaje es el medidor de revoluciones (como la deportiva).
        assertEquals("rpm", track.single { it.type == TileTypes.GAUGE }.data)
        assertEquals("rim=neon;color=cyan", Template.DESIGN_NEON.layout(Grid(19, 6)).single { it.type == TileTypes.SPEEDOMETER }.data)
        assertEquals("style=digital", Template.DESIGN_ROUTE.layout(Grid(7, 11)).single { it.type == TileTypes.SPEEDOMETER }.data)
    }

    @Test
    fun routePutsTheMapBehind() {
        assertTrue(Template.DESIGN_ROUTE.mapBackground)
        assertTrue(DESIGN_TEMPLATES.filter { it != Template.DESIGN_ROUTE }.none { it.mapBackground })
        // Sin widget Mapa encima del mapa de fondo.
        for (c in grids) assertTrue(Template.DESIGN_ROUTE.layout(c).none { it.type == TileTypes.MAP })
    }

    private companion object {
        val EXPECTED = mapOf(
        "DESIGN_GRAPHITE 13x7" to "map@0,0,9x7 music@9,0,4x4 speedometer@9,4,4x2 weather@9,6,4x1",
        "DESIGN_GRAPHITE 19x6" to "map@0,0,10x6 music@10,0,5x6 speedometer@15,0,4x3 weather@15,3,4x3",
        "DESIGN_GRAPHITE 7x11" to "map@0,0,7x6 music@0,6,7x1 car@0,7,4x4 speedometer@4,7,3x2 weather@4,9,3x2",
        "DESIGN_GRAPHITE 10x4" to "map@0,0,7x4 music@7,0,3x2 speedometer@7,2,3x1 weather@7,3,3x1",
        "DESIGN_GRAPHITE 12x4" to "map@0,0,7x4 music@7,0,3x4 speedometer@10,0,2x2 weather@10,2,2x2",
        "DESIGN_GRAPHITE 10x5" to "map@0,0,7x5 music@7,0,3x3 speedometer@7,3,3x1 weather@7,4,3x1",
        "DESIGN_GRAPHITE 16x9" to "map@0,0,11x9 music@11,0,5x5 speedometer@11,5,5x2 weather@11,7,5x2",
        "DESIGN_GRAPHITE 6x9" to "map@0,0,6x5 music@0,5,6x1 car@0,6,3x3 speedometer@3,6,3x2 weather@3,8,3x1",
        "DESIGN_CLOUD 13x7" to "car@0,0,6x5 map@6,0,7x5 music@0,5,8x2 weather@8,5,5x2",
        "DESIGN_CLOUD 19x6" to "car@0,0,6x6 map@6,0,8x6 music@14,0,5x4 weather@14,4,5x2",
        "DESIGN_CLOUD 7x11" to "car@0,0,7x4 map@0,4,7x4 music@0,8,4x3 weather@4,8,3x3",
        "DESIGN_CLOUD 10x4" to "car@0,0,5x3 map@5,0,5x3 music@0,3,6x1 weather@6,3,4x1",
        "DESIGN_CLOUD 12x4" to "car@0,0,4x4 map@4,0,5x4 music@9,0,3x3 weather@9,3,3x1",
        "DESIGN_CLOUD 10x5" to "car@0,0,5x3 map@5,0,5x3 music@0,3,6x2 weather@6,3,4x2",
        "DESIGN_CLOUD 16x9" to "car@0,0,8x7 map@8,0,8x7 music@0,7,10x2 weather@10,7,6x2",
        "DESIGN_CLOUD 6x9" to "car@0,0,6x3 map@0,3,6x3 music@0,6,3x3 weather@3,6,3x3",
        "DESIGN_TRACK 13x7" to "road@0,0,4x5 trip@0,5,4x2 speedometer@4,0,5x7p map@9,0,4x5 music@9,5,4x2",
        "DESIGN_TRACK 19x6" to "road@0,0,6x4 trip@0,4,6x2 speedometer@6,0,7x6p map@13,0,6x4 music@13,4,6x2",
        "DESIGN_TRACK 7x11" to "speedometer@0,0,7x5p road@0,5,7x3 map@0,8,4x3 music@4,8,3x2 trip@4,10,3x1",
        "DESIGN_TRACK 10x4" to "road@0,0,3x3 trip@0,3,3x1 speedometer@3,0,4x4p map@7,0,3x3 music@7,3,3x1",
        "DESIGN_TRACK 12x4" to "road@0,0,4x3 trip@0,3,4x1 speedometer@4,0,4x4p map@8,0,4x3 music@8,3,4x1",
        "DESIGN_TRACK 10x5" to "road@0,0,3x3 trip@0,3,3x2 speedometer@3,0,4x5p map@7,0,3x3 music@7,3,3x2",
        "DESIGN_TRACK 16x9" to "road@0,0,5x7 trip@0,7,5x2 speedometer@5,0,6x9p map@11,0,5x7 music@11,7,5x2",
        "DESIGN_TRACK 6x9" to "speedometer@0,0,6x4p road@0,4,6x3 map@0,7,3x2 music@3,7,3x1 trip@3,8,3x1",
        "DESIGN_ROUTE 13x7" to "car@0,0,6x6 music@0,6,5x1",
        "DESIGN_ROUTE 19x6" to "car@0,0,9x5 music@0,5,8x1",
        "DESIGN_ROUTE 7x11" to "car@0,7,4x4 weather@4,7,3x2 music@4,9,3x1 speedometer@4,10,3x1",
        "DESIGN_ROUTE 10x4" to "car@0,0,5x3 music@0,3,4x1",
        "DESIGN_ROUTE 12x4" to "car@0,0,5x3 music@0,3,5x1",
        "DESIGN_ROUTE 10x5" to "car@0,0,5x4 music@0,4,4x1",
        "DESIGN_ROUTE 16x9" to "car@0,0,7x8 music@0,8,7x1",
        "DESIGN_ROUTE 6x9" to "car@0,5,3x4 weather@3,5,3x2 music@3,7,3x1 speedometer@3,8,3x1",
        "DESIGN_COLUMN 13x7" to "music@0,0,5x2 map@0,2,5x3 weather@0,5,5x2 car@5,0,8x7",
        "DESIGN_COLUMN 19x6" to "music@0,0,6x2 map@0,2,6x4 car@6,0,9x6 speedometer@15,0,4x3 weather@15,3,4x3",
        "DESIGN_COLUMN 7x11" to "car@0,0,7x3 map@0,3,7x4 music@0,7,7x2 speedometer@0,9,3x2 weather@3,9,4x2",
        "DESIGN_COLUMN 10x4" to "music@0,0,4x2 clock@0,2,4x1 weather@0,3,4x1 car@4,0,6x4",
        "DESIGN_COLUMN 12x4" to "music@0,0,4x2 map@0,2,4x2 car@4,0,6x4 speedometer@10,0,2x2 weather@10,2,2x2",
        "DESIGN_COLUMN 10x5" to "music@0,0,4x2 map@0,2,4x2 weather@0,4,4x1 car@4,0,6x5",
        "DESIGN_COLUMN 16x9" to "music@0,0,6x2 map@0,2,6x5 weather@0,7,6x2 car@6,0,10x9",
        "DESIGN_COLUMN 6x9" to "car@0,0,6x3 map@0,3,6x4 music@0,7,6x2",
        "DESIGN_NEON 13x7" to "clock@0,0,4x2p music@0,2,4x5 speedometer@4,0,5x7p map@9,0,4x5 weather@9,5,4x2",
        "DESIGN_NEON 19x6" to "clock@0,0,6x2p music@0,2,6x4 speedometer@6,0,7x6p map@13,0,6x4 weather@13,4,6x2",
        "DESIGN_NEON 7x11" to "clock@0,0,7x2p speedometer@0,2,7x4p map@0,6,7x3 music@0,9,4x2 weather@4,9,3x2",
        "DESIGN_NEON 10x4" to "clock@0,0,3x1p music@0,1,3x3 speedometer@3,0,4x4p map@7,0,3x3 weather@7,3,3x1",
        "DESIGN_NEON 12x4" to "clock@0,0,4x1p music@0,1,4x3 speedometer@4,0,4x4p map@8,0,4x3 weather@8,3,4x1",
        "DESIGN_NEON 10x5" to "clock@0,0,3x2p music@0,2,3x3 speedometer@3,0,4x5p map@7,0,3x4 weather@7,4,3x1",
        "DESIGN_NEON 16x9" to "clock@0,0,5x2p music@0,2,5x7 speedometer@5,0,6x9p map@11,0,5x7 weather@11,7,5x2",
        "DESIGN_NEON 6x9" to "clock@0,0,6x2p speedometer@0,2,6x4p weather@0,6,6x1 music@0,7,3x2 weather@3,7,3x2",
        "DESIGN_SAND 13x7" to "clock@0,0,4x2p weather@0,2,4x2 trip@0,4,4x3 car@4,0,5x5 music@4,5,5x2 map@9,0,4x7",
        "DESIGN_SAND 19x6" to "clock@0,0,5x2p weather@0,2,5x2 trip@0,4,5x2 car@5,0,9x4 music@5,4,9x2 map@14,0,5x6",
        "DESIGN_SAND 7x11" to "clock@0,0,7x2p car@0,2,7x4 music@0,6,7x2 weather@0,8,3x3 map@3,8,4x3",
        "DESIGN_SAND 10x4" to "clock@0,0,3x1p weather@0,1,3x2 trip@0,3,3x1 car@3,0,4x3 music@3,3,4x1 map@7,0,3x4",
        "DESIGN_SAND 12x4" to "clock@0,0,3x1p weather@0,1,3x2 trip@0,3,3x1 car@3,0,6x3 music@3,3,6x1 map@9,0,3x4",
        "DESIGN_SAND 10x5" to "clock@0,0,3x2p weather@0,2,3x2 trip@0,4,3x1 car@3,0,4x3 music@3,3,4x2 map@7,0,3x5",
        "DESIGN_SAND 16x9" to "clock@0,0,4x2p weather@0,2,4x2 trip@0,4,4x5 car@4,0,8x7 music@4,7,8x2 map@12,0,4x9",
        "DESIGN_SAND 6x9" to "clock@0,0,6x2p car@0,2,6x4 music@0,6,6x2 weather@0,8,3x1 trip@3,8,3x1",
        "DESIGN_FOREST 13x7" to "map@0,0,7x7 car@7,0,6x5 music@7,5,3x2 weather@10,5,3x2",
        "DESIGN_FOREST 19x6" to "map@0,0,9x6 car@9,0,6x6 music@15,0,4x4 weather@15,4,4x2",
        "DESIGN_FOREST 7x11" to "map@0,0,7x5 car@0,5,7x3 music@0,8,4x3 weather@4,8,3x3",
        "DESIGN_FOREST 10x4" to "map@0,0,5x4 car@5,0,5x3 music@5,3,3x1 weather@8,3,2x1",
        "DESIGN_FOREST 12x4" to "map@0,0,6x4 car@6,0,4x4 music@10,0,2x3 weather@10,3,2x1",
        "DESIGN_FOREST 10x5" to "map@0,0,5x5 car@5,0,5x3 music@5,3,3x2 weather@8,3,2x2",
        "DESIGN_FOREST 16x9" to "map@0,0,8x9 car@8,0,8x7 music@8,7,4x2 weather@12,7,4x2",
        "DESIGN_FOREST 6x9" to "map@0,0,6x5 car@0,5,6x3 music@0,8,3x1 weather@3,8,3x1",
        )
    }

    @Test
    fun neverTwoCarsOnAPage() {
        // También en pantallas donde el mapa no cabe y su lugar lo toma el auto (Grafito).
        val more = grids + listOf(Grid(5, 3), Grid(4, 6), Grid(8, 3), Grid(3, 5))
        for (t in DESIGN_TEMPLATES) for (g in more) {
            val cars = t.layout(g, skip = noObd).count { it.type == TileTypes.CAR }
            assertTrue("${t.name} ${g.columns}x${g.rows}: $cars autos", cars <= 1)
        }
    }
}

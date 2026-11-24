package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que decide "¿por qué se reinició mi inicio?": leer, copiar a la cuadrícula fina y elegir el acomodo guardado. */
class LayoutStoreTest {
    private fun line(vararg f: Any) = f.joinToString("\t")

    @Test
    fun parsesALayout() {
        val text = listOf(
            line("car-1", "car", 0, 0, 8, 6, "", ""),
            line("music-1", "music", 8, 0, 6, 4, "app=x", "1"),
        ).joinToString("\n")
        val tiles = parseLayout(text)
        assertEquals(listOf(Tile("car-1", "car", 0, 0, 8, 6), Tile("music-1", "music", 8, 0, 6, 4, "app=x", plain = true)), tiles)
    }

    @Test
    fun skipsDamagedLinesAndRepeatedIds() {
        val text = listOf(
            line("car-1", "car", 0, 0, 8, 6),
            "music-1\tmusic\t8\t0", // cortada a media escritura
            line("clock-1", "clock", "x", 0, 2, 2), // número dañado
            line("map-1", "map", -1, 0, 4, 4), // fuera de la cuadrícula
            line("car-1", "clock", 10, 0, 2, 2), // id repetido: se queda la primera
            "",
        ).joinToString("\n")
        assertEquals(listOf(Tile("car-1", "car", 0, 0, 8, 6)), parseLayout(text))
    }

    @Test
    fun rewritesEveryLayoutNewestFirstAndOnlyWhatChanges() {
        val all = mapOf(
            "fine_layout_20x10" to line("p-1", "projection", 0, 0, 4, 2, "a/b;x=off", ""),
            "fine_saved_at_20x10" to 10L,
            "page_layout_1_20x10" to line("p-2", "projection", 0, 0, 4, 2, "c/d", ""),
            "page_saved_at_1_20x10" to 20L,
            "fine_layout_24x12" to line("clock-1", "clock", 0, 0, 4, 2, "", ""),
            "layout_10x5" to line("p-3", "projection", 0, 0, 2, 1, "e/f", ""), // de 96 dp: no se toca
        )
        val seen = mutableListOf<String>()
        val out = rewriteLayouts(all) { t -> if (t.type == "projection") { seen += t.data; t.data.substringAfter(';', "") } else null }
        assertEquals(listOf("c/d", "a/b;x=off"), seen)
        assertEquals(
            mapOf(
                "page_layout_1_20x10" to line("p-2", "projection", 0, 0, 4, 2, "", ""),
                "fine_layout_20x10" to line("p-1", "projection", 0, 0, 4, 2, "x=off", ""),
            ),
            out,
        )
    }

    @Test
    fun navigateIsReadAsAFactoryMap() {
        // El widget Navegar (hasta la 1.4.68): mismo lugar y tamaño, sin sus datos, conserva "sin tarjeta".
        val tiles = parseLayout(line("nav-1", "navigate", 2, 1, 6, 4, "waze", "1"))
        assertEquals(listOf(Tile("nav-1", TileTypes.MAP, 2, 1, 6, 4, "", plain = true)), tiles)
    }

    @Test
    fun oldLayoutsAreCopiedDoubledOnce() {
        val old = line("car-1", "car", 1, 0, 4, 3)
        // Sin copia: se copia ×2 con la hora del de 96 dp (0 = de antes de la 1.4.100, sin hora).
        assertEquals(mapOf("20x8" to (line("car-1", "car", 2, 0, 8, 6) to 0L)), fineCopies(mapOf("layout_10x4" to old)))
        // Con copia más nueva: nada.
        assertTrue(fineCopies(mapOf("layout_10x4" to old, "saved_at_10x4" to 5L, "fine_layout_20x8" to "x", "fine_saved_at_20x8" to 9L)).isEmpty())
        // Con copia sin hora (de la 1.4.126 recién copiada de uno sin hora): tampoco se pisa.
        assertTrue(fineCopies(mapOf("layout_10x4" to old, "fine_layout_20x8" to "x")).isEmpty())
        // Acomodado en una versión vieja después de la copia: se vuelve a copiar.
        assertEquals(setOf("20x8"), fineCopies(mapOf("layout_10x4" to old, "saved_at_10x4" to 10L, "fine_saved_at_20x8" to 9L)).keys)
        // Lo que no es un acomodo viejo no se toca.
        assertTrue(fineCopies(mapOf("layout_abc" to old, "fine_layout_20x8" to old, "hero_type" to "car", "layout_10x5" to 3L)).isEmpty())
    }

    /** Un almacén en memoria: acomodo y hora por cuadrícula. */
    private class Saved(vararg val layouts: Pair<Grid, Pair<List<Tile>, Long>>) {
        val grids = layouts.map { it.first }
        fun read(g: Grid) = layouts.firstOrNull { it.first == g }?.second?.first
        fun savedAt(g: Grid) = layouts.firstOrNull { it.first == g }?.second?.second ?: 0L
        fun pick(c: Grid) = savedLayoutFor(c, grids, ::read, ::savedAt)
    }

    private val screen = Grid(20, 10)
    private val withBar = Grid(20, 9) // la misma pantalla con una barra del sistema de más
    private val mine = listOf(Tile("car-1", "car", 0, 0, 10, 10))
    private val other = listOf(Tile("clock-1", "clock", 0, 0, 4, 9))

    @Test
    fun exactGridWinsWhenItIsNewest() {
        assertEquals(mine, Saved(screen to (mine to 20L), withBar to (other to 10L)).pick(screen))
    }

    @Test
    fun newerNearGridWinsAndIsStretched() {
        // Se acomodó con la barra puesta (9 filas) después: se usa ese, estirado a 10 filas.
        val picked = Saved(screen to (mine to 10L), withBar to (other to 20L)).pick(screen)
        assertEquals(listOf(Tile("clock-1", "clock", 0, 0, 4, 10)), picked)
    }

    @Test
    fun withoutHoursTheExactOneStays() {
        // Acomodos de antes de la 1.4.100 (hora 0): ninguno es "más nuevo".
        assertEquals(mine, Saved(screen to (mine to 0L), withBar to (other to 0L)).pick(screen))
    }

    @Test
    fun nearGridFillsInWhenThereIsNoExactOne() {
        assertEquals(listOf(Tile("clock-1", "clock", 0, 0, 4, 10)), Saved(withBar to (other to 0L)).pick(screen))
        // Una cuadrícula lejana (otra pantalla, acostada vs. parada) no cuenta: plantilla.
        assertNull(Saved(Grid(10, 20) to (other to 50L)).pick(screen))
        assertNull(Saved().pick(screen))
    }

    private val limits: (String) -> SizeLimits = { SizeLimits(2, 2) }

    @Test
    fun dragMovesToTheNearestCell() {
        val t = Tile("a", "clock", 2, 2, 4, 4)
        assertEquals(t.copy(col = 3, row = 1), dragTarget(t, false, Offset(60f, -40f), 50f, 50f, limits, screen))
        assertEquals(t, dragTarget(t, false, Offset(20f, 20f), 50f, 50f, limits, screen))
    }

    @Test
    fun resizeKeepsMinimumAndEdge() {
        val t = Tile("a", "clock", 16, 6, 3, 3)
        // Más grande que lo que queda: hasta la orilla (4 columnas, 4 filas).
        assertEquals(t.copy(width = 4, height = 4), dragTarget(t, true, Offset(500f, 500f), 50f, 50f, limits, screen))
        // Más chico que su mínimo: el mínimo.
        assertEquals(t.copy(width = 2, height = 2), dragTarget(t, true, Offset(-500f, -500f), 50f, 50f, limits, screen))
    }
}

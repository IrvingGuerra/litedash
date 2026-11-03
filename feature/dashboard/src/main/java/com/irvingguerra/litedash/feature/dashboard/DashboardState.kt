package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** El acomodo actual y sus cambios; cada cambio se guarda para esta cuadrícula. */
class DashboardState(private val store: DashboardStore) {
    var grid by mutableStateOf<Grid?>(null)
        private set
    var tiles by mutableStateOf(emptyList<Tile>())
        private set

    /** La pantalla (o la escala) cambió: carga el acomodo de ese tamaño o una plantilla. */
    fun use(c: Grid) {
        if (c != grid) {
            grid = c
            tiles = store.read(c) ?: suggested(c)
        }
    }

    fun change(newTiles: List<Tile>) {
        tiles = newTiles
        grid?.let { store.save(it, newTiles) }
    }

    /**
     * Pone la plantilla [p] con el auto 3D o la carretera ([hero]) como pieza grande. Esa pieza
     * también va en las plantillas de fábrica de otras cuadrículas (otra escala u otra pantalla).
     */
    fun template(p: Template, hero: String = TileTypes.CAR) {
        store.saveHero(hero)
        grid?.let { change(p.tiles(it, hero)) }
    }

    /** La plantilla recomendada para [c], con la pieza grande que se eligió la última vez. */
    private fun suggested(c: Grid): List<Tile> {
        val (hero, data) = store.hero()
        return c.suggestedTemplate().tiles(c, hero).map { if (it.type == hero && data.isNotEmpty()) it.copy(data = data) else it }
    }

    /**
     * Agrega una pieza del [size] pedido en el primer hueco; si no cabe, prueba
     * con su tamaño mínimo. Devuelve false si ya no hay lugar.
     */
    fun add(type: String, size: Pair<Int, Int>, limits: SizeLimits, data: String = ""): Boolean {
        val c = grid ?: return false
        for ((width, height) in listOf(size, limits.minWidth to limits.minHeight)) {
            val spot = tiles.freeSpot(width, height, c) ?: continue
            val id = "$type-${System.currentTimeMillis().toString(36)}"
            change(tiles + Tile(id, type, spot.first, spot.second, width, height, data))
            return true
        }
        return false
    }

    fun setData(id: String, data: String) {
        tiles.firstOrNull { it.id == id }?.let { change(tiles.replace(it.copy(data = data))) }
    }

    /** Cambia los datos de varias piezas ([updates]: id → datos) con una sola escritura. */
    fun changeData(updates: Map<String, String>) {
        if (updates.isEmpty()) return
        change(tiles.map { t -> updates[t.id]?.let { t.copy(data = it) } ?: t })
    }
}

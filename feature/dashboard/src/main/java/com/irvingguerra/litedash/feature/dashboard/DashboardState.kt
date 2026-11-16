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

    /**
     * Los tipos que no se ven (de un módulo apagado; lo pone la app). Sus piezas se guardan pero
     * no apartan su lugar: se pueden acomodar otras encima, y entonces se van. Si nada las tapa,
     * vuelven al prender el módulo. Las plantillas no los ponen.
     */
    var hidden: (String) -> Boolean = { false }

    /** El tamaño mínimo de cada tipo (lo pone la app): para llevar un acomodo a otra cuadrícula. */
    var limits: (String) -> SizeLimits = { SizeLimits(1, 1) }

    /** Con qué datos arranca una pieza nueva de cada tipo si no se le dan (lo pone la app; p. ej. el aspecto del mapa). */
    var initialData: (String) -> String = { "" }

    /** Las piezas que se ven y se acomodan. */
    val shown: List<Tile> get() = tiles.filterNot { hidden(it.type) }

    /** La pantalla (o la escala) cambió: carga el acomodo de ese tamaño o una plantilla. */
    fun use(c: Grid) {
        if (c != grid) {
            grid = c
            tiles = load(c)
        }
    }

    /**
     * El acomodo de [c]. Si una cuadrícula casi igual ([near]: una barra del sistema que aparece o se va)
     * tiene uno guardado más nuevo, ese, ajustado a [c]; si [c] no tiene ninguno, la plantilla. Así el
     * acomodo no "se reinicia" cuando el alto de la pantalla cambia un poco. El de [c] también se revisa
     * (uno dañado o de una versión con otros mínimos). Lo ajustado no se guarda hasta que se mueva algo.
     */
    private fun load(c: Grid): List<Tile> =
        savedLayoutFor(c, store.grids(), store::read, store::savedAt)?.fittedTo(c) ?: suggested(c)

    /** [adaptTo] con las que se ven primero: las de un módulo apagado no le quitan lugar a ninguna. */
    private fun List<Tile>.fittedTo(c: Grid): List<Tile> {
        val (off, on) = partition { hidden(it.type) }
        return (on + off).adaptTo(c, limits)
    }

    /** Para los reportes: la cuadrícula actual y las que tienen acomodo guardado. */
    fun describe(): String =
        "Cuadrícula: ${grid?.key ?: "-"} · guardadas: ${store.grids().sortedBy { it.key }.joinToString { "${it.key}${if (store.savedAt(it) == 0L) "" else "*"}" }.ifEmpty { "ninguna" }}"

    fun change(newTiles: List<Tile>) {
        tiles = newTiles
        grid?.let { store.save(it, newTiles) }
    }

    /** Cambia las que se ven ([shown]); las escondidas se quedan si nada quedó encima de ellas. */
    fun changeShown(newShown: List<Tile>) {
        val kept = tiles.filter { h -> hidden(h.type) && newShown.none { it.overlaps(h) } }
        change(newShown + kept)
    }

    /**
     * Pone la plantilla [p] con el auto 3D o la carretera ([hero]) como pieza grande. Esa pieza
     * también va en las plantillas de fábrica de otras cuadrículas (otra escala u otra pantalla).
     */
    fun template(p: Template, hero: String = TileTypes.CAR) {
        store.saveHero(hero)
        grid?.let { change(p.tiles(it, hero, hidden)) }
    }

    /** La plantilla recomendada para [c], con la pieza grande que se eligió la última vez. */
    private fun suggested(c: Grid): List<Tile> {
        val (hero, data) = store.hero()
        return c.coarse.suggestedTemplate().tiles(c, hero, hidden).map { if (it.type == hero && data.isNotEmpty()) it.copy(data = data) else it }
    }

    /**
     * Agrega una pieza del [size] pedido en el primer hueco; si no cabe, prueba
     * con su tamaño mínimo. Devuelve false si ya no hay lugar.
     */
    fun add(type: String, size: Pair<Int, Int>, limits: SizeLimits, data: String = ""): Boolean {
        val c = grid ?: return false
        for ((width, height) in listOf(size, limits.minWidth to limits.minHeight)) {
            val spot = shown.freeSpot(width, height, c) ?: continue
            val id = "$type-${System.currentTimeMillis().toString(36)}"
            changeShown(shown + Tile(id, type, spot.first, spot.second, width, height, data.ifEmpty { initialData(type) }))
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

/**
 * El acomodo guardado que toca a [c] (aún sin ajustar con [adaptTo]), de las cuadrículas [grids] con acomodo, que se
 * leen con [read] y cuya hora da [savedAt] (0 = de antes de la 1.4.100): el de una cercana ([near]) si es más nuevo
 * que el de [c] (o [c] no tiene), estirado hacia donde [c] tiene más lugar; si no, el de [c]; null = ninguno.
 */
internal fun savedLayoutFor(c: Grid, grids: List<Grid>, read: (Grid) -> List<Tile>?, savedAt: (Grid) -> Long): List<Tile>? {
    val exact = read(c)
    val newer = grids.filter { it != c && it.near(c) }.maxByOrNull(savedAt)
        ?.takeIf { exact == null || savedAt(it) > savedAt(c) }
    val adapted = newer?.let { from ->
        read(from)?.let { saved ->
            // Hacia donde hay más lugar se estira en proporción (sin hueco en la orilla); hacia donde hay
            // menos, no se encoge (bajaría piezas de su mínimo): ahí [adaptTo] recorta o recorre como siempre.
            val grown = Grid(maxOf(from.columns, c.columns), maxOf(from.rows, c.rows))
            saved.map { it.scaledTo(from, grown) }
        }
    }
    return adapted ?: exact
}

package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * El acomodo actual y sus cambios; cada cambio se guarda para esta cuadrícula y esta página. Con varias páginas, solo
 * la que se ve está en [tiles]; la primera es el inicio de siempre (sus acomodos son los de antes de las páginas).
 */
class DashboardState(private val store: DashboardStore) {
    var grid by mutableStateOf<Grid?>(null)
        private set
    var tiles by mutableStateOf(emptyList<Tile>())
        private set

    /** La página que se ve (0 = la primera) y cuántas hay. */
    var page by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(store.pages)
        private set

    /** El fondo de cada página. */
    var backgrounds by mutableStateOf(List(pageCount) { store.background(it) })
        private set

    /** El fondo de la página que se ve. */
    val background: PageBackground get() = backgrounds.getOrElse(page) { PageBackground.WALLPAPER }

    /** Lo de las otras páginas en esta cuadrícula, como quedó la última vez que se vio (volver a ellas no lee el disco). */
    private val pageTiles = HashMap<Int, List<Tile>>()

    /**
     * Los tipos que no se ven (de un módulo apagado; lo pone la app). Sus piezas se guardan pero
     * no apartan su lugar: se pueden acomodar otras encima, y entonces se van. Si nada las tapa,
     * vuelven al prender el módulo. Las plantillas no los ponen.
     */
    var hidden: (String) -> Boolean = { false }

    /** El tamaño mínimo de cada tipo (lo pone la app): para llevar un acomodo a otra cuadrícula. */
    var limits: (String) -> SizeLimits = { SizeLimits(1, 1) }

    /** Las piezas que se ven y se acomodan. */
    val shown: List<Tile> get() = tiles.filterNot { hidden(it.type) }

    /** La pieza con ese id (de esta página), o null. */
    fun tile(id: String): Tile? = tiles.firstOrNull { it.id == id }

    /** La pantalla (o la escala) cambió: carga el acomodo de ese tamaño o una plantilla. */
    fun use(c: Grid) {
        if (c != grid) {
            grid = c
            pageTiles.clear()
            tiles = load(c, page)
        }
    }

    /**
     * Las piezas de todas las páginas en esta cuadrícula (las otras, como quedaron o leídas una vez): para lo que vale para
     * todo el inicio, como no soltar la foto o la cámara de un widget que solo está en otra página.
     */
    val allTiles: List<Tile>
        get() {
            val c = grid ?: return tiles
            return (0 until pageCount).flatMap { p -> if (p == page) tiles else pageTiles.getOrPut(p) { load(c, p) } }
        }

    /** Cambia los datos de las piezas de todas las páginas: [update] da los nuevos de una (null = igual). */
    fun changeDataEverywhere(update: (Tile) -> String?) {
        val c = grid ?: return
        changeData(tiles.mapNotNull { t -> update(t)?.let { t.id to it } }.toMap())
        for (p in 0 until pageCount) {
            if (p == page) continue
            val before = pageTiles.getOrPut(p) { load(c, p) }
            val after = before.mapData(update)
            if (after != before) {
                pageTiles[p] = after
                store.save(c, after, p)
            }
        }
    }

    /**
     * La página que se ve si cumple [test] (con su fondo y lo que está a la vista) o, si no, la primera que sí; null =
     * ninguna.
     */
    fun pageWhere(test: (PageBackground, List<Tile>) -> Boolean): Int? {
        if (test(background, shown)) return page
        val c = grid ?: return null
        return (0 until pageCount).firstOrNull { p ->
            p != page && test(backgrounds.getOrElse(p) { PageBackground.WALLPAPER }, pageTiles.getOrPut(p) { load(c, p) }.filterNot { hidden(it.type) })
        }
    }

    /** Pasa a la página [p] (se queda donde está si no existe). */
    fun goTo(p: Int) {
        if (p == page || p !in 0 until pageCount) return
        pageTiles[page] = tiles
        page = p
        tiles = grid?.let { pageTiles[p] ?: load(it, p) }.orEmpty()
    }

    /** Agrega una página vacía al final y pasa a ella; false si ya hay [MAX_PAGES]. */
    fun addPage(): Boolean {
        if (pageCount >= MAX_PAGES) return false
        store.pages = pageCount + 1
        pageCount = store.pages
        backgrounds = List(pageCount) { store.background(it) }
        goTo(pageCount - 1)
        return true
    }

    /** Quita la página [p] (no la primera) y queda en la de antes. */
    fun removePage(p: Int) {
        if (p !in 1 until pageCount) return
        store.removePage(p)
        pageCount = store.pages
        backgrounds = List(pageCount) { store.background(it) }
        pageTiles.clear()
        page = if (page >= p) page - 1 else page
        tiles = grid?.let { load(it, page) }.orEmpty()
    }

    fun setBackground(p: Int, b: PageBackground) {
        store.setBackground(p, b)
        backgrounds = List(pageCount) { store.background(it) }
    }

    /**
     * El acomodo de [c]. Si una cuadrícula casi igual ([near]: una barra del sistema que aparece o se va)
     * tiene uno guardado más nuevo, ese, ajustado a [c]; si [c] no tiene ninguno, la plantilla. Así el
     * acomodo no "se reinicia" cuando el alto de la pantalla cambia un poco. El de [c] también se revisa
     * (uno dañado o de una versión con otros mínimos). Lo ajustado no se guarda hasta que se mueva algo.
     */
    private fun load(c: Grid, p: Int): List<Tile> =
        savedLayoutFor(c, store.grids(p), { store.read(it, p) }, { store.savedAt(it, p) })?.fittedTo(c)
            // Sin acomodo guardado, la plantilla que se le puso; si no, una página nueva empieza vacía y solo la primera
            // trae la recomendada.
            ?: store.template(p)?.let { fromTemplate(c, it) }
            ?: if (p == 0) suggested(c) else emptyList()

    /** [adaptTo] con las que se ven primero: las de un módulo apagado no le quitan lugar a ninguna. */
    private fun List<Tile>.fittedTo(c: Grid): List<Tile> {
        val (off, on) = partition { hidden(it.type) }
        return (on + off).adaptTo(c, limits)
    }

    /** Para los reportes: la cuadrícula actual y las que tienen acomodo guardado. */
    fun describe(): String =
        "Cuadrícula: ${grid?.key ?: "-"} · guardadas: ${store.grids().sortedBy { it.key }.joinToString { "${it.key}${if (store.savedAt(it) == 0L) "" else "*"}" }.ifEmpty { "ninguna" }}" +
            if (pageCount > 1) " · páginas: $pageCount (en la ${page + 1}; fondos ${backgrounds.joinToString("/") { it.stored }})" else ""

    fun change(newTiles: List<Tile>) {
        tiles = newTiles
        grid?.let { store.save(it, newTiles, page) }
    }

    /** Cambia las que se ven ([shown]); las escondidas se quedan si nada quedó encima de ellas. */
    fun changeShown(newShown: List<Tile>) {
        val kept = tiles.filter { h -> hidden(h.type) && newShown.none { it.overlaps(h) } }
        change(newShown + kept)
    }

    /** Pone la plantilla [p] (la de un Diseño) y el fondo de la página que le toca (mapa solo con la del mapa de fondo). */
    fun template(p: Template) {
        grid?.let { change(fromTemplate(it, p)) }
        // Las otras cuadrículas de esta página sin acomodo guardado se arman con ella.
        store.setTemplate(page, p)
        // La plantilla decide el fondo de la página: las de mapa de fondo ponen el mapa y cualquier otra lo quita (si no,
        // el mapa de fondo quedaba debajo de widgets que lo tapan, o junto a un widget Mapa).
        val wanted = if (p.mapBackground) PageBackground.MAP else PageBackground.WALLPAPER
        if (background != wanted) setBackground(page, wanted)
    }

    /** La plantilla que se puso en la página que se ve (null = ninguna: la de fábrica). */
    val pageTemplate: Template? get() = store.template(page)

    /** La página que se ve tal como está ([restore] la regresa así): su acomodo en esta cuadrícula, su fondo y su plantilla. */
    fun snapshot(): PageSnapshot? = grid?.let { PageSnapshot(page, it, formatLayout(tiles), background, store.template(page)) }

    /** Regresa la página de [s] como estaba (su acomodo en esa cuadrícula, su fondo y su plantilla). */
    fun restore(s: PageSnapshot) {
        if (s.page !in 0 until pageCount) return
        val restored = parseLayout(s.layout)
        store.save(s.grid, restored, s.page)
        store.setTemplate(s.page, s.template)
        setBackground(s.page, s.background)
        pageTiles.remove(s.page)
        val c = grid
        if (s.page == page && c != null) tiles = if (c == s.grid) restored else load(c, page)
    }

    /**
     * ¿La página [page] sigue con [layout] en [c] (nadie la movió desde entonces)? Solo cuenta dónde está cada widget (id,
     * tipo, lugar y tamaño): cambiar una opción de un widget no. Se calcula una vez por texto.
     */
    fun unchanged(page: Int, c: Grid, layout: String): Boolean {
        val now = store.layoutText(c, page) ?: return false
        if (now == layout) return true
        lastCompare?.let { (a, b, same) -> if (a == now && b == layout) return same }
        return samePlaces(parseLayout(now), parseLayout(layout)).also { lastCompare = Triple(now, layout, it) }
    }

    private var lastCompare: Triple<String, String, Boolean>? = null

    /** La plantilla [t] en [c], con la pieza grande que se eligió la última vez (los Diseños siempre con el auto). */
    private fun fromTemplate(c: Grid, t: Template): List<Tile> {
        val (hero, data) = if (t.isDesign) TileTypes.CAR to "" else store.hero()
        return t.tiles(c, hero, hidden).map { if (it.type == hero && data.isNotEmpty()) it.copy(data = data) else it }
    }

    /** La plantilla recomendada para [c], con la pieza grande que se eligió la última vez. */
    private fun suggested(c: Grid): List<Tile> = fromTemplate(c, c.coarse.suggestedTemplate())

    /**
     * Agrega una pieza del [size] pedido en el primer hueco; si no cabe, prueba
     * con su tamaño mínimo. Devuelve false si ya no hay lugar.
     */
    fun add(type: String, size: Pair<Int, Int>, limits: SizeLimits, data: String = ""): Boolean {
        val c = grid ?: return false
        for ((width, height) in listOf(size, limits.minWidth to limits.minHeight)) {
            val spot = shown.freeSpot(width, height, c) ?: continue
            val id = "$type-${System.currentTimeMillis().toString(36)}"
            changeShown(shown + Tile(id, type, spot.first, spot.second, width, height, data))
            return true
        }
        return false
    }

    /** Cambia los datos de una pieza. */
    fun setData(id: String, data: String) = changeData(mapOf(id to data))

    /** Cambia los datos de varias piezas ([updates]: id → datos) con una sola escritura (ninguna si nada cambia). */
    fun changeData(updates: Map<String, String>) {
        if (updates.isEmpty()) return
        val after = tiles.map { t -> updates[t.id]?.let { t.copy(data = it) } ?: t }
        if (after != tiles) change(after)
    }
}

/** ¿Los mismos widgets en los mismos lugares y tamaños (sin mirar sus datos ni si van sin tarjeta)? */
internal fun samePlaces(a: List<Tile>, b: List<Tile>): Boolean =
    a.size == b.size && a.zip(b).all { (x, y) -> x.id == y.id && x.type == y.type && x.col == y.col && x.row == y.row && x.width == y.width && x.height == y.height }

/**
 * Una página como estaba ([DashboardState.snapshot]): su número, la cuadrícula, su acomodo ahí (el texto que guarda
 * [DashboardStore]), su fondo y su plantilla.
 */
data class PageSnapshot(val page: Int, val grid: Grid, val layout: String, val background: PageBackground, val template: Template?) {
    companion object {
        /** La cuadrícula de su clave ("20x10"); null si no lo es. */
        fun grid(key: String): Grid? = parseGrid(key)
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

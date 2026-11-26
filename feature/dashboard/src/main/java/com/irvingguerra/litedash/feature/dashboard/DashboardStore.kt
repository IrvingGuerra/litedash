package com.irvingguerra.litedash.feature.dashboard

import android.content.Context

/**
 * Guarda un acomodo por tamaño de cuadrícula: la misma app en otra pantalla (u
 * otra escala) no rompe el acomodo de la primera. Formato de texto simple, sin
 * librerías: una pieza por línea, campos separados por tabulador.
 */
class DashboardStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    init {
        toFineGrid()
    }

    /**
     * Plan Widgets: cada acomodo de la cuadrícula de 96 dp se copia a la fina con todo ×[FINE] (misma pieza,
     * mismo lugar) si no tiene copia o si se guardó después de ella (alguien regresó a una versión vieja,
     * acomodó ahí y volvió). Los de antes NO se borran (regla de no borrar datos). La pantalla de ahora toma
     * el más nuevo de su cuadrícula o una cercana ([Grid.near]) y lo ajusta.
     * Quitar cuando < 5 % de las pantallas esté antes de la 1.4.140 según docs/usuarios.py.
     */
    private fun toFineGrid() {
        val copies = fineCopies(prefs.all)
        if (copies.isEmpty()) return
        val edit = prefs.edit()
        for ((key, copy) in copies) edit.putString(LAYOUT_KEY_PREFIX + key, copy.first).putLong(SAVED_AT_PREFIX + key, copy.second)
        edit.apply()
    }

    fun read(c: Grid, page: Int = 0): List<Tile>? = prefs.getString(layoutKey(page, c), null)?.let(::parseLayout)

    fun save(c: Grid, tiles: List<Tile>, page: Int = 0) {
        prefs.edit().putString(layoutKey(page, c), formatLayout(tiles)).putLong(savedAtKey(page, c), System.currentTimeMillis()).apply()
    }

    /**
     * Para migraciones de una vez al arrancar (antes de cargar el inicio): cambia los datos de las piezas de todos los
     * acomodos guardados (toda cuadrícula y página), del guardado más nuevo al más viejo. [update] da los nuevos datos de
     * una pieza (null = igual). No mueve la hora de guardado (no cambia qué acomodo se elige).
     */
    fun changeDataEverywhere(update: (Tile) -> String?) {
        val changed = rewriteLayouts(prefs.all, update)
        if (changed.isEmpty()) return
        val edit = prefs.edit()
        for ((key, text) in changed) edit.putString(key, text)
        edit.apply()
    }

    /** Cuándo se guardó el acomodo de [c] (0 si nunca o si es de antes de la 1.4.100). */
    fun savedAt(c: Grid, page: Int = 0): Long = prefs.getLong(savedAtKey(page, c), 0L)

    /** Las cuadrículas que tienen acomodo guardado en [page]. */
    fun grids(page: Int = 0): List<Grid> {
        val prefix = layoutPrefix(page)
        return prefs.all.keys.mapNotNull { key -> key.takeIf { it.startsWith(prefix) }?.let { parseGrid(it.removePrefix(prefix)) } }
    }

    /** Cuántas páginas tiene el inicio (1 = como siempre). */
    var pages: Int
        get() = prefs.getInt(PAGES, 1).coerceIn(1, MAX_PAGES)
        set(v) = prefs.edit().putInt(PAGES, v.coerceIn(1, MAX_PAGES)).apply()

    /** El fondo de [page]: el de siempre (tu imagen o el del tema) o el mapa. */
    fun background(page: Int): PageBackground = PageBackground.fromStored(prefs.getString(BACKGROUND_PREFIX + page, null))

    fun setBackground(page: Int, b: PageBackground) {
        prefs.edit().putString(BACKGROUND_PREFIX + page, b.stored).apply()
    }

    /**
     * La plantilla (o el acomodo de un Diseño) que se puso en [page]: con ella se arma esa página en las cuadrículas que
     * aún no tienen acomodo guardado (otra escala u orientación). null = ninguna (la página de siempre).
     */
    fun template(page: Int): Template? = prefs.getString(TEMPLATE_PREFIX + page, null)?.let { name -> Template.entries.firstOrNull { it.name == name } }

    fun setTemplate(page: Int, t: Template?) {
        prefs.edit().apply { if (t == null) remove(TEMPLATE_PREFIX + page) else putString(TEMPLATE_PREFIX + page, t.name) }.apply()
    }

    /** El texto del acomodo guardado de [c] en [page] (para comparar si alguien lo movió); null = ninguno. */
    fun layoutText(c: Grid, page: Int): String? = prefs.getString(layoutKey(page, c), null)

    /**
     * Quita la página [page] (lo pidió el usuario, con confirmación): las de después se recorren una hacia atrás con
     * sus acomodos y su fondo. Lo de la última, que ya quedó copiado, se suelta. La primera no se quita: es tu inicio
     * de siempre (y sus llaves son las de antes de las páginas).
     */
    fun removePage(page: Int) {
        val count = pages
        if (page !in 1 until count) return
        val edit = prefs.edit()
        for ((key, value) in withoutPage(prefs.all, page, count)) when (value) {
            null -> edit.remove(key)
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
        }
        edit.putInt(PAGES, count - 1).apply()
    }

    /**
     * La pieza grande de las plantillas (tipo y datos): el auto 3D o la carretera que se eligió en los Acomodos (hasta la
     * 1.5.22); con ella se siguen armando las páginas que tienen puesta una de esas plantillas.
     */
    fun hero(): Pair<String, String> =
        (prefs.getString(HERO_TYPE, null) ?: TileTypes.CAR) to (prefs.getString(HERO_DATA, null) ?: "")
}

/** Las llaves de cada página: la 0 usa las de siempre (los acomodos de antes de las páginas son la página 1). */
private fun layoutPrefix(page: Int) = if (page == 0) LAYOUT_KEY_PREFIX else "$PAGE_LAYOUT_PREFIX${page}_"
private fun savedAtPrefix(page: Int) = if (page == 0) SAVED_AT_PREFIX else "$PAGE_SAVED_AT_PREFIX${page}_"
private fun layoutKey(page: Int, c: Grid) = layoutPrefix(page) + c.key
private fun savedAtKey(page: Int, c: Grid) = savedAtPrefix(page) + c.key

/** "10x5" → la cuadrícula; null si no lo es. */
internal fun parseGrid(size: String): Grid? {
    val parts = size.split('x').takeIf { it.size == 2 } ?: return null
    return Grid(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
}

/**
 * Lo que [DashboardStore.toFineGrid] escribe, con todo lo guardado ([all]): por cada acomodo de 96 dp sin copia en
 * la fina (o guardado después de ella), la llave de la fina ("20x10") → (su texto ×[FINE], hora del de 96 dp).
 */
/**
 * Lo que cambia en [all] (las llaves guardadas) al quitar la página [page] de [count]: cada llave con su valor nuevo, o
 * null si se suelta. Lo de cada página de después pasa a la de antes (acomodos, horas y fondo); lo de la última se suelta.
 */
internal fun withoutPage(all: Map<String, *>, page: Int, count: Int): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (p in page until count) {
        for (key in all.keys) if (key.startsWith(layoutPrefix(p)) || key.startsWith(savedAtPrefix(p))) out[key] = null
        out[BACKGROUND_PREFIX + p] = null
        out[TEMPLATE_PREFIX + p] = null
    }
    for (p in page until count - 1) {
        for ((key, value) in all) {
            val moved = when {
                key.startsWith(layoutPrefix(p + 1)) -> layoutPrefix(p) + key.removePrefix(layoutPrefix(p + 1))
                key.startsWith(savedAtPrefix(p + 1)) -> savedAtPrefix(p) + key.removePrefix(savedAtPrefix(p + 1))
                else -> null
            } ?: continue
            if (value is String || value is Long) out[moved] = value
        }
        (all[BACKGROUND_PREFIX + (p + 1)] as? String)?.let { out[BACKGROUND_PREFIX + p] = it }
        (all[TEMPLATE_PREFIX + (p + 1)] as? String)?.let { out[TEMPLATE_PREFIX + p] = it }
    }
    return out
}

internal fun fineCopies(all: Map<String, *>): Map<String, Pair<String, Long>> {
    val out = HashMap<String, Pair<String, Long>>()
    for ((key, value) in all) {
        if (!key.startsWith(OLD_LAYOUT_PREFIX) || value !is String) continue
        val old = parseGrid(key.removePrefix(OLD_LAYOUT_PREFIX)) ?: continue
        val fine = Grid(old.columns * FINE, old.rows * FINE)
        val oldAt = all[OLD_SAVED_AT_PREFIX + old.key] as? Long ?: 0L
        val fineAt = all[SAVED_AT_PREFIX + fine.key] as? Long
        if (fineAt != null && oldAt <= fineAt || fineAt == null && all.containsKey(LAYOUT_KEY_PREFIX + fine.key)) continue
        out[fine.key] = scaleLines(value, FINE) to oldAt
    }
    return out
}

/**
 * Lo que [DashboardStore.changeDataEverywhere] escribe, con todo lo guardado ([all]): llave del acomodo → su texto nuevo,
 * solo los que cambian. Recorre los de la cuadrícula fina de todas las páginas, del guardado más nuevo al más viejo.
 */
internal fun rewriteLayouts(all: Map<String, *>, update: (Tile) -> String?): Map<String, String> {
    val layouts = all.entries.mapNotNull { (key, value) ->
        val savedAt = when {
            key.startsWith(LAYOUT_KEY_PREFIX) -> SAVED_AT_PREFIX + key.removePrefix(LAYOUT_KEY_PREFIX)
            key.startsWith(PAGE_LAYOUT_PREFIX) -> PAGE_SAVED_AT_PREFIX + key.removePrefix(PAGE_LAYOUT_PREFIX)
            else -> return@mapNotNull null
        }
        (value as? String)?.let { Triple(key, it, all[savedAt] as? Long ?: 0L) }
    }.sortedByDescending { it.third }
    val out = LinkedHashMap<String, String>()
    for ((key, text, _) in layouts) {
        val before = parseLayout(text)
        val after = before.mapData(update)
        if (after != before) out[key] = formatLayout(after)
    }
    return out
}

/** Las piezas con los datos que da [update] (null = igual). */
internal fun List<Tile>.mapData(update: (Tile) -> String?): List<Tile> = map { t -> update(t)?.let { t.copy(data = it) } ?: t }

/** El texto de un acomodo: una pieza por línea, campos separados por tabulador. */
internal fun formatLayout(tiles: List<Tile>): String = tiles.joinToString("\n") {
    // El campo nuevo va al final: las versiones de antes lo ignoran.
    listOf(it.id, it.type, it.col, it.row, it.width, it.height, it.data.replace('\t', ' ').replace('\n', ' '), if (it.plain) "1" else "")
        .joinToString("\t")
}

/**
 * Un acomodo guardado: una pieza por línea, campos separados por tabulador. Una línea dañada (escritura
 * cortada) se salta y un id repetido (acomodo dañado o editado a mano) se queda con la primera: no tumban el inicio.
 */
internal fun parseLayout(text: String): List<Tile> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
    val f = line.split('\t')
    if (f.size < 6) return@mapNotNull null
    val (col, row, width, height) = (2..5).map { f[it].toIntOrNull() ?: return@mapNotNull null }
    val plain = f.getOrElse(7) { "" } == "1"
    // El widget Navegar (hasta la 1.4.68) es ahora el Mapa, en el mismo lugar y tamaño (sus
    // datos eran otra cosa: el mapa queda como de fábrica). Quitar cuando < 5 % de las pantallas esté antes de la 1.4.140 según docs/usuarios.py.
    if (f[1] == LEGACY_NAVIGATE) Tile(f[0], TileTypes.MAP, col, row, width, height, "", plain)
    else migrateStack1520(Tile(f[0], f[1], col, row, width, height, f.getOrElse(6) { "" }, plain))
}.filter { p -> p.col >= 0 && p.row >= 0 }.distinctBy { it.id }.toList()

/** El texto de un acomodo con columna, fila, ancho y alto (campos 2 a 5) multiplicados por [k]. */
internal fun scaleLines(text: String, k: Int) = text.lineSequence().joinToString("\n") { line ->
    val f = line.split('\t').toMutableList()
    if (f.size >= 6) for (i in 2..5) f[i].toIntOrNull()?.let { f[i] = (it * k).toString() }
    f.joinToString("\t")
}

// Nombres guardados en el dispositivo (en inglés desde la 1.4.47).
private const val PREFS_FILE = "dashboard"
/** Los acomodos en la cuadrícula fina (de 48 dp); los de 96 dp siguen con [OLD_LAYOUT_PREFIX]. */
private const val LAYOUT_KEY_PREFIX = "fine_layout_"
/** Hora en que se guardó cada acomodo (no empieza con el prefijo de ellos para no confundirse). */
private const val SAVED_AT_PREFIX = "fine_saved_at_"
private const val OLD_LAYOUT_PREFIX = "layout_"
private const val OLD_SAVED_AT_PREFIX = "saved_at_"

/** El tipo que tenía el widget Navegar en los acomodos guardados. */
private const val LEGACY_NAVIGATE = "navigate"
private const val HERO_TYPE = "hero_type"
private const val HERO_DATA = "hero_data"
private const val PAGES = "pages"
/** Las páginas de después de la primera: no empiezan con [LAYOUT_KEY_PREFIX] (no se confunden con la primera). */
private const val PAGE_LAYOUT_PREFIX = "page_layout_"
private const val PAGE_SAVED_AT_PREFIX = "page_saved_at_"
private const val BACKGROUND_PREFIX = "page_background_"
/** La plantilla puesta en cada página ([Template.name]). */
private const val TEMPLATE_PREFIX = "page_template_"

/** Hasta cuántas páginas puede tener el inicio. */
const val MAX_PAGES = 5

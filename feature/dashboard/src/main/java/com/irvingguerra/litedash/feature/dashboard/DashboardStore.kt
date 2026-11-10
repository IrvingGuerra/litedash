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
     * el más nuevo de su cuadrícula o una cercana ([Grid.near]) y lo ajusta. Quitar esta copia en 2027.
     */
    private fun toFineGrid() {
        val all = prefs.all
        val edit = prefs.edit()
        var copied = false
        for ((key, value) in all) {
            if (!key.startsWith(OLD_LAYOUT_PREFIX) || value !is String) continue
            val old = parseGrid(key.removePrefix(OLD_LAYOUT_PREFIX)) ?: continue
            val fine = Grid(old.columns * FINE, old.rows * FINE)
            val oldAt = all[OLD_SAVED_AT_PREFIX + old.key] as? Long ?: 0L
            val fineAt = all[SAVED_AT_PREFIX + fine.key] as? Long
            if (fineAt != null && oldAt <= fineAt || fineAt == null && all.containsKey(LAYOUT_KEY_PREFIX + fine.key)) continue
            edit.putString(LAYOUT_KEY_PREFIX + fine.key, scaleLines(value, FINE)).putLong(SAVED_AT_PREFIX + fine.key, oldAt)
            copied = true
        }
        if (copied) edit.apply()
    }

    fun read(c: Grid): List<Tile>? {
        val text = prefs.getString(LAYOUT_KEY_PREFIX + c.key, null) ?: return null
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) return@mapNotNull null
            // Una línea dañada (escritura cortada) se salta; no tumba el inicio.
            val (col, row, width, height) = (2..5).map { f[it].toIntOrNull() ?: return@mapNotNull null }
            // El widget Navegar (hasta la 1.4.68) es ahora el Mapa, en el mismo lugar y tamaño (sus
            // datos eran otra cosa: el mapa queda como de fábrica).
            if (f[1] == LEGACY_NAVIGATE) return@mapNotNull Tile(f[0], TileTypes.MAP, col, row, width, height, "", plain = f.getOrElse(7) { "" } == "1")
            Tile(f[0], f[1], col, row, width, height, f.getOrElse(6) { "" }, plain = f.getOrElse(7) { "" } == "1")
        }.filter { p -> p.col >= 0 && p.row >= 0 }.toList()
    }

    fun save(c: Grid, tiles: List<Tile>) {
        val text = tiles.joinToString("\n") {
            // El campo nuevo va al final: las versiones de antes lo ignoran.
            listOf(it.id, it.type, it.col, it.row, it.width, it.height, it.data.replace('\t', ' ').replace('\n', ' '), if (it.plain) "1" else "")
                .joinToString("\t")
        }
        prefs.edit().putString(LAYOUT_KEY_PREFIX + c.key, text).putLong(SAVED_AT_PREFIX + c.key, System.currentTimeMillis()).apply()
    }

    /** Cuándo se guardó el acomodo de [c] (0 si nunca o si es de antes de la 1.4.100). */
    fun savedAt(c: Grid): Long = prefs.getLong(SAVED_AT_PREFIX + c.key, 0L)

    /** Las cuadrículas que tienen acomodo guardado. */
    fun grids(): List<Grid> = prefs.all.keys.mapNotNull { key ->
        key.takeIf { it.startsWith(LAYOUT_KEY_PREFIX) }?.let { parseGrid(it.removePrefix(LAYOUT_KEY_PREFIX)) }
    }

    /** La pieza grande de las plantillas (tipo y datos): el auto 3D si nunca se eligió otra. */
    fun hero(): Pair<String, String> =
        (prefs.getString(HERO_TYPE, null) ?: TileTypes.CAR) to (prefs.getString(HERO_DATA, null) ?: "")

    /** Elige la pieza grande de las plantillas; sus datos quedan vacíos (los de fábrica). */
    fun saveHero(type: String) {
        prefs.edit().putString(HERO_TYPE, type).putString(HERO_DATA, "").apply()
    }
}

/** "10x5" → la cuadrícula; null si no lo es. */
private fun parseGrid(size: String): Grid? {
    val parts = size.split('x').takeIf { it.size == 2 } ?: return null
    return Grid(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
}

/** El texto de un acomodo con columna, fila, ancho y alto (campos 2 a 5) multiplicados por [k]. */
internal fun scaleLines(text: String, k: Int) = text.lineSequence().joinToString("\n") { line ->
    val f = line.split('\t').toMutableList()
    if (f.size >= 6) for (i in 2..5) f[i].toIntOrNull()?.let { f[i] = (it * k).toString() }
    f.joinToString("\t")
}

// Nombres guardados en el dispositivo (en inglés desde la 1.4.47; la migración copió los viejos).
private const val PREFS_FILE = "dashboard"
/** Los acomodos en la cuadrícula fina (de 48 dp); los de 96 dp siguen con [OLD_LAYOUT_PREFIX]. */
private const val LAYOUT_KEY_PREFIX = "fine_layout_"
/** Hora en que se guardó cada acomodo (no empieza con el prefijo de ellos para no confundirse). */
private const val SAVED_AT_PREFIX = "fine_saved_at_"
private const val OLD_LAYOUT_PREFIX = "layout_"
private const val OLD_SAVED_AT_PREFIX = "saved_at_"

// La migración de la 1.4.49 (pieza del auto en tres) también los escribe: mismos nombres allá.
/** El tipo que tenía el widget Navegar en los acomodos guardados. */
private const val LEGACY_NAVIGATE = "navigate"
private const val HERO_TYPE = "hero_type"
private const val HERO_DATA = "hero_data"

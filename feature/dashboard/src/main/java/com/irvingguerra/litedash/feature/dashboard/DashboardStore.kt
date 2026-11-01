package com.irvingguerra.litedash.feature.dashboard

import android.content.Context

/**
 * Guarda un acomodo por tamaño de cuadrícula: la misma app en otra pantalla (u
 * otra escala) no rompe el acomodo de la primera. Formato de texto simple, sin
 * librerías: una pieza por línea, campos separados por tabulador.
 */
class DashboardStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun read(c: Grid): List<Tile>? {
        val text = prefs.getString(LAYOUT_KEY_PREFIX + c.key, null) ?: return null
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) return@mapNotNull null
            Tile(f[0], LEGACY_TYPES[f[1]] ?: f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f.getOrElse(6) { "" })
        }.filter { p -> p.col >= 0 && p.row >= 0 }.toList()
    }

    fun save(c: Grid, tiles: List<Tile>) {
        val text = tiles.joinToString("\n") {
            listOf(it.id, it.type, it.col, it.row, it.width, it.height, it.data.replace('\t', ' ').replace('\n', ' '))
                .joinToString("\t")
        }
        prefs.edit().putString(LAYOUT_KEY_PREFIX + c.key, text).apply()
    }
}

// Piezas que ya no existen y en qué se convierten (la del mapa de la 1.3.0 es ahora Navegar).
private val LEGACY_TYPES = mapOf("mapa" to TileTypes.NAVIGATE)

// Nombres ya guardados en el dispositivo (no se traducen: se perderían los acomodos).
private const val PREFS_FILE = "tablero"
private const val LAYOUT_KEY_PREFIX = "acomodo_"

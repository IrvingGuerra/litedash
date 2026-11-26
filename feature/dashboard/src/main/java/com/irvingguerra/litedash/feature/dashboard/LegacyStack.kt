package com.irvingguerra.litedash.feature.dashboard

import java.net.URLDecoder

/** El tipo que tenía la Pila (1.5.20–1.5.22) en los acomodos guardados. */
private const val LEGACY_STACK = "stack"

/**
 * Migración de la Pila de la 1.5.20 (se quitó después de la 1.5.22): una pieza Pila se vuelve la cara que estaba a la
 * vista (`current`; si no se encuentra, la primera) con el id, el lugar y el tamaño de la Pila y los datos de esa cara.
 * Cualquier otra pieza sale igual. Su texto era `actual|id,tipo,datos|…` con los datos en formato de URL. Sin ninguna
 * cara legible, la Música: nunca deja un hueco ni truena.
 * Quitar cuando no queden pantallas en la 1.5.20–1.5.22 según docs/usuarios.py.
 */
internal fun migrateStack1520(tile: Tile): Tile {
    if (tile.type != LEGACY_STACK) return tile
    val parts = tile.data.split('|')
    val faces = parts.drop(1).mapNotNull { part ->
        val f = part.split(',')
        if (f.size != 3 || f[0].isEmpty() || f[1].isEmpty() || f[1] == LEGACY_STACK) return@mapNotNull null
        val data = runCatching { URLDecoder.decode(f[2], "UTF-8") }.getOrNull() ?: return@mapNotNull null
        Triple(f[0], f[1], data)
    }
    val face = faces.firstOrNull { it.first == parts.first() } ?: faces.firstOrNull()
    return tile.copy(type = face?.second ?: TileTypes.MUSIC, data = face?.third.orEmpty())
}

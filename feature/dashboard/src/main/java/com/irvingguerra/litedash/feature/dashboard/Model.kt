package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.runtime.Immutable

/** Lado mínimo de una celda: de él sale cuántas columnas y filas caben en la pantalla. */
const val MIN_CELL_DP = 96

/** Tamaño de la cuadrícula en celdas. Más pantalla = más celdas = más libertad. */
@Immutable
data class Grid(val columns: Int, val rows: Int) {
    val vertical get() = rows >= columns
    val key get() = "${columns}x$rows"

    companion object {
        fun forSize(widthDp: Float, heightDp: Float) = Grid(
            columns = (widthDp / MIN_CELL_DP).toInt().coerceAtLeast(4),
            rows = (heightDp / MIN_CELL_DP).toInt().coerceAtLeast(3),
        )
    }
}

/**
 * Una pieza del tablero. [type] dice qué se dibuja (lo registra la app) y
 * [data] guarda lo que la pieza necesite, como el componente de una app.
 */
@Immutable
data class Tile(
    val id: String,
    val type: String,
    val col: Int,
    val row: Int,
    val width: Int,
    val height: Int,
    val data: String = "",
) {
    fun overlaps(other: Tile) =
        col < other.col + other.width && other.col < col + width &&
            row < other.row + other.height && other.row < row + height
}

/** Lo que la app sabe de cada tipo de pieza: nombre y tamaños permitidos (en celdas). */
data class SizeLimits(val minWidth: Int, val minHeight: Int, val maxWidth: Int = 99, val maxHeight: Int = 99) {
    fun clamp(width: Int, height: Int) =
        width.coerceIn(minWidth, maxWidth) to height.coerceIn(minHeight, maxHeight)
}

/** ¿Cabe [p] en la cuadrícula sin encimarse con las demás (ignorando a ella misma)? */
fun List<Tile>.fits(p: Tile, c: Grid): Boolean =
    p.col >= 0 && p.row >= 0 && p.width >= 1 && p.height >= 1 &&
        p.col + p.width <= c.columns && p.row + p.height <= c.rows &&
        none { it.id != p.id && it.overlaps(p) }

/** Primer lugar libre (de arriba abajo, de izquierda a derecha) para una pieza de ese tamaño. */
fun List<Tile>.freeSpot(width: Int, height: Int, c: Grid): Pair<Int, Int>? {
    for (row in 0..c.rows - height) for (col in 0..c.columns - width) {
        if (fits(Tile("?", "?", col, row, width, height), c)) return col to row
    }
    return null
}

/**
 * Las mismas piezas acomodadas en otra cuadrícula (más chica, como la mitad de la
 * pantalla con la música al lado), sin tocar el acomodo guardado: en su orden (de
 * arriba abajo), cada una con su tamaño recortado a lo que quepa o, si no, a su
 * mínimo. Las que ya no caben se quedan fuera mientras dure.
 */
fun List<Tile>.packed(into: Grid, limits: (String) -> SizeLimits): List<Tile> {
    val placed = mutableListOf<Tile>()
    for (t in sortedWith(compareBy({ it.row }, { it.col }))) {
        val l = limits(t.type)
        val sizes = listOf(
            l.clamp(t.width.coerceAtMost(into.columns), t.height.coerceAtMost(into.rows)),
            l.minWidth to l.minHeight,
        )
        for ((width, height) in sizes) {
            val spot = placed.freeSpot(width, height, into) ?: continue
            placed += t.copy(col = spot.first, row = spot.second, width = width, height = height)
            break
        }
    }
    return placed
}

/** Reemplaza la pieza con el mismo id. */
fun List<Tile>.replace(p: Tile) = map { if (it.id == p.id) p else it }

// Tipos de pieza que conocen las plantillas. La app registra cómo se dibuja cada uno.
// Los valores se guardan en cada acomodo: van en español y no se traducen.
object TileTypes {
    const val CAR = "auto"
    const val MUSIC = "musica"
    const val NAVIGATE = "navegar"
    const val CLOCK = "reloj"
    const val PHONE = "telefono"
    const val WEATHER = "clima"
    const val TRIP = "viaje"
    const val PROJECTION = "proyeccion"
    const val APP = "app"
}

/** Tamaños de pantalla, para agrupar los acomodos y sugerir el mejor. */
enum class ScreenSize { SMALL, MEDIUM, LARGE, VERTICAL }

/** Qué tamaño es esta cuadrícula: de 7" (10×4) a 11" o más (13×6 y arriba). */
val Grid.size: ScreenSize
    get() = when {
        vertical -> ScreenSize.VERTICAL
        columns * rows >= 75 -> ScreenSize.LARGE
        columns * rows >= 55 -> ScreenSize.MEDIUM
        else -> ScreenSize.SMALL
    }

/** Los acomodos de inicio y para qué pantallas lucen mejor ([sizes]). */
enum class Template(val sizes: Set<ScreenSize>) {
    /** Como el Model 3: el auto grande al centro y dos tarjetas abajo. */
    CENTER(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM)),
    /** Como DuduAuto: el auto a todo lo ancho y una fila de tarjetas abajo. */
    CARDS(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** El auto a la izquierda y las tarjetas a la derecha. */
    SIDE(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Tres columnas altas: navegar, música y el auto. */
    COLUMNS(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Tablero completo: tarjetas a los dos lados y el auto en medio. */
    PANEL(setOf(ScreenSize.LARGE)),
    /** Pantallas paradas: auto arriba, tarjetas abajo. */
    VERTICAL(setOf(ScreenSize.VERTICAL)),
}

/**
 * Acomodos de inicio. Se calculan para cualquier cuadrícula: lo que no quepa se
 * omite, y nunca se enciman (lo revisan las pruebas).
 */
fun Template.tiles(c: Grid): List<Tile> {
    val cols = c.columns
    val rows = c.rows
    val list = mutableListOf<Tile>()
    fun place(type: String, col: Int, row: Int, width: Int, height: Int) {
        val p = Tile("$type-${list.size}", type, col, row, width, height)
        if (width >= 1 && height >= 1 && list.fits(p, c)) list += p
    }
    when (this) {
        Template.CENTER -> {
            val bottom = if (rows >= 6) 2 else 1
            val carWidth = (cols * 0.6).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, (cols - carWidth) / 2, 0, carWidth, rows - bottom)
            // Las tarjetas de abajo miden lo mismo que el auto, alineadas con él.
            val start = (cols - carWidth) / 2
            val musicWidth = (carWidth * 0.6).toInt().coerceAtLeast(3)
            val navWidth = carWidth - musicWidth
            place(TileTypes.MUSIC, start, rows - bottom, musicWidth, bottom)
            place(TileTypes.NAVIGATE, start + musicWidth, rows - bottom, navWidth, bottom)
        }
        Template.CARDS -> {
            val bottom = if (rows >= 6) 2 else 1
            place(TileTypes.CAR, 0, 0, cols, rows - bottom)
            // Navegar, música (la más ancha) y lo que quepa: reloj y teléfono.
            val music = maxOf(3, cols * 4 / 10)
            val rest = cols - music
            val others = when {
                rest >= 7 -> listOf(TileTypes.CLOCK, TileTypes.PHONE)
                rest >= 4 -> listOf(TileTypes.CLOCK)
                else -> emptyList()
            }
            val navWidth = if (others.isEmpty()) rest else (rest / (others.size + 1)).coerceAtLeast(2)
            place(TileTypes.NAVIGATE, 0, rows - bottom, navWidth, bottom)
            place(TileTypes.MUSIC, navWidth, rows - bottom, music, bottom)
            var col = navWidth + music
            others.forEachIndexed { i, type ->
                val width = if (i == others.lastIndex) cols - col else (cols - col) / (others.size - i)
                place(type, col, rows - bottom, width, bottom)
                col += width
            }
        }
        Template.SIDE -> {
            val carWidth = (cols * 0.4).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, 0, 0, carWidth, rows)
            val rest = cols - carWidth
            val clockHeight = if (rows >= 5) 2 else 1
            place(TileTypes.CLOCK, carWidth, 0, rest, clockHeight)
            val musicHeight = if (rows - clockHeight >= 3) 2 else 1
            place(TileTypes.MUSIC, carWidth, clockHeight, rest, musicHeight)
            val row = clockHeight + musicHeight
            val half = rest / 2
            place(TileTypes.NAVIGATE, carWidth, row, half, rows - row)
            place(TileTypes.PHONE, carWidth + half, row, rest - half, rows - row)
        }
        Template.COLUMNS -> {
            if (cols < 9) {
                // No caben tres columnas: el auto arriba y música abajo.
                place(TileTypes.CAR, 0, 0, cols, rows - 1)
                place(TileTypes.MUSIC, 0, rows - 1, cols, 1)
            } else {
                val navWidth = cols / 4
                val musicWidth = maxOf(3, cols / 3)
                val carStart = navWidth + musicWidth
                place(TileTypes.CAR, carStart, 0, cols - carStart, rows)
                place(TileTypes.NAVIGATE, 0, 0, navWidth, rows)
                val musicHeight = minOf(rows, 3)
                place(TileTypes.MUSIC, navWidth, 0, musicWidth, musicHeight)
                if (rows > musicHeight) place(TileTypes.CLOCK, navWidth, musicHeight, musicWidth, rows - musicHeight)
            }
        }
        Template.PANEL -> {
            val side = cols / 4
            if (side < 3 || cols - 2 * side < 3) {
                // Pantalla chica: el auto con la música abajo.
                place(TileTypes.CAR, 0, 0, cols, rows - 1)
                place(TileTypes.MUSIC, 0, rows - 1, cols, 1)
            } else {
                place(TileTypes.CAR, side, 0, cols - 2 * side, rows)
                val half = (rows + 1) / 2
                place(TileTypes.MUSIC, 0, 0, side, minOf(half, 3))
                place(TileTypes.NAVIGATE, 0, minOf(half, 3), side, rows - minOf(half, 3))
                place(TileTypes.CLOCK, cols - side, 0, side, half)
                place(TileTypes.PHONE, cols - side, half, side, rows - half)
            }
        }
        Template.VERTICAL -> {
            val carHeight = (rows * 0.45).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, 0, 0, cols, carHeight)
            place(TileTypes.MUSIC, 0, carHeight, cols, 2)
            val row = carHeight + 2
            val half = cols / 2
            place(TileTypes.NAVIGATE, 0, row, half, 2)
            place(TileTypes.PHONE, half, row, cols - half, 2)
        }
    }
    return list
}

/** El acomodo que mejor luce en esta pantalla. */
fun Grid.suggestedTemplate() = when (size) {
    ScreenSize.SMALL -> Template.CENTER
    ScreenSize.MEDIUM -> Template.CARDS
    ScreenSize.LARGE -> Template.PANEL
    ScreenSize.VERTICAL -> Template.VERTICAL
}

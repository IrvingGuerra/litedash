package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Lado mínimo de una celda: de él sale cuántas columnas y filas caben en la pantalla. Fina (desde el
 * Plan Widgets): el doble de columnas y filas que la de 96 dp de antes, para acomodar con más libertad.
 */
const val MIN_CELL_DP = 48

/** Celdas finas por cada celda de las plantillas ([coarse], las de 96 dp de antes). */
const val FINE = 2

/** Tamaño de la cuadrícula en celdas. Más pantalla = más celdas = más libertad. */
@Immutable
data class Grid(val columns: Int, val rows: Int) {
    val vertical get() = rows >= columns
    val key get() = "${columns}x$rows"

    companion object {
        fun forSize(widthDp: Float, heightDp: Float) = Grid(
            columns = (widthDp / MIN_CELL_DP).toInt().coerceAtLeast(4 * FINE),
            rows = (heightDp / MIN_CELL_DP).toInt().coerceAtLeast(3 * FINE),
        )
    }
}

/**
 * La misma pantalla en celdas de plantilla (de 96 dp): ahí se calculan las plantillas, su tamaño de
 * pantalla ([size]) y la recomendada, y luego se escalan a la fina ([Template.tiles]).
 */
val Grid.coarse get() = Grid(columns / FINE, rows / FINE)

/**
 * La pieza de la cuadrícula [from] llevada a [to] (la misma pantalla con otras celdas): cada orilla va a
 * la celda más cercana, así las piezas que se tocaban se siguen tocando y ninguna se encima.
 */
fun Tile.scaledTo(from: Grid, to: Grid): Tile {
    fun x(v: Int) = (v.toFloat() * to.columns / from.columns).roundToInt()
    fun y(v: Int) = (v.toFloat() * to.rows / from.rows).roundToInt()
    val left = x(col)
    val top = y(row)
    return copy(col = left, row = top, width = maxOf(1, x(col + width) - left), height = maxOf(1, y(row + height) - top))
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
    /** Sin fondo de tarjeta: lo de adentro flota sobre el fondo del inicio. */
    val plain: Boolean = false,
) {
    fun overlaps(other: Tile) =
        col < other.col + other.width && other.col < col + width &&
            row < other.row + other.height && other.row < row + height
}

/**
 * El tamaño mínimo de un tipo de pieza (en celdas). No hay máximo: cada pieza crece lo que quiera
 * mientras quepa en la cuadrícula ([clamp] con el espacio que le queda).
 */
data class SizeLimits(val minWidth: Int, val minHeight: Int) {
    fun clamp(width: Int, height: Int, maxWidth: Int, maxHeight: Int) =
        width.coerceIn(minWidth, maxOf(minWidth, maxWidth)) to height.coerceIn(minHeight, maxOf(minHeight, maxHeight))
}

/** ¿Cabe [p] en la cuadrícula sin encimarse con las demás (ignorando a ella misma)? */
fun List<Tile>.fits(p: Tile, c: Grid): Boolean =
    p.width >= 1 && p.height >= 1 && p.inside(c) && none { it.id != p.id && it.overlaps(p) }

/** Primer lugar libre (de arriba abajo, de izquierda a derecha) para una pieza de ese tamaño. */
fun List<Tile>.freeSpot(width: Int, height: Int, c: Grid): Pair<Int, Int>? {
    for (row in 0..c.rows - height) for (col in 0..c.columns - width) {
        if (fits(Tile("?", "?", col, row, width, height), c)) return col to row
    }
    return null
}

/**
 * Casi la misma cuadrícula (misma orientación y ±1 celda de plantilla, [coarse]): la misma pantalla con una
 * barra del sistema de más o de menos. Entre ellas se usa el último acomodo guardado.
 */
fun Grid.near(other: Grid): Boolean {
    val a = coarse
    val b = other.coarse
    return vertical == other.vertical && kotlin.math.abs(a.columns - b.columns) <= 1 && kotlin.math.abs(a.rows - b.rows) <= 1
}

/**
 * El acomodo con [moved] (una pieza que se arrastró o cambió de tamaño) en su lugar nuevo: las que le
 * estorban se hacen a un lado (como en Android). Cada una va al hueco libre más cercano a donde estaba; si
 * no hay, empuja a su vez a otras que sí tengan hueco (en cadena, un paso), o se va al lugar que dejó
 * [moved]. Las demás no se mueven. null si [moved] se sale de la cuadrícula o si alguna ya no cabe.
 * Conserva el orden de la lista.
 */
fun List<Tile>.arrange(moved: Tile, c: Grid): List<Tile>? {
    if (!moved.inside(c)) return null
    val before = firstOrNull { it.id == moved.id }
    val (hit, stay) = filter { it.id != moved.id }.partition { it.overlaps(moved) }
    val placed = (stay + moved).toMutableList()
    // Las grandes primero: las chicas encuentran hueco más fácil.
    for (t in hit.sortedByDescending { it.width * it.height }) {
        val free = spotsByDistance(t, c).firstOrNull { s -> placed.none { it.overlaps(s) } }
        if (free != null) {
            placed += free
            continue
        }
        // En cadena: un lugar donde solo estorbe a piezas que no se han movido y que sí tienen hueco.
        val chained = spotsByDistance(t, c).take(CHAIN_TRIES).firstNotNullOfOrNull { s ->
            if (s.overlaps(moved)) return@firstNotNullOfOrNull null
            val blocked = placed.filter { it.overlaps(s) }
            if (blocked.any { it.id == moved.id || it !in stay }) return@firstNotNullOfOrNull null
            val rest = (placed - blocked.toSet() + s).toMutableList()
            for (o in blocked) rest += spotsByDistance(o, c).firstOrNull { o2 -> rest.none { it.overlaps(o2) } } ?: return@firstNotNullOfOrNull null
            rest
        }
        if (chained != null) {
            placed.clear()
            placed += chained
            continue
        }
        // Intercambio: al lugar que dejó la pieza movida, si ahí cabe.
        val swap = before?.let { t.copy(col = it.col, row = it.row) }?.takeIf { s -> s.inside(c) && placed.none { it.overlaps(s) } }
        placed += swap ?: return null
    }
    return map { orig -> placed.first { it.id == orig.id } }.let { if (before == null) it + moved else it }
}

/** Cuántos lugares cercanos prueba el empuje en cadena (es el paso caro). */
private const val CHAIN_TRIES = 40

private fun Tile.inside(c: Grid) = col >= 0 && row >= 0 && col + width <= c.columns && row + height <= c.rows

/**
 * Todos los lugares de la cuadrícula donde cabe [t] (por su tamaño), del más cercano a donde está al más lejano (a
 * igual distancia, de arriba abajo y de izquierda a derecha). Se ordenan números (distancia y lugar en uno) y la
 * pieza de cada lugar se arma solo si se pide: quien busca el primero que sirva no paga los demás.
 */
private fun spotsByDistance(t: Tile, c: Grid): Sequence<Tile> {
    val cols = c.columns - t.width + 1
    val rows = c.rows - t.height + 1
    if (cols <= 0 || rows <= 0) return emptySequence()
    val keys = LongArray(cols * rows) { i ->
        val dc = i % cols - t.col
        val dr = i / cols - t.row
        (dc.toLong() * dc + dr.toLong() * dr) shl 32 or i.toLong()
    }
    keys.sort()
    return keys.asSequence().map { k -> (k and 0xFFFFFFFFL).toInt().let { i -> t.copy(col = i % cols, row = i / cols) } }
}

/**
 * Lleva un acomodo de otra cuadrícula a [c]: cada pieza se queda donde estaba si cabe; si se sale, se
 * recorta hasta su mínimo ([limits]) o se recorre hacia adentro; si choca con otra, va al primer hueco
 * (con su tamaño o el mínimo). La que no cabe de ninguna forma se queda fuera.
 */
fun List<Tile>.adaptTo(c: Grid, limits: (String) -> SizeLimits): List<Tile> {
    val placed = mutableListOf<Tile>()
    for (t in this) {
        val m = limits(t.type)
        if (m.minWidth > c.columns || m.minHeight > c.rows) continue
        val width = t.width.coerceAtMost(maxOf(m.minWidth, c.columns - t.col)).coerceIn(m.minWidth, c.columns)
        val height = t.height.coerceAtMost(maxOf(m.minHeight, c.rows - t.row)).coerceIn(m.minHeight, c.rows)
        val inside = t.copy(
            col = t.col.coerceIn(0, c.columns - width),
            row = t.row.coerceIn(0, c.rows - height),
            width = width,
            height = height,
        )
        val spot = if (placed.fits(inside, c)) inside else
            listOf(width to height, m.minWidth to m.minHeight).firstNotNullOfOrNull { (w, h) ->
                placed.freeSpot(w, h, c)?.let { (col, row) -> t.copy(col = col, row = row, width = w, height = h) }
            }
        if (spot != null) placed += spot
    }
    return placed
}

/** Reemplaza la pieza con el mismo id. */
fun List<Tile>.replace(p: Tile) = map { if (it.id == p.id) p else it }

// Tipos de pieza que conocen las plantillas. La app registra cómo se dibuja cada uno.
// Los valores se guardan en cada acomodo: van en inglés y no se traducen.
object TileTypes {
    /** El auto 3D que gira con el dedo. */
    const val CAR = "car"
    /** Tu auto de espaldas en una carretera que avanza con la velocidad. */
    const val ROAD = "road"
    /** Una foto tuya; [Tile.data] es el nombre de su archivo (vacío = sin foto todavía). */
    const val IMAGE = "image"
    const val MUSIC = "music"
    /** La radio de la pantalla (FM) o la de LiteDash por internet. */
    const val RADIO = "radio"
    /** Mapa de OpenStreetMap con tu posición (feature/map). */
    const val MAP = "map"
    const val CLOCK = "clock"
    const val PHONE = "phone"
    const val WEATHER = "weather"
    const val TRIP = "trip"
    const val SPEEDOMETER = "speedometer"
    /** Un dato del auto por OBD; [Tile.data] dice cuál (una señal del bus). */
    const val GAUGE = "gauge"
    const val WORKSHOP = "workshop"
    const val PROJECTION = "projection"
    /** Presión de las cuatro llantas (módulo de llantas). */
    const val TIRES = "tires"
    const val APP = "app"
}

/** Sin fondo de tarjeta siempre: el auto, la carretera y la imagen flotan sobre el inicio. */
val Tile.cardless get() = type == TileTypes.CAR || type == TileTypes.ROAD || type == TileTypes.IMAGE

/** Un rectángulo de celdas de la cuadrícula. */
data class Cells(val col: Int, val row: Int, val width: Int, val height: Int)

/**
 * El hueco libre más grande de [grid] con estas piezas (el rectángulo de celdas vacías de más área; a la par, el más
 * cuadrado): ahí va tu posición en el mapa de fondo. null = no queda ninguna celda libre.
 */
fun List<Tile>.freeArea(grid: Grid): Cells? {
    val filled = Array(grid.rows) { BooleanArray(grid.columns) }
    for (t in this) for (r in t.row until minOf(grid.rows, t.row + t.height)) for (c in t.col until minOf(grid.columns, t.col + t.width)) filled[r][c] = true
    // Por fila, cuántas celdas libres hay hacia arriba en cada columna; el rectángulo más grande de ese histograma.
    val up = IntArray(grid.columns)
    var best: Cells? = null
    fun better(a: Cells, b: Cells?): Boolean {
        if (b == null) return true
        val area = a.width * a.height - b.width * b.height
        return area > 0 || area == 0 && minOf(a.width, a.height) > minOf(b.width, b.height)
    }
    for (r in 0 until grid.rows) {
        for (c in 0 until grid.columns) up[c] = if (filled[r][c]) 0 else up[c] + 1
        for (c in 0 until grid.columns) {
            var h = Int.MAX_VALUE
            for (e in c until grid.columns) {
                h = minOf(h, up[e])
                if (h == 0) break
                val cand = Cells(c, r - h + 1, e - c + 1, h)
                if (better(cand, best)) best = cand
            }
        }
    }
    return best
}

/** Lo que va detrás de los widgets de una página: el fondo de siempre (tu imagen o el del tema) o el mapa vivo. */
enum class PageBackground(val stored: String) {
    WALLPAPER("wallpaper"), MAP("map");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: WALLPAPER
    }
}

/** Tamaños de pantalla, para agrupar los acomodos y sugerir el mejor. */
enum class ScreenSize { SMALL, MEDIUM, LARGE, VERTICAL }

/** Qué tamaño es esta cuadrícula de plantilla ([Grid.coarse]): de 7" (10×4) a 11" o más (13×6 y arriba). */
val Grid.size: ScreenSize
    get() = when {
        vertical -> ScreenSize.VERTICAL
        columns * rows >= 75 -> ScreenSize.LARGE
        columns * rows >= 55 -> ScreenSize.MEDIUM
        else -> ScreenSize.SMALL
    }

/**
 * Los acomodos de inicio y para qué pantallas lucen mejor ([sizes]). Desde la 1.5.23 solo se eligen los de los Diseños; los
 * demás (los Acomodos de antes) se quedan sin pantalla para elegirlos porque cada página guarda el nombre de su plantilla
 * ([DashboardStore.template]) y con él se arma en una cuadrícula sin acomodo guardado (otra escala u orientación), y la
 * primera página sin plantilla usa la recomendada ([suggestedTemplate]): así a nadie le cambia el inicio.
 */
enum class Template(val sizes: Set<ScreenSize>) {
    /** Como el Model 3: el auto grande al centro y abajo la música con el mapa (o la hora, si no cabe). */
    CENTER(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM)),
    /** Como DuduAuto: el auto a todo lo ancho y una fila de tarjetas abajo (mapa, música, velocidad). */
    CARDS(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** El auto a la izquierda y a la derecha la hora, la música y el mapa. */
    SIDE(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Tres columnas altas: mapa, música y el auto. */
    COLUMNS(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Tablero completo: tarjetas a los dos lados y el auto en medio. */
    PANEL(setOf(ScreenSize.LARGE)),
    /** Pantallas paradas: auto arriba, música y mapa abajo. */
    VERTICAL(setOf(ScreenSize.VERTICAL)),
    /** Solo el velocímetro, grande al centro: nada más que ver. */
    SPEED(ScreenSize.entries.toSet()),
    /** El auto con el velocímetro al lado; música y mapa abajo. */
    CAR_SPEED(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Deportiva: el auto arriba y abajo revoluciones, velocidad y temperatura del motor. */
    SPORT(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Sin mapa: el auto, la música grande y la radio. */
    MUSIC_RADIO(ScreenSize.entries.toSet()),
    /** Mínima: la hora en grande y la música, sin auto. */
    MINIMAL(ScreenSize.entries.toSet()),
    /** El velocímetro al centro con la hora y la fecha a un lado, y el clima y el viaje al otro. */
    SPEED_WIDGETS(ScreenSize.entries.toSet()),
    /** Para lucir tu fondo (como BYD): la hora flotando arriba y una fila de tarjetas abajo. */
    WALL_CARDS(ScreenSize.entries.toSet()),
    /** Tu fondo a la izquierda y una columna de tarjetas a la derecha, con la hora flotando. */
    WALL_SIDE(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Como el Model 3 navegando: el auto y la música del lado del conductor y el mapa grande (~55 %) al otro. */
    MAP_CAR(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** El mapa en columna alta, el auto al centro y la música y la radio del lado del copiloto. */
    COPILOT(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Para manejar con navegación: el mapa casi completo y una columna angosta con velocidad y música. */
    ROUTE(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Pantallas paradas: el mapa arriba y abajo el auto con la música y la radio. */
    VERTICAL_MAP(setOf(ScreenSize.VERTICAL)),
    /**
     * El mapa de fondo (#18): una columna de widgets a la derecha (abajo en pantallas paradas) y el resto libre para ver
     * el mapa contigo en medio. Al aplicarla, la página pasa a fondo de mapa ([DashboardState.template]).
     */
    MAP_BACKGROUND(ScreenSize.entries.toSet()),

    // Los acomodos de los Diseños (app/Designs.kt): uno por Diseño, para cualquier pantalla (de pie, muy ancha desde 2.6:1
    // o ancha).
    /** Grafito: el mapa grande y una columna con la música (de pie, tu auto), la velocidad y el clima. */
    DESIGN_GRAPHITE(ScreenSize.entries.toSet()),
    /** Nube: tu auto y el mapa en dos mitades; abajo la música y el clima. */
    DESIGN_CLOUD(ScreenSize.entries.toSet()),
    /** Pista: el velocímetro grande al centro sin tarjeta; tu auto en la carretera (sin su velocidad) y el viaje a un lado, el mapa y la música al otro. */
    DESIGN_TRACK(ScreenSize.entries.toSet()),
    /** Ruta: el mapa es la página (fondo de mapa); flotan tu auto, grande, y la música. */
    DESIGN_ROUTE(ScreenSize.entries.toSet()),
    /** Columna: tipo tableta, una columna de tarjetas y tu auto grande. */
    DESIGN_COLUMN(ScreenSize.entries.toSet()),
    /** Neón: la hora flotando, el velocímetro de aro neón al centro, la música, el mapa y el clima. */
    DESIGN_NEON(ScreenSize.entries.toSet()),
    /** Arena: la hora y el clima a un lado, tu auto al centro con la música y el mapa alto al otro. */
    DESIGN_SAND(ScreenSize.entries.toSet()),
    /** Bosque: el mapa a la mitad, tu auto al otro lado, la música (o la radio) y el clima. */
    DESIGN_FOREST(ScreenSize.entries.toSet()),
}

/** Los acomodos de los Diseños, en el orden de su galería. */
val DESIGN_TEMPLATES = listOf(
    Template.DESIGN_GRAPHITE, Template.DESIGN_CLOUD, Template.DESIGN_TRACK, Template.DESIGN_ROUTE,
    Template.DESIGN_COLUMN, Template.DESIGN_NEON, Template.DESIGN_SAND, Template.DESIGN_FOREST,
)

val Template.isDesign get() = this in DESIGN_TEMPLATES

/** Las que, al aplicarlas, ponen el mapa de fondo en la página ([DashboardState.template]). */
val Template.mapBackground get() = this == Template.MAP_BACKGROUND || this == Template.DESIGN_ROUTE

/**
 * La plantilla en la cuadrícula fina [c]: se calcula en sus celdas de plantilla ([Grid.coarse], ver
 * [layout]) y se escala ([scaledTo]); así llena la pantalla igual que antes.
 */
fun Template.tiles(c: Grid, hero: String = TileTypes.CAR, skip: (String) -> Boolean = { false }): List<Tile> {
    val coarse = c.coarse
    return layout(coarse, hero, skip).map { it.scaledTo(coarse, c) }
}

/**
 * Acomodos de inicio en celdas de plantilla (de 96 dp). Se calculan para cualquier cuadrícula: lo que no quepa se
 * omite, y nunca se enciman (lo revisan las pruebas). La pieza grande es el auto 3D
 * o, con [hero] = [TileTypes.ROAD], la carretera en su lugar. Los tipos de [skip] (de un
 * módulo apagado) no se ponen; la deportiva cambia sus medidores por la hora y el viaje.
 */
fun Template.layout(c: Grid, hero: String = TileTypes.CAR, skip: (String) -> Boolean = { false }): List<Tile> {
    val cols = c.columns
    val rows = c.rows
    val list = mutableListOf<Tile>()
    fun place(kind: String, col: Int, row: Int, width: Int, height: Int, data: String = "", plain: Boolean = false) {
        val type = if (kind == TileTypes.CAR) hero else kind
        if (skip(type)) return
        val p = Tile("$type-${list.size}", type, col, row, width, height, data, plain)
        if (width >= 1 && height >= 1 && list.fits(p, c)) list += p
    }
    // El mapa solo luce de 3×2 para arriba; más chico va [fallback] en su lugar (o nada).
    fun mapOr(fallback: String?, col: Int, row: Int, width: Int, height: Int, fallbackData: String = "") {
        if (width >= 3 && height >= 2) place(TileTypes.MAP, col, row, width, height)
        else if (fallback != null) place(fallback, col, row, width, height, fallbackData)
    }
    // El primero de [choices] que no sea de un módulo apagado ni esté ya en la página (nada repetido: dos autos o dos
    // músicas no); si ninguno, la hora o el clima (sin hueco).
    fun firstFree(choices: List<String>, col: Int, row: Int, width: Int, height: Int) {
        val type = (choices + FREE_FALLBACK).map { if (it == TileTypes.CAR) hero else it }
            .firstOrNull { !skip(it) && list.none { t -> t.type == it } } ?: return
        place(type, col, row, width, height)
    }
    // Lo de los Diseños, como su especificación (litedash-claude docs/disenos/acomodos.py): redondeo a la mitad hacia
    // arriba y pantalla muy ancha desde 2.6:1.
    fun rnd(x: Double) = floor(x + 0.5).toInt()
    fun up(x: Double) = ceil(x).toInt()
    val ultra = cols >= rows * 2.6
    // El viaje o, con OBD, las revoluciones (como la deportiva).
    val tripType = if (skip(TileTypes.GAUGE)) TileTypes.TRIP else TileTypes.GAUGE
    val tripData = if (tripType == TileTypes.GAUGE) "rpm" else ""
    // No caben columnas: el auto arriba y la música abajo.
    fun carOverMusic() {
        place(TileTypes.CAR, 0, 0, cols, rows - 1)
        place(TileTypes.MUSIC, 0, rows - 1, cols, 1)
    }
    // Música y radio apiladas (3 filas cuando mucho cada una); si sobra, la hora abajo.
    fun musicAndRadio(col: Int, row: Int, width: Int, height: Int) {
        val music = minOf(3, (height + 1) / 2)
        val radio = minOf(3, height - music)
        place(TileTypes.MUSIC, col, row, width, music)
        place(TileTypes.RADIO, col, row + music, width, radio)
        if (music + radio < height) place(TileTypes.CLOCK, col, row + music + radio, width, height - music - radio)
    }
    // Fila de tarjetas de abajo: de dos filas desde 5, para que quepa el mapa.
    val strip = if (rows >= 5) 2 else 1
    when (this) {
        Template.CENTER -> {
            val carWidth = (cols * 0.6).toInt().coerceAtLeast(3)
            val start = (cols - carWidth) / 2
            place(TileTypes.CAR, start, 0, carWidth, rows - strip)
            // Las tarjetas de abajo miden lo mismo que el auto, alineadas con él.
            val musicWidth = (carWidth * 0.6).toInt().coerceAtLeast(3)
            place(TileTypes.MUSIC, start, rows - strip, musicWidth, strip)
            mapOr(TileTypes.CLOCK, start + musicWidth, rows - strip, carWidth - musicWidth, strip)
        }
        Template.CARDS -> {
            place(TileTypes.CAR, 0, 0, cols, rows - strip)
            val music = maxOf(3, cols * 4 / 10)
            val rest = cols - music
            val row = rows - strip
            if (strip >= 2 && rest >= 3) {
                // Mapa, música (la más ancha) y, si sobra, la velocidad.
                val speed = if (rest >= 5) rest / 2 else 0
                val map = rest - speed
                place(TileTypes.MAP, 0, row, map, strip)
                place(TileTypes.MUSIC, map, row, music, strip)
                place(TileTypes.SPEEDOMETER, map + music, row, speed, strip)
            } else if (rest >= 4) {
                // En una fila el mapa no luce: velocidad, música y hora.
                val speed = rest / 2
                place(TileTypes.SPEEDOMETER, 0, row, speed, strip)
                place(TileTypes.MUSIC, speed, row, music, strip)
                place(TileTypes.CLOCK, speed + music, row, rest - speed, strip)
            } else if (rest >= 2) {
                // Angosta (5 columnas): velocidad y hora no caben de 2 de ancho; solo música y hora.
                place(TileTypes.MUSIC, 0, row, music, strip)
                place(TileTypes.CLOCK, music, row, rest, strip)
            } else place(TileTypes.MUSIC, 0, row, cols, strip)
        }
        Template.SIDE -> {
            val carWidth = (cols * 0.4).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, 0, 0, carWidth, rows)
            val rest = cols - carWidth
            val music = if (rows >= 5) 2 else 1
            place(TileTypes.CLOCK, carWidth, 0, rest, 1)
            place(TileTypes.MUSIC, carWidth, 1, rest, music)
            // Sin lugar para el mapa (pantallas de 3 filas), la radio: sin huecos.
            mapOr(TileTypes.RADIO, carWidth, 1 + music, rest, rows - 1 - music)
        }
        Template.COLUMNS -> {
            if (cols < 9) carOverMusic()
            else {
                val mapWidth = maxOf(3, cols / 4)
                val musicWidth = maxOf(3, cols / 3)
                val carStart = mapWidth + musicWidth
                mapOr(null, 0, 0, mapWidth, rows)
                val musicHeight = minOf(rows, 3)
                place(TileTypes.MUSIC, mapWidth, 0, musicWidth, musicHeight)
                if (rows > musicHeight) place(TileTypes.CLOCK, mapWidth, musicHeight, musicWidth, rows - musicHeight)
                place(TileTypes.CAR, carStart, 0, cols - carStart, rows)
            }
        }
        Template.PANEL -> {
            val side = cols / 4
            if (side < 3 || cols - 2 * side < 3) carOverMusic()
            else {
                place(TileTypes.CAR, side, 0, cols - 2 * side, rows)
                val half = (rows + 1) / 2
                val music = minOf(half, 3)
                place(TileTypes.MUSIC, 0, 0, side, music)
                mapOr(TileTypes.RADIO, 0, music, side, rows - music)
                place(TileTypes.CLOCK, cols - side, 0, side, half)
                place(TileTypes.PHONE, cols - side, half, side, rows - half)
            }
        }
        Template.VERTICAL -> {
            val carHeight = (rows * 0.45).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, 0, 0, cols, carHeight)
            place(TileTypes.MUSIC, 0, carHeight, cols, 2)
            mapOr(TileTypes.CLOCK, 0, carHeight + 2, cols, rows - carHeight - 2)
        }
        Template.SPEED -> {
            // Un poco más ancho que alto (el arco es así); en pantallas paradas, cuadrado al centro.
            val height = if (c.vertical) minOf(rows, cols) else rows
            val width = minOf(cols, maxOf(2, (height * 1.4f + 0.5f).toInt()))
            place(TileTypes.SPEEDOMETER, (cols - width) / 2, (rows - height) / 2, width, height, plain = true)
        }
        Template.CAR_SPEED -> {
            val speedWidth = maxOf(2, (cols * 0.35f).toInt())
            place(TileTypes.CAR, 0, 0, cols - speedWidth, rows - strip)
            place(TileTypes.SPEEDOMETER, cols - speedWidth, 0, speedWidth, rows - strip)
            val music = maxOf(3, cols * 6 / 10).coerceAtMost(cols)
            place(TileTypes.MUSIC, 0, rows - strip, music, strip)
            mapOr(TileTypes.CLOCK, music, rows - strip, cols - music, strip)
        }
        Template.SPORT -> {
            // Con 5 filas o más los medidores llevan arco; con menos, número y barra.
            place(TileTypes.CAR, 0, 0, cols, rows - strip)
            val third = cols / 3
            // Señales del bus (core/vehicle): revoluciones y temperatura del motor. Sin OBD, la hora y el viaje.
            val obd = !skip(TileTypes.GAUGE)
            if (obd) place(TileTypes.GAUGE, 0, rows - strip, third, strip, data = "rpm")
            else place(TileTypes.CLOCK, 0, rows - strip, third, strip)
            place(TileTypes.SPEEDOMETER, third, rows - strip, cols - 2 * third, strip)
            if (obd) place(TileTypes.GAUGE, cols - third, rows - strip, third, strip, data = "coolant_temp")
            else place(TileTypes.TRIP, cols - third, rows - strip, third, strip)
        }
        Template.MUSIC_RADIO -> {
            if (c.vertical) {
                val cards = minOf(6, rows / 2)
                place(TileTypes.CAR, 0, 0, cols, rows - cards)
                musicAndRadio(0, rows - cards, cols, cards)
            } else {
                val carWidth = maxOf(3, (cols * 0.45f).toInt())
                if (cols - carWidth < 3) carOverMusic()
                else {
                    place(TileTypes.CAR, 0, 0, carWidth, rows)
                    musicAndRadio(carWidth, 0, cols - carWidth, rows)
                }
            }
        }
        Template.MINIMAL -> {
            val bottom = if (rows >= 6) 2 else 1
            val width = minOf(cols, maxOf(4, cols * 6 / 10))
            val start = (cols - width) / 2
            place(TileTypes.CLOCK, start, 0, width, rows - bottom)
            place(TileTypes.MUSIC, start, rows - bottom, width, bottom)
        }
        Template.SPEED_WIDGETS -> {
            val side = cols / 4
            if (c.vertical || side < 2 || cols - 2 * side < 3) {
                // No caben las tres columnas: el velocímetro arriba y abajo la hora y el clima.
                val bottom = if (rows >= 6) 2 else 1
                val half = cols / 2
                place(TileTypes.SPEEDOMETER, 0, 0, cols, rows - bottom, plain = true)
                place(TileTypes.CLOCK, 0, rows - bottom, half, bottom)
                place(TileTypes.WEATHER, half, rows - bottom, cols - half, bottom)
            } else {
                val top = (rows + 1) / 2
                place(TileTypes.CLOCK, 0, 0, side, rows, plain = true)
                place(TileTypes.SPEEDOMETER, side, 0, cols - 2 * side, rows, plain = true)
                place(TileTypes.WEATHER, cols - side, 0, side, top)
                place(TileTypes.TRIP, cols - side, top, side, rows - top)
            }
        }
        Template.WALL_CARDS -> {
            // Arriba queda libre para el fondo; la hora flota sin tarjeta.
            val bottom = if (rows >= 6) 2 else 1
            place(TileTypes.CLOCK, 0, 0, maxOf(2, cols / 2), if (rows >= 5) 2 else 1, plain = true)
            val music = maxOf(3, cols * 4 / 10).coerceAtMost(cols)
            val rest = cols - music
            // Mapa (o radio en una fila) y clima; si no caben los dos, solo el clima.
            val first = if (rest >= 5) maxOf(3, rest / 2) else 0
            mapOr(TileTypes.RADIO, 0, rows - bottom, first, bottom)
            place(TileTypes.MUSIC, first, rows - bottom, music, bottom)
            place(TileTypes.WEATHER, first + music, rows - bottom, rest - first, bottom)
        }
        Template.WALL_SIDE -> {
            val width = maxOf(3, cols / 3)
            val start = cols - width
            val clock = if (rows >= 5) 2 else 1
            place(TileTypes.CLOCK, start, 0, width, clock, plain = true)
            val rest = rows - clock
            // Con 4 filas libres cabe el mapa (2) bajo la música (2); si no, la radio en una fila.
            val weather = if (rest >= 5 || rest == 3) 1 else 0
            if (weather > 0) place(TileTypes.WEATHER, start, clock, width, 1)
            val music = if (rest >= 4) 2 else 1
            place(TileTypes.MUSIC, start, clock + weather, width, music)
            val row = clock + weather + music
            mapOr(TileTypes.RADIO, start, row, width, rows - row)
        }
        Template.MAP_BACKGROUND -> {
            if (c.vertical) {
                // Parada: abajo, la música a lo ancho y debajo velocidad y hora; arriba, el mapa.
                val music = if (rows >= 8) 2 else 1
                val gauges = if (rows >= 6) 2 else 1
                val top = rows - music - gauges
                place(TileTypes.MUSIC, 0, top, cols, music)
                val half = cols / 2
                place(TileTypes.SPEEDOMETER, 0, top + music, half, gauges)
                place(TileTypes.CLOCK, half, top + music, cols - half, gauges)
            } else {
                // Acostada: columna de un tercio a la derecha con la música, la velocidad y la hora, y el clima si cabe.
                val width = maxOf(3, cols / 3)
                val start = cols - width
                val music = if (rows >= 5) 2 else 1
                place(TileTypes.MUSIC, start, 0, width, music)
                val gauges = minOf(2, rows - music)
                val half = width / 2
                place(TileTypes.SPEEDOMETER, start, music, half, gauges)
                place(TileTypes.CLOCK, start + half, music, width - half, gauges)
                val row = music + gauges
                if (rows - row >= 1) place(TileTypes.WEATHER, start, row, width, minOf(2, rows - row))
            }
        }
        Template.DESIGN_GRAPHITE -> when {
            c.vertical -> {
                val m = rnd(rows * 0.55)
                mapOr(TileTypes.CAR, 0, 0, cols, m)
                val music = if (rows >= 12) 2 else 1
                place(TileTypes.MUSIC, 0, m, cols, music)
                val y = m + music
                val h = rows - m - music
                val s = (cols + 1).floorDiv(2)
                val top = (h + 1).floorDiv(2)
                firstFree(STACK_DRIVE, 0, y, s, h)
                place(TileTypes.SPEEDOMETER, s, y, cols - s, top)
                place(TileTypes.WEATHER, s, y + top, cols - s, h - top)
            }
            ultra -> {
                val m = rnd(cols * 0.55)
                val s = rnd((cols - m) / 2.0)
                val top = (rows + 1).floorDiv(2)
                mapOr(TileTypes.CAR, 0, 0, m, rows)
                firstFree(STACK_CAR, m, 0, s, rows)
                place(TileTypes.SPEEDOMETER, m + s, 0, cols - m - s, top)
                place(TileTypes.WEATHER, m + s, top, cols - m - s, rows - top)
            }
            else -> {
                val side = maxOf(3, rnd(cols * 0.32))
                val m = cols - side
                val a = up(rows * 0.45)
                val b = maxOf(1, rnd(rows * 0.27))
                mapOr(TileTypes.CAR, 0, 0, m, rows)
                firstFree(STACK_CAR, m, 0, side, a)
                place(TileTypes.SPEEDOMETER, m, a, side, b)
                place(TileTypes.WEATHER, m, a + b, side, rows - a - b)
            }
        }
        Template.DESIGN_CLOUD -> when {
            c.vertical -> {
                val a = rnd(rows * 0.36)
                place(TileTypes.CAR, 0, 0, cols, a)
                mapOr(TileTypes.CLOCK, 0, a, cols, a)
                val s = rnd(cols * 0.57)
                place(TileTypes.MUSIC, 0, 2 * a, s, rows - 2 * a)
                place(TileTypes.WEATHER, s, 2 * a, cols - s, rows - 2 * a)
            }
            ultra -> {
                val a = rnd(cols * 0.33)
                val b = rnd(cols * 0.4)
                val music = up(rows * 0.6)
                place(TileTypes.CAR, 0, 0, a, rows)
                mapOr(TileTypes.CLOCK, a, 0, b, rows)
                place(TileTypes.MUSIC, a + b, 0, cols - a - b, music)
                place(TileTypes.WEATHER, a + b, music, cols - a - b, rows - music)
            }
            else -> {
                val h = rows - strip
                val half = cols / 2
                val music = rnd(cols * 0.6)
                place(TileTypes.CAR, 0, 0, half, h)
                mapOr(TileTypes.CLOCK, half, 0, cols - half, h)
                place(TileTypes.MUSIC, 0, h, music, strip)
                place(TileTypes.WEATHER, music, h, cols - music, strip)
            }
        }
        Template.DESIGN_TRACK -> {
            val gauge = "style=needle;color=red;numbers=1;redline=1"
            if (c.vertical) {
                val s = rnd(rows * 0.45)
                place(TileTypes.SPEEDOMETER, 0, 0, cols, s, gauge, plain = true)
                place(TileTypes.ROAD, 0, s, cols, 3, ROAD_NO_SPEED)
                val y = s + 3
                val h = rows - y
                val m = rnd(cols * 0.57)
                val music = maxOf(1, h - 1)
                mapOr(TileTypes.MUSIC, 0, y, m, h)
                place(TileTypes.MUSIC, m, y, cols - m, music)
                place(tripType, m, y + music, cols - m, h - music, tripData)
            } else {
                val side = maxOf(3, rnd(cols * 0.3))
                val mid = cols - 2 * side
                val low = if (rows >= 5) 2 else 1
                place(TileTypes.ROAD, 0, 0, side, rows - low, ROAD_NO_SPEED)
                place(tripType, 0, rows - low, side, low, tripData)
                place(TileTypes.SPEEDOMETER, side, 0, mid, rows, gauge, plain = true)
                mapOr(TileTypes.CLOCK, side + mid, 0, side, rows - low)
                place(TileTypes.MUSIC, side + mid, rows - low, side, low)
            }
        }
        Template.DESIGN_ROUTE -> {
            // El mapa es el fondo: el hueco libre más grande ([freeArea]) es donde vas tú y la guía pone sus tarjetas.
            if (c.vertical) {
                // Tu auto, grande: más de la mitad del ancho.
                val s = rnd(cols * 0.55)
                val y = rows - 4
                firstFree(STACK_DRIVE, 0, y, s, 4)
                place(TileTypes.WEATHER, s, y, cols - s, 2)
                place(TileTypes.MUSIC, s, y + 2, cols - s, 1)
                place(TileTypes.SPEEDOMETER, s, y + 3, cols - s, 1, "style=digital")
            } else {
                // Tu auto, grande: casi la mitad del ancho y todo el alto menos la fila de la música.
                val s = maxOf(4, rnd(cols * 0.45))
                firstFree(STACK_DRIVE, 0, 0, s, rows - 1)
                place(TileTypes.MUSIC, 0, rows - 1, maxOf(3, rnd(cols * 0.42)), 1)
            }
        }
        Template.DESIGN_COLUMN -> when {
            c.vertical -> {
                place(TileTypes.CAR, 0, 0, cols, 3)
                mapOr(TileTypes.CLOCK, 0, 3, cols, 4)
                place(TileTypes.MUSIC, 0, 7, cols, 2)
                val s = if (cols >= 6) (cols + 1).floorDiv(2) - 1 else cols / 2
                place(TileTypes.SPEEDOMETER, 0, 9, s, rows - 9)
                place(TileTypes.WEATHER, s, 9, cols - s, rows - 9)
            }
            ultra -> {
                val w = rnd(cols * 0.3)
                val cw = rnd(cols * 0.47)
                val top = (rows + 1).floorDiv(2)
                place(TileTypes.MUSIC, 0, 0, w, 2)
                mapOr(TileTypes.CLOCK, 0, 2, w, rows - 2)
                place(TileTypes.CAR, w, 0, cw, rows)
                place(TileTypes.SPEEDOMETER, w + cw, 0, cols - w - cw, top)
                place(TileTypes.WEATHER, w + cw, top, cols - w - cw, rows - top)
            }
            else -> {
                val w = maxOf(4, rnd(cols * 0.36))
                val weather = if (rows >= 6) 2 else 1
                place(TileTypes.MUSIC, 0, 0, w, 2)
                mapOr(TileTypes.CLOCK, 0, 2, w, rows - 2 - weather)
                place(TileTypes.WEATHER, 0, rows - weather, w, weather)
                place(TileTypes.CAR, w, 0, cols - w, rows)
            }
        }
        Template.DESIGN_NEON -> {
            val gauge = "rim=neon;color=cyan"
            if (c.vertical) {
                place(TileTypes.CLOCK, 0, 0, cols, 2, plain = true)
                place(TileTypes.SPEEDOMETER, 0, 2, cols, 4, gauge, plain = true)
                mapOr(TileTypes.WEATHER, 0, 6, cols, rows - 8)
                val s = rnd(cols * 0.57)
                place(TileTypes.MUSIC, 0, rows - 2, s, 2)
                place(TileTypes.WEATHER, s, rows - 2, cols - s, 2)
            } else {
                val side = maxOf(3, rnd(cols * 0.3))
                val mid = cols - 2 * side
                val top = if (rows >= 5) 2 else 1
                val low = if (rows >= 6) 2 else 1
                place(TileTypes.CLOCK, 0, 0, side, top, plain = true)
                place(TileTypes.MUSIC, 0, top, side, rows - top)
                place(TileTypes.SPEEDOMETER, side, 0, mid, rows, gauge, plain = true)
                mapOr(TileTypes.RADIO, side + mid, 0, side, rows - low)
                place(TileTypes.WEATHER, side + mid, rows - low, side, low)
            }
        }
        Template.DESIGN_SAND -> {
            if (c.vertical) {
                place(TileTypes.CLOCK, 0, 0, cols, 2, plain = true)
                place(TileTypes.CAR, 0, 2, cols, 4)
                place(TileTypes.MUSIC, 0, 6, cols, 2)
                val w = maxOf(3, cols - 4)
                place(TileTypes.WEATHER, 0, 8, w, rows - 8)
                mapOr(tripType, w, 8, cols - w, rows - 8, tripData)
            } else {
                val side = maxOf(3, rnd(cols * 0.28))
                val mid = cols - 2 * side
                val top = if (rows >= 5) 2 else 1
                val low = if (rows >= 5) 2 else 1
                place(TileTypes.CLOCK, 0, 0, side, top, plain = true)
                place(TileTypes.WEATHER, 0, top, side, minOf(2, rows - top))
                if (rows - top - 2 >= 1) place(tripType, 0, top + 2, side, rows - top - 2, tripData)
                place(TileTypes.CAR, side, 0, mid, rows - low)
                place(TileTypes.MUSIC, side, rows - low, mid, low)
                mapOr(TileTypes.RADIO, side + mid, 0, cols - side - mid, rows)
            }
        }
        Template.DESIGN_FOREST -> when {
            c.vertical -> {
                mapOr(TileTypes.CLOCK, 0, 0, cols, 5)
                place(TileTypes.CAR, 0, 5, cols, 3)
                val s = rnd(cols * 0.57)
                firstFree(STACK_AUDIO, 0, 8, s, rows - 8)
                place(TileTypes.WEATHER, s, 8, cols - s, rows - 8)
            }
            ultra -> {
                val m = rnd(cols * 0.47)
                val a = rnd(cols * 0.32)
                val top = up(rows * 0.66)
                mapOr(TileTypes.CLOCK, 0, 0, m, rows)
                place(TileTypes.CAR, m, 0, a, rows)
                firstFree(STACK_AUDIO, m + a, 0, cols - m - a, top)
                place(TileTypes.WEATHER, m + a, top, cols - m - a, rows - top)
            }
            else -> {
                val m = rnd(cols * 0.5)
                val rest = cols - m
                val s = maxOf(3, (rest + 1).floorDiv(2))
                mapOr(TileTypes.CLOCK, 0, 0, m, rows)
                place(TileTypes.CAR, m, 0, rest, rows - strip)
                firstFree(STACK_AUDIO, m, rows - strip, s, strip)
                place(TileTypes.WEATHER, m + s, rows - strip, rest - s, strip)
            }
        }
        Template.MAP_CAR -> {
            // Del lado del conductor el auto y la música; el resto (~55 %), el mapa.
            val side = maxOf(3, cols - (cols * 0.55f + 0.5f).toInt())
            if (cols - side < 3) carOverMusic()
            else {
                val music = if (rows >= 6) 2 else 1
                place(TileTypes.CAR, 0, 0, side, rows - music)
                place(TileTypes.MUSIC, 0, rows - music, side, music)
                mapOr(null, side, 0, cols - side, rows)
            }
        }
        Template.COPILOT -> {
            val side = maxOf(3, (cols * 0.3f + 0.5f).toInt())
            if (cols - 2 * side < 3) carOverMusic()
            else {
                mapOr(null, 0, 0, side, rows)
                place(TileTypes.CAR, side, 0, cols - 2 * side, rows)
                musicAndRadio(cols - side, 0, side, rows)
            }
        }
        Template.ROUTE -> {
            // Columna angosta del lado del conductor: velocidad arriba y música abajo.
            val side = maxOf(3, (cols * 0.3f + 0.5f).toInt())
            val music = if (rows >= 6) 2 else 1
            place(TileTypes.SPEEDOMETER, 0, 0, side, rows - music)
            place(TileTypes.MUSIC, 0, rows - music, side, music)
            mapOr(null, side, 0, cols - side, rows)
        }
        Template.VERTICAL_MAP -> {
            val mapHeight = rows / 2
            mapOr(null, 0, 0, cols, mapHeight)
            val half = cols / 2
            place(TileTypes.CAR, 0, mapHeight, half, rows - mapHeight)
            musicAndRadio(half, mapHeight, cols - half, rows - mapHeight)
        }
    }
    return list
}

// Lo que va donde los Diseños de la 1.5.20–1.5.22 ponían una Pila (sus caras, en orden): el primero que se pueda.
private val STACK_CAR = listOf(TileTypes.MUSIC, TileTypes.CAR, TileTypes.TIRES)
private val STACK_DRIVE = listOf(TileTypes.CAR, TileTypes.TIRES, TileTypes.MUSIC)
private val STACK_AUDIO = listOf(TileTypes.MUSIC, TileTypes.RADIO)
/** Tu auto en la carretera sin su velocidad (va junto a un velocímetro): lo que guarda la pieza. */
private const val ROAD_NO_SPEED = "speed=hidden"
private val FREE_FALLBACK = listOf(TileTypes.CLOCK, TileTypes.WEATHER)

/**
 * El acomodo que mejor luce en esta pantalla (en celdas de plantilla: [Grid.coarse]). El auto 3D sigue de protagonista al
 * estrenar; en las medianas, grandes y paradas el sugerido ya trae el mapa de 2 filas.
 */
fun Grid.suggestedTemplate() = when (size) {
    ScreenSize.SMALL -> Template.CENTER
    ScreenSize.MEDIUM -> Template.CARDS
    ScreenSize.LARGE -> Template.PANEL
    ScreenSize.VERTICAL -> Template.VERTICAL
}

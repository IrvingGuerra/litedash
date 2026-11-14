package com.irvingguerra.litedash.feature.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Los nombres de las calles del mapa minimalista, como en Waze: derechos aunque el mapa esté inclinado, sobre el tramo
 * recto más largo de cada calle, sin encimarse. Sin Android, para probarlo con JUnit.
 */

/**
 * Dónde puede ir el nombre de una calle: su tramo recto más largo, de ([x0], [y0]) a ([x1], [y1]) en coordenadas de
 * tesela del zoom del mapa. [width] = lo que mide el texto en pantalla (px).
 */
class LabelRun(val name: String, val tier: RoadTier, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val width: Float)

/** Un nombre ya acomodado: centro en pantalla, giro (grados, siempre legible) y lo que mide. */
class PlacedLabel(val name: String, val x: Float, val y: Float, val degrees: Float, val width: Float)

/**
 * Los tramos de [tiles] (tesela origen → sus nombres) donde caben los nombres, en el zoom [z] con teselas de [tilePx]
 * en pantalla ([dp] = px por dp). [measure] = lo que mide un texto (px). Un tramo por calle de cada tesela: el más largo.
 */
fun labelRuns(tiles: Map<Long, TileNames>, z: Int, tilePx: Float, dp: Float, measure: (String) -> Float): List<LabelRun> {
    val out = ArrayList<LabelRun>()
    for ((origin, names) in tiles) {
        // De unidades de la tesela origen a teselas del zoom del mapa.
        val scale = Math.pow(2.0, (z - keyZ(origin)).toDouble())
        val ox = keyX(origin) * scale
        val oy = keyY(origin) * scale
        val unit = scale / names.extent
        for (road in names.roads) {
            val width = measure(road.name)
            val best = longestStraight(road.shape) ?: continue
            val (a, b) = best
            val pts = road.shape.points
            val x0 = ox + pts[a * 2] * unit
            val y0 = oy + pts[a * 2 + 1] * unit
            val x1 = ox + pts[b * 2] * unit
            val y1 = oy + pts[b * 2 + 1] * unit
            // Que quepa el texto con aire; lo que no, ni se intenta.
            if (hypot(x1 - x0, y1 - y0) * tilePx < width + LABEL_PAD * 2 * dp) continue
            out += LabelRun(road.name, road.tier, x0, y0, x1, y1, width)
        }
    }
    // Las grandes primero; entre iguales, las de tramo más largo.
    out.sortWith(compareBy<LabelRun> { it.tier.ordinal }.thenByDescending { hypot(it.x1 - it.x0, it.y1 - it.y0) })
    return out
}

/**
 * El tramo casi recto más largo de [shape] (índices del primer y último punto): se alarga mientras la dirección no se
 * aparte más de [MAX_BEND] grados de la del primer pedazo. null si no tiene ni un pedazo.
 */
internal fun longestStraight(shape: Shape): Pair<Int, Int>? {
    val p = shape.points
    var best: Pair<Int, Int>? = null
    var bestLength = 0.0
    val bend = MAX_BEND * PI / 180
    for (part in shape.parts.indices) {
        val start = shape.parts[part]
        val end = (if (part + 1 < shape.parts.size) shape.parts[part + 1] else p.size / 2) - 1
        var i = start
        while (i < end) {
            // Un pedazo de largo cero no tiene dirección: se salta.
            if (p[i * 2 + 2] == p[i * 2] && p[i * 2 + 3] == p[i * 2 + 1]) {
                i++
                continue
            }
            val dir = atan2((p[i * 2 + 3] - p[i * 2 + 1]).toDouble(), (p[i * 2 + 2] - p[i * 2]).toDouble())
            var j = i + 1
            while (j < end) {
                if (p[j * 2 + 2] == p[j * 2] && p[j * 2 + 3] == p[j * 2 + 1]) {
                    j++
                    continue
                }
                val next = atan2((p[j * 2 + 3] - p[j * 2 + 1]).toDouble(), (p[j * 2 + 2] - p[j * 2]).toDouble())
                if (abs(angleBetween(dir, next)) > bend) break
                j++
            }
            val length = hypot((p[j * 2] - p[i * 2]).toDouble(), (p[j * 2 + 1] - p[i * 2 + 1]).toDouble())
            if (length > bestLength) {
                bestLength = length
                best = i to j
            }
            i = j
        }
    }
    return best
}

/** Diferencia de dos ángulos (radianes) entre −π y π. */
private fun angleBetween(a: Double, b: Double): Double {
    var d = b - a
    while (d > PI) d -= 2 * PI
    while (d < -PI) d += 2 * PI
    return d
}

/**
 * Acomoda los nombres de [runs] en una vista de [width] × [height] px (nada arriba de [top]: el cielo del mapa
 * inclinado). [toScreen] lleva un punto en teselas del zoom del mapa a la pantalla (false = detrás de la cámara);
 * [textHeight] = alto del texto y [dp] = px por dp. A lo más [max] nombres, sin encimarse ni repetir uno cerca de otro igual.
 */
fun placeLabels(
    runs: List<LabelRun>,
    width: Float,
    height: Float,
    top: Float,
    textHeight: Float,
    dp: Float,
    max: Int,
    toScreen: (Double, Double, FloatArray) -> Boolean,
): List<PlacedLabel> {
    val placed = ArrayList<PlacedLabel>()
    val boxes = ArrayList<FloatArray>()
    val a = FloatArray(3)
    val b = FloatArray(3)
    for (run in runs) {
        if (placed.size >= max) break
        if (!toScreen(run.x0, run.y0, a) || !toScreen(run.x1, run.y1, b)) continue
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val length = sqrt(dx * dx + dy * dy)
        // Inclinado, lo lejano se achica: si ya no cabe ahí, no va.
        if (length < run.width + LABEL_PAD * 2 * dp) continue
        val cx = (a[0] + b[0]) / 2
        val cy = (a[1] + b[1]) / 2
        var degrees = (atan2(dy, dx) * 180 / PI).toFloat()
        // Siempre de izquierda a derecha (nunca de cabeza).
        if (degrees > 90f) degrees -= 180f else if (degrees < -90f) degrees += 180f
        // El rectángulo girado, en su caja derecha (con poco aire: las calles van juntas).
        val r = degrees * PI / 180
        val c = abs(cos(r)).toFloat()
        val s = abs(kotlin.math.sin(r)).toFloat()
        val halfW = (run.width * c + textHeight * s) / 2 + LABEL_GAP * dp
        val halfH = (run.width * s + textHeight * c) / 2 + LABEL_GAP * dp
        val box = floatArrayOf(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        // Completo o nada: un nombre cortado por la orilla se ve roto.
        if (box[0] < 0f || box[2] > width || box[1] < top || box[3] > height) continue
        if (boxes.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
        if (placed.any { it.name == run.name && hypot(it.x - cx, it.y - cy) < SAME_NAME_GAP * dp }) continue
        boxes += box
        placed += PlacedLabel(run.name, cx, cy, degrees, run.width)
    }
    return placed
}

/**
 * La calle donde estás: la de [names] (de la tesela origen [origin]) más cerca de ([lat], [lon]), si está a menos de
 * [MAX_STREET_METERS]. null si no hay ninguna tan cerca.
 */
fun nearestStreet(origin: Long, names: TileNames, lat: Double, lon: Double): String? {
    val z = keyZ(origin)
    // Tu posición en unidades de la tesela.
    val px = (tileX(lon, z) - keyX(origin)) * names.extent
    val py = (tileY(lat, z) - keyY(origin)) * names.extent
    val metersPerUnit = EARTH_METERS * cos(Math.toRadians(lat)) / (1 shl z) / names.extent
    var best: String? = null
    var bestDistance = MAX_STREET_METERS / metersPerUnit
    for (road in names.roads) {
        val p = road.shape.points
        val parts = road.shape.parts
        for (part in parts.indices) {
            val start = parts[part]
            val end = (if (part + 1 < parts.size) parts[part + 1] else p.size / 2) - 1
            for (i in start until end) {
                val d = segmentDistance(px, py, p[i * 2].toDouble(), p[i * 2 + 1].toDouble(), p[i * 2 + 2].toDouble(), p[i * 2 + 3].toDouble())
                if (d < bestDistance) {
                    bestDistance = d
                    best = road.name
                }
            }
        }
    }
    return best
}

/** Distancia del punto ([px], [py]) al segmento de ([ax], [ay]) a ([bx], [by]). */
private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val vx = bx - ax
    val vy = by - ay
    val length = vx * vx + vy * vy
    val t = if (length == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / length).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * vx), py - (ay + t * vy))
}

/** Lo que más se dobla un tramo para llevar nombre (grados). */
private const val MAX_BEND = 18.0

/** Aire a cada lado del texto sobre su tramo (dp). */
private const val LABEL_PAD = 8f

/** Aire entre dos nombres (dp). */
private const val LABEL_GAP = 3f

/** Dos nombres iguales no van más cerca que esto (dp). */
private const val SAME_NAME_GAP = 160f

/** Más lejos que esto de cualquier calle con nombre, no se dice en cuál estás. */
private const val MAX_STREET_METERS = 30.0

/** El ecuador en metros (Web Mercator). */
private const val EARTH_METERS = 40_075_016.686

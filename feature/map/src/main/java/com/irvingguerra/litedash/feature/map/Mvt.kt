package com.irvingguerra.litedash.feature.map

/**
 * Lo poco que el mapa minimalista necesita de una tesela vectorial (formato Mapbox Vector Tile,
 * esquema OpenMapTiles): agua y parques (áreas), ríos (líneas) y calles por tipo. Todo lo demás
 * (edificios, negocios, números de casa…) se salta sin leerlo. Coordenadas en unidades de [extent].
 */
class VectorTile(
    val extent: Int,
    val water: List<Shape>,
    val parks: List<Shape>,
    val rivers: List<Shape>,
    /** Por [RoadTier.ordinal]. */
    val roads: List<List<Shape>>,
)

/** Una línea o un contorno: x, y, x, y… ([parts] = en qué punto empieza cada pedazo). */
class Shape(val points: FloatArray, val parts: IntArray)

/** De la autopista a las de servicio; el orden es el de dibujo inverso (las grandes van encima). */
enum class RoadTier { MOTORWAY, PRIMARY, SECONDARY, MINOR, SERVICE }

/** El tipo de calle de OpenMapTiles (`class`) en su grupo; null = no se dibuja (veredas, vías del tren…). */
fun roadTier(osmClass: String?): RoadTier? = when (osmClass) {
    "motorway", "trunk" -> RoadTier.MOTORWAY
    "primary" -> RoadTier.PRIMARY
    "secondary", "tertiary" -> RoadTier.SECONDARY
    "minor" -> RoadTier.MINOR
    "service" -> RoadTier.SERVICE
    else -> null
}

/** Lee lo que se usa de [data] (ya sin gzip). Una tesela dañada da lo que se alcanzó a leer. */
fun parseVectorTile(data: ByteArray): VectorTile {
    val water = ArrayList<Shape>()
    val parks = ArrayList<Shape>()
    val rivers = ArrayList<Shape>()
    val roads = List(RoadTier.entries.size) { ArrayList<Shape>() }
    var extent = 4096
    val tile = Pbf(data)
    runCatching {
        while (tile.more()) {
            val tag = tile.tag()
            if (tag != LAYER) {
                tile.skip(tag)
                continue
            }
            val layer = readLayer(tile.message()) ?: continue
            extent = layer.extent
            when (layer.name) {
                "water" -> layer.features.forEach { f -> if (f.type == POLYGON && f.value(layer, "class") != "swimming_pool") water += f.shape() }
                // Los parques de la ciudad y los bosques (la capa "park" son zonas protegidas o históricas
                // enteras: el Centro Histórico salía todo verde).
                "landcover" -> layer.features.forEach { f ->
                    if (f.type == POLYGON && (f.value(layer, "subclass") == "park" || f.value(layer, "class") == "wood")) parks += f.shape()
                }
                "waterway" -> layer.features.forEach { f -> if (f.type == LINE && f.value(layer, "class") in RIVERS) rivers += f.shape() }
                "transportation" -> layer.features.forEach { f ->
                    if (f.type != LINE) return@forEach
                    roadTier(f.value(layer, "class"))?.let { roads[it.ordinal] += f.shape() }
                }
            }
        }
    }
    return VectorTile(extent, water, parks, rivers, roads)
}

private val USED_LAYERS = setOf("water", "landcover", "waterway", "transportation")
private val RIVERS = setOf("river", "canal")

private class Layer(val name: String, val extent: Int, val keys: List<String>, val values: List<String?>, val features: List<Feature>)

private class Feature(val type: Int, val tags: Pbf?, val geometry: Pbf?) {
    /** El valor de texto de la etiqueta [key] (solo hace falta `class`). */
    fun value(layer: Layer, key: String): String? {
        val k = layer.keys.indexOf(key)
        val t = tags?.copy() ?: return null
        while (t.more()) {
            val ki = t.varint().toInt()
            val vi = t.varint().toInt()
            if (ki == k) return layer.values.getOrNull(vi)
        }
        return null
    }

    /** Los comandos de la geometría (MoveTo, LineTo, ClosePath) a puntos absolutos. */
    fun shape(): Shape {
        val g = geometry?.copy() ?: return Shape(FloatArray(0), IntArray(0))
        var points = FloatArray(64)
        var n = 0
        val parts = ArrayList<Int>()
        var x = 0
        var y = 0
        while (g.more()) {
            val command = g.varint().toInt()
            val id = command and 7
            val count = command ushr 3
            if (id == CLOSE) continue
            repeat(count) {
                x += zigzag(g.varint())
                y += zigzag(g.varint())
                if (id == MOVE_TO) parts += n / 2
                if (n + 2 > points.size) points = points.copyOf(points.size * 2)
                points[n++] = x.toFloat()
                points[n++] = y.toFloat()
            }
        }
        return Shape(points.copyOf(n), parts.toIntArray())
    }
}

private fun readLayer(p: Pbf): Layer? {
    var name = ""
    var extent = 4096
    val keys = ArrayList<String>()
    val values = ArrayList<String?>()
    val features = ArrayList<Pbf>()
    while (p.more()) {
        when (val tag = p.tag()) {
            NAME -> name = p.string()
            FEATURE -> features += p.message()
            KEY -> keys += p.string()
            VALUE -> values += readValue(p.message())
            EXTENT -> extent = p.varint().toInt()
            else -> p.skip(tag)
        }
        // Las capas que no se usan no se leen (son casi toda la tesela: edificios, negocios…).
        if (name.isNotEmpty() && name !in USED_LAYERS) return null
    }
    return Layer(name, extent, keys, values, features.map(::readFeature))
}

private fun readFeature(p: Pbf): Feature {
    var type = 0
    var tags: Pbf? = null
    var geometry: Pbf? = null
    while (p.more()) {
        when (val tag = p.tag()) {
            TAGS -> tags = p.message()
            TYPE -> type = p.varint().toInt()
            GEOMETRY -> geometry = p.message()
            else -> p.skip(tag)
        }
    }
    return Feature(type, tags, geometry)
}

/** Solo los valores de texto (los números de las etiquetas no se usan). */
private fun readValue(p: Pbf): String? {
    while (p.more()) {
        val tag = p.tag()
        if (tag == STRING_VALUE) return p.string()
        p.skip(tag)
    }
    return null
}

private fun zigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()

/** Lector mínimo de protobuf sobre una parte de [b]. */
internal class Pbf(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    fun more() = pos < end
    fun copy() = Pbf(b, pos, end)

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val x = b[pos++].toInt()
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
    }

    fun tag(): Int = varint().toInt()

    fun message(): Pbf {
        val length = varint().toInt()
        return Pbf(b, pos, pos + length).also { pos += length }
    }

    fun string(): String {
        val length = varint().toInt()
        return String(b, pos, length, Charsets.UTF_8).also { pos += length }
    }

    fun skip(tag: Int) {
        when (tag and 7) {
            0 -> varint()
            1 -> pos += 8
            2 -> pos += varint().toInt()
            5 -> pos += 4
            else -> pos = end // desconocido: lo que queda no se puede leer
        }
    }
}

// Campos del formato (número de campo << 3 | tipo de dato).
private const val LAYER = 3 shl 3 or 2
private const val NAME = 1 shl 3 or 2
private const val FEATURE = 2 shl 3 or 2
private const val KEY = 3 shl 3 or 2
private const val VALUE = 4 shl 3 or 2
private const val EXTENT = 5 shl 3 or 0
private const val TAGS = 2 shl 3 or 2
private const val TYPE = 3 shl 3 or 0
private const val GEOMETRY = 4 shl 3 or 2
private const val STRING_VALUE = 1 shl 3 or 2

private const val LINE = 2
private const val POLYGON = 3
private const val MOVE_TO = 1
private const val CLOSE = 7

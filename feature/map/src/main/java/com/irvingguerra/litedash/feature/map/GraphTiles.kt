package com.irvingguerra.litedash.feature.map

/**
 * Las calles de una tesela sacadas del archivo de ruta (mapas del formato nuevo: el `.pmtiles` ya no las trae, MP2-D2),
 * en la misma forma que las de una tesela vectorial ([VectorTile]): así se dibujan igual, con la misma paleta, grosores y
 * nombres. Del zoom 13 en adelante, todos los tramos (y las calles que solo se dibujan: privadas, estacionamientos); del 12
 * para abajo, la vista lejana (principales simplificadas). Sin Android, para probarlo con JUnit.
 */
internal object GraphTiles {
    /** Del zoom de la vista lejana para abajo, solo esa. */
    const val FAR_ZOOM = 12
    /** Desde este zoom, solo autopistas, troncales y primarias (lo demás se vuelve ruido). */
    private const val MAJOR_ONLY_ZOOM = 9
    /** Las calles de servicio no se dibujan en el 13 (en las teselas vectoriales tampoco). */
    private const val SERVICE_FROM_ZOOM = 14
    /** Lo que se busca de más alrededor de la tesela (fracción de su lado): el grosor de las calles en la orilla. */
    private const val MARGIN = 0.03
    /** Las calles de un tipo se agrupan en un cuadriculado de la tesela (menos trazos, y al dibujar un pedazo se saltan). */
    private const val BINS = 8

    /** Las calles (por tipo, su sentido y, en el zoom 14, sus nombres) de la tesela [origin] con lado [extent]. */
    fun roads(graph: RoadGraph, origin: Long, extent: Int): Roads {
        val z = keyZ(origin)
        val tx = keyX(origin)
        val ty = keyY(origin)
        val build = Builder(z, tx, ty, extent, names = z == DATA_ZOOM)
        val north = tileLat(ty.toDouble() - MARGIN, z)
        val south = tileLat(ty + 1 + MARGIN, z)
        val west = tileLon(tx.toDouble() - MARGIN, z)
        val east = tileLon(tx + 1 + MARGIN, z)
        val line = DrawLine()
        val pts = PointList()
        if (z <= FAR_ZOOM) {
            val seen = HashSet<Int>()
            forEachCell(south, north, west, east, RoadGraph.FAR_CELL_DEG) { cell ->
                graph.forEachListed(RoadGraph.FAR, cell) { off ->
                    if (!seen.add(off)) return@forEachListed
                    pts.clear()
                    graph.readLine(RoadGraph.FAR, off, pts, line)
                    if (z <= MAJOR_ONLY_ZOOM && !major(line.roadClass)) return@forEachListed
                    build.add(pts, line.roadClass, line.oneWay, "", -1, -1)
                }
            }
            return build.done()
        }
        val service = z >= SERVICE_FROM_ZOOM
        forEachEdge(graph, south, north, west, east) { h, from ->
            val e = graph.halfEdge(h)
            val cls = graph.roadClass(e)
            if (!service && cls == SERVICE_CLASS) return@forEachEdge
            val to = graph.halfTarget(h)
            pts.clear()
            pts.add(graph.latE6(from), graph.lonE6(from))
            graph.addShape(e, from, to, graph.halfReversed(h), pts)
            build.add(pts, cls, graph.oneWay(h), graph.name(e), from, to)
        }
        val r0 = cellOf(south, RoadGraph.CELL_DEG, 90.0)
        val r1 = cellOf(north, RoadGraph.CELL_DEG, 90.0)
        val c0 = cellOf(west, RoadGraph.CELL_DEG, 180.0)
        val c1 = cellOf(east, RoadGraph.CELL_DEG, 180.0)
        // Las que solo se dibujan.
        val extra = HashSet<Int>()
        for (r in r0..r1) for (c in c0..c1) graph.forEachListed(RoadGraph.EXTRA, r * 65536 + c) { off ->
            if (!extra.add(off)) return@forEachListed
            pts.clear()
            graph.readLine(RoadGraph.EXTRA, off, pts, line)
            if (!service && line.roadClass == SERVICE_CLASS) return@forEachListed
            build.add(pts, line.roadClass, line.oneWay, line.name, -1, -1)
        }
        return build.done()
    }

    /**
     * Cada tramo que toca las celdas de 0.01° de la caja dada, una sola vez: [action] recibe la mitad que lo recorre y el
     * cruce de donde sale. Los encuentra por sus cruces (lo pone el de origen; si ese queda fuera, el otro) y, los que
     * cruzan sin tener un cruce ahí, por el índice de tramos largos.
     */
    fun forEachEdge(graph: RoadGraph, south: Double, north: Double, west: Double, east: Double, action: (half: Int, from: Int) -> Unit) {
        val r0 = cellOf(south, RoadGraph.CELL_DEG, 90.0)
        val r1 = cellOf(north, RoadGraph.CELL_DEG, 90.0)
        val c0 = cellOf(west, RoadGraph.CELL_DEG, 180.0)
        val c1 = cellOf(east, RoadGraph.CELL_DEG, 180.0)
        fun inside(v: Int): Boolean {
            val r = cellOf(graph.latE6(v) / 1e6, RoadGraph.CELL_DEG, 90.0)
            val c = cellOf(graph.lonE6(v) / 1e6, RoadGraph.CELL_DEG, 180.0)
            return r in r0..r1 && c in c0..c1
        }
        for (r in r0..r1) for (c in c0..c1) {
            val (first, next) = graph.cellVertices(r * 65536 + c) ?: continue
            for (v in first until next) {
                for (h in graph.firstHalf(v) until graph.firstHalf(v + 1)) {
                    if (graph.halfReversed(h) && inside(graph.halfTarget(h))) continue
                    action(h, v)
                }
            }
        }
        val long = HashSet<Int>()
        for (r in r0..r1) for (c in c0..c1) graph.forEachListed(RoadGraph.LONG, r * 65536 + c) { h ->
            val from = graph.halfOrigin(h)
            if (!inside(from) && !inside(graph.halfTarget(h)) && long.add(h)) action(h, from)
        }
    }

    /** El tipo de dibujo de una clase del archivo de ruta (CLASSES de armar_mapa.py), como OpenMapTiles agrupa las suyas. */
    fun tierOf(roadClass: Int): RoadTier = when (roadClass) {
        0, 1, 8, 9 -> RoadTier.MOTORWAY
        2, 10 -> RoadTier.PRIMARY
        3, 4, 11, 12 -> RoadTier.SECONDARY
        SERVICE_CLASS -> RoadTier.SERVICE
        else -> RoadTier.MINOR
    }

    private const val SERVICE_CLASS = 14

    private fun major(roadClass: Int) = roadClass <= 2 || roadClass in 8..10

    private fun cellOf(deg: Double, cell: Double, shift: Double) = ((deg + shift) / cell).toInt()

    private inline fun forEachCell(south: Double, north: Double, west: Double, east: Double, cell: Double, action: (Int) -> Unit) {
        for (r in cellOf(south, cell, 90.0)..cellOf(north, cell, 90.0)) {
            for (c in cellOf(west, cell, 180.0)..cellOf(east, cell, 180.0)) action(r * 65536 + c)
        }
    }

    /** Las calles de una tesela: como las de [VectorTile] (por [RoadTier.ordinal]). */
    class Roads(val roads: List<List<Shape>>, val oneway: List<BooleanArray>, val names: List<RoadName>)

    /** Junta las líneas de una tesela en unidades de la tesela, agrupadas por tipo, sentido y casilla. */
    private class Builder(private val z: Int, private val tx: Int, private val ty: Int, private val extent: Int, private val names: Boolean) {
        /** (tipo, sentido, casilla) → puntos y dónde empieza cada pedazo. */
        private val groups = HashMap<Int, Group>()
        /** Nombre y tipo → sus tramos (para unirlos en calles enteras, como las de OpenMapTiles). */
        private val named = HashMap<String, MutableList<Piece>>()

        fun add(pts: PointList, roadClass: Int, oneWay: Boolean, name: String, from: Int, to: Int) {
            if (pts.size < 2) return
            val tier = tierOf(roadClass)
            val xy = FloatArray(pts.size * 2)
            for (i in 0 until pts.size) {
                xy[2 * i] = ((tileX(pts.lon(i) / 1e6, z) - tx) * extent).toFloat()
                xy[2 * i + 1] = ((tileY(pts.lat(i) / 1e6, z) - ty) * extent).toFloat()
            }
            val bx = (xy[0] / extent * BINS).toInt().coerceIn(0, BINS - 1)
            val by = (xy[1] / extent * BINS).toInt().coerceIn(0, BINS - 1)
            val key = ((tier.ordinal * 2 + if (oneWay) 1 else 0) * BINS + by) * BINS + bx
            groups.getOrPut(key) { Group() }.add(xy)
            if (names && name.isNotEmpty()) named.getOrPut("${tier.ordinal}\u0000$name") { ArrayList() } += Piece(xy, from, to)
        }

        fun done(): Roads {
            val roads = List(RoadTier.entries.size) { ArrayList<Shape>() }
            val oneway = List(RoadTier.entries.size) { ArrayList<Boolean>() }
            for ((key, g) in groups.entries.sortedBy { it.key }) {
                val tierAndWay = key / (BINS * BINS)
                roads[tierAndWay / 2] += g.shape()
                oneway[tierAndWay / 2] += tierAndWay % 2 == 1
            }
            val out = ArrayList<RoadName>()
            for ((key, pieces) in named) {
                val tier = RoadTier.entries[key.substringBefore('\u0000').toInt()]
                val name = key.substringAfter('\u0000')
                for (chain in chains(pieces)) out += RoadName(name, tier, Shape(chain, intArrayOf(0)))
            }
            return Roads(roads, oneway.map { it.toBooleanArray() }, out)
        }
    }

    private class Group {
        private var points = FloatArray(256)
        private var n = 0
        private val parts = ArrayList<Int>()

        fun add(xy: FloatArray) {
            if (n + xy.size > points.size) points = points.copyOf(maxOf(points.size * 2, n + xy.size))
            parts += n / 2
            xy.copyInto(points, n)
            n += xy.size
        }

        fun shape() = Shape(points.copyOf(n), parts.toIntArray())
    }

    /** Un tramo con nombre: sus puntos y sus cruces (-1 = sin cruce: se une por la punta). */
    private class Piece(val xy: FloatArray, val from: Int, val to: Int)

    /**
     * Une los tramos de una misma calle que se tocan en una punta (por cruce o, si no tienen, por el mismo punto) en calles
     * más largas: el nombre necesita un tramo recto largo, y una calle partida en cada esquina casi nunca lo tiene.
     */
    private fun chains(pieces: List<Piece>): List<FloatArray> {
        // Los cruces van en su propio rango de llaves (no chocan con las de un punto).
        fun startKey(p: Piece) = if (p.from >= 0) Long.MIN_VALUE + p.from else pointKey(p.xy[0], p.xy[1])
        fun endKey(p: Piece) = if (p.to >= 0) Long.MIN_VALUE + p.to else pointKey(p.xy[p.xy.size - 2], p.xy[p.xy.size - 1])
        val at = HashMap<Long, MutableList<Int>>()
        for ((i, p) in pieces.withIndex()) {
            at.getOrPut(startKey(p)) { ArrayList(2) } += i
            at.getOrPut(endKey(p)) { ArrayList(2) } += i
        }
        val used = BooleanArray(pieces.size)
        val out = ArrayList<FloatArray>()
        for (i in pieces.indices) {
            if (used[i]) continue
            used[i] = true
            // Hacia adelante desde el final del primero, luego hacia atrás desde su principio.
            val forward = ArrayList<FloatArray>()
            forward += pieces[i].xy
            var tail = endKey(pieces[i])
            while (true) {
                val j = at[tail]?.firstOrNull { !used[it] } ?: break
                used[j] = true
                val p = pieces[j]
                if (startKey(p) == tail) { forward += p.xy; tail = endKey(p) } else { forward += reversed(p.xy); tail = startKey(p) }
            }
            var head = startKey(pieces[i])
            val backward = ArrayList<FloatArray>()
            while (true) {
                val j = at[head]?.firstOrNull { !used[it] } ?: break
                used[j] = true
                val p = pieces[j]
                if (endKey(p) == head) { backward += p.xy; head = startKey(p) } else { backward += reversed(p.xy); head = endKey(p) }
            }
            backward.reverse()
            out += join(backward + forward)
        }
        return out
    }

    private fun pointKey(x: Float, y: Float): Long = (Math.round(x).toLong() shl 32) or (Math.round(y).toLong() and 0xFFFFFFFFL)

    private fun reversed(xy: FloatArray): FloatArray {
        val out = FloatArray(xy.size)
        for (i in 0 until xy.size / 2) {
            out[2 * i] = xy[xy.size - 2 - 2 * i]
            out[2 * i + 1] = xy[xy.size - 1 - 2 * i]
        }
        return out
    }

    /** Pega los tramos en una sola línea (sin repetir el punto donde se tocan). */
    private fun join(list: List<FloatArray>): FloatArray {
        var n = 0
        for ((k, xy) in list.withIndex()) n += if (k == 0) xy.size else xy.size - 2
        val out = FloatArray(n)
        var at = 0
        for ((k, xy) in list.withIndex()) {
            val skip = if (k == 0) 0 else 2
            xy.copyInto(out, at, skip)
            at += xy.size - skip
        }
        return out
    }
}

/** Esta tesela con las calles de [roads] (las del archivo de ruta) en vez de las suyas (que no trae). */
internal fun VectorTile.withRoads(roads: GraphTiles.Roads) =
    VectorTile(extent, water, parks, rivers, roads.roads, roads.names, roads.oneway)

package com.irvingguerra.litedash.feature.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Una ruta: sus puntos (pares lat, lon en millonésimas de grado, del punto de salida al de llegada), lo que mide, lo que
 * se tarda (sin tráfico) y la calle por donde va la mayor parte ([mainRoad], "" si no tiene nombre).
 */
class Route(
    val points: IntArray, val distanceM: Double, val seconds: Double, val mainRoad: String,
    /** Las vueltas en orden (la primera es la salida y la última la llegada). */
    val maneuvers: List<Maneuver> = emptyList(),
    /** Por punto: metros y segundos desde la salida (para el avance y la llegada). */
    val atM: DoubleArray = DoubleArray(0),
    val atS: DoubleArray = DoubleArray(0),
) {
    val pointCount get() = points.size / 2
}

/** Por qué no salió una ruta. */
enum class RouteError { FROM_OFF_ROAD, TO_OFF_ROAD, NO_PATH }

/**
 * Busca la ruta más rápida por las calles de [graph] (A*: el tiempo de cada tramo por su clase y velocidad máxima; respeta
 * sentidos y vueltas prohibidas u obligatorias; sin vueltas en U). Guarda sus arreglos entre búsquedas (una a la vez:
 * llamar desde un solo hilo, fuera del principal). Memoria: ~16 B por cruce (Edomex: ~10 MB).
 */
class Router(private val graph: RoadGraph) {
    private val n = graph.vertexCount
    private val cost = FloatArray(n)
    private val via = IntArray(n)
    private val prev = IntArray(n)
    private val seen = IntArray(n)
    private var round = 0
    private val heap = Heap()

    /** La ruta de ([fromLat], [fromLon]) a ([toLat], [toLon]), o por qué no la hay. */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Result<Route> = try {
        val a = graph.snap(fromLat, fromLon) ?: throw RouteException(RouteError.FROM_OFF_ROAD)
        val b = graph.snap(toLat, toLon) ?: throw RouteException(RouteError.TO_OFF_ROAD)
        search(a, b)?.let { Result.success(it) } ?: Result.failure(RouteException(RouteError.NO_PATH))
    } catch (e: RouteException) {
        Result.failure(e)
    } catch (_: RuntimeException) {
        // Un archivo dañado por dentro (pasó la revisión de la cabecera): sin ruta, sin tumbar la app.
        Result.failure(RouteException(RouteError.NO_PATH))
    }

    private fun search(a: Snap, b: Snap): Route? {
        if (++round == Int.MAX_VALUE) {
            seen.fill(0)
            round = 1
        }
        heap.clear()
        val aLen = graph.lengthM(a.edge)
        val bLen = graph.lengthM(b.edge)
        val aSpeed = speed(a.edge)
        val bSpeed = speed(b.edge)
        // Salidas: por el tramo de donde estás, hacia adelante (a su cruce de llegada) o, si se puede, hacia atrás.
        val back = twin(a)
        // Mismo tramo, en un sentido permitido: directo, sin pasar por cruces.
        if (a.edge == b.edge && a.from == b.from) {
            val forward = b.alongM >= a.alongM
            if (if (forward) graph.canLeave(a.half) else back >= 0 && graph.canLeave(back)) {
                val pts = PointList()
                pts.add(e6(a.lat), e6(a.lon))
                pts.add(e6(b.lat), e6(b.lon))
                val d = kotlin.math.abs(b.alongM - a.alongM)
                // Con su salida, su llegada y su avance, como cualquier ruta (la guía los usa).
                val name = graph.name(a.edge)
                return Route(
                    pts.toArray(), d, d / aSpeed, name,
                    listOf(Maneuver(ManeuverType.DEPART, 0f, name, 0, 0.0), Maneuver(ManeuverType.ARRIVE, 0f, "", 1, d)),
                    doubleArrayOf(0.0, d), doubleArrayOf(0.0, d / aSpeed),
                )
            }
        }
        if (graph.canLeave(a.half)) seed(a.to, ((aLen - a.alongM) / aSpeed).toFloat(), b)
        if (back >= 0 && graph.canLeave(back)) seed(a.from, (a.alongM / aSpeed).toFloat(), b)
        // Llegadas: al cruce de antes del destino sobre su tramo, más lo que falta (en el sentido permitido).
        val bBack = twin(b)
        val goalFrom = if (graph.canLeave(b.half)) (b.alongM / bSpeed).toFloat() else Float.NaN
        val goalTo = if (bBack >= 0 && graph.canLeave(bBack)) ((bLen - b.alongM) / bSpeed).toFloat() else Float.NaN
        var best = Float.MAX_VALUE
        var bestEnd = -1
        val toLat = b.lat
        val toLon = b.lon
        val kx = cos(Math.toRadians(toLat)) * RoadGraph.M_PER_DEG
        while (heap.size > 0) {
            val f = heap.topKey()
            val v = heap.pop()
            if (f >= best) break
            val g = cost[v]
            // Ya se sacó con algo mejor (las entradas viejas se quedan en el montón).
            if (f > g + heuristic(v, toLat, toLon, kx) + 0.01f) continue
            val inEdge = if (via[v] >= 0) graph.halfEdge(via[v]) else a.edge
            // Entrar al tramo del destino también es una vuelta: sin vuelta en U ni vueltas prohibidas.
            val ontoGoal = inEdge != b.edge && graph.turnAllowed(v, inEdge, b.edge)
            if (ontoGoal && v == b.from && !goalFrom.isNaN() && g + goalFrom < best) {
                best = g + goalFrom
                bestEnd = v
            }
            if (ontoGoal && v == b.to && !goalTo.isNaN() && g + goalTo < best) {
                best = g + goalTo
                bestEnd = v
            }
            for (h in graph.firstHalf(v) until graph.firstHalf(v + 1)) {
                if (!graph.canLeave(h)) continue
                val e = graph.halfEdge(h)
                // Sin vuelta en U por el mismo tramo, ni vueltas prohibidas.
                if (e == inEdge || !graph.turnAllowed(v, inEdge, e)) continue
                val t = graph.halfTarget(h)
                val c = g + (graph.lengthM(e) / speed(e)).toFloat()
                if (seen[t] != round || c < cost[t]) {
                    seen[t] = round
                    cost[t] = c
                    via[t] = h
                    prev[t] = v
                    heap.push(c + heuristic(t, toLat, toLon, kx), t)
                }
            }
        }
        if (bestEnd < 0) return null
        return build(a, b, bestEnd, best.toDouble())
    }

    private fun seed(v: Int, c: Float, b: Snap) {
        if (seen[v] == round && cost[v] <= c) return
        seen[v] = round
        cost[v] = c
        via[v] = -1
        prev[v] = -1
        val kx = cos(Math.toRadians(b.lat)) * RoadGraph.M_PER_DEG
        heap.push(c + heuristic(v, b.lat, b.lon, kx), v)
    }

    /** La otra mitad del tramo del punto [s] (de su cruce de llegada al de salida); -1 si no está. */
    private fun twin(s: Snap): Int {
        for (h in graph.firstHalf(s.to) until graph.firstHalf(s.to + 1)) {
            if (graph.halfEdge(h) == s.edge && graph.halfTarget(h) == s.from) return h
        }
        return -1
    }

    /** Lo menos que se puede tardar del cruce [v] al destino: en línea recta a la velocidad más alta. */
    private fun heuristic(v: Int, lat: Double, lon: Double, kx: Double): Float {
        val dy = (graph.latE6(v) / 1e6 - lat) * RoadGraph.M_PER_DEG
        val dx = (graph.lonE6(v) / 1e6 - lon) * kx
        return (sqrt(dx * dx + dy * dy) / FASTEST).toFloat()
    }

    /** Arma la ruta: del punto de salida al cruce de inicio, los tramos y del último cruce al destino. */
    private fun build(a: Snap, b: Snap, end: Int, seconds: Double): Route {
        val halves = ArrayList<Int>()
        var v = end
        while (via[v] >= 0) {
            halves += via[v]
            v = prev[v]
        }
        halves.reverse()
        val pts = PointList()
        // Los tramos de la ruta: dónde empieza cada uno (índice de punto), su tramo y su velocidad.
        val segs = ArrayList<Seg>()
        segs += Seg(0, a.edge, -1)
        pts.add(e6(a.lat), e6(a.lon))
        // Lo que queda del tramo de salida, con sus curvas, hasta el primer cruce.
        partial(a, a.alongM, if (v == a.to) Double.MAX_VALUE else 0.0, pts)
        pts.add(graph.latE6(v), graph.lonE6(v))
        var meters = if (v == a.to) graph.lengthM(a.edge) - a.alongM else a.alongM
        val byName = HashMap<String, Double>()
        fun count(edge: Int, len: Double) {
            val name = graph.name(edge)
            if (name.isNotEmpty()) byName[name] = (byName[name] ?: 0.0) + len
        }
        count(a.edge, meters)
        var at = v
        for (h in halves) {
            val e = graph.halfEdge(h)
            val t = graph.halfTarget(h)
            segs += Seg(pts.size - 1, e, at)
            graph.addShape(e, at, t, graph.halfReversed(h), pts)
            val len = graph.lengthM(e)
            meters += len
            count(e, len)
            at = t
        }
        val last = if (end == b.from) b.alongM else graph.lengthM(b.edge) - b.alongM
        meters += last
        count(b.edge, last)
        // Del último cruce al destino, también con las curvas del tramo.
        if (b.edge != segs.last().edge) segs += Seg(pts.size - 1, b.edge, end)
        partial(b, if (end == b.from) 0.0 else Double.MAX_VALUE, b.alongM, pts)
        pts.add(e6(b.lat), e6(b.lon))
        val main = byName.maxByOrNull { it.value }?.key.orEmpty()
        val points = pts.toArray()
        // Metros y segundos por punto (cada tramo a su velocidad); el total se ajusta al calculado.
        val n = points.size / 2
        val atM = DoubleArray(n)
        val atS = DoubleArray(n)
        var seg = 0
        for (i in 1 until n) {
            while (seg + 1 < segs.size && segs[seg + 1].start < i) seg++
            val d = flatMeters(points, i - 1, i)
            atM[i] = atM[i - 1] + d
            atS[i] = atS[i - 1] + d / speed(segs[seg].edge)
        }
        if (atM[n - 1] > 0) for (i in 0 until n) atM[i] *= meters / atM[n - 1]
        if (atS[n - 1] > 0) for (i in 0 until n) atS[i] *= seconds / atS[n - 1]
        return Route(points, meters, seconds, main, maneuvers(points, segs, atM), atM, atS)
    }

    /** Un tramo de la ruta: empieza en el punto [start], es el tramo [edge] y se entra a él por el cruce [via] (-1 = la salida). */
    private class Seg(val start: Int, val edge: Int, val via: Int)

    /**
     * Las vueltas de la ruta: la salida, cada cruce donde se da vuelta (o se sigue derecho a otra calle habiendo más
     * opciones) y la llegada. Un cruce sin otras salidas (la calle solo se curva o cambia de nombre) no cuenta.
     */
    private fun maneuvers(points: IntArray, segs: List<Seg>, atM: DoubleArray): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        out += Maneuver(ManeuverType.DEPART, 0f, graph.name(segs[0].edge), 0, 0.0)
        for (k in 1 until segs.size) {
            val s = segs[k]
            val j = s.start
            val before = graph.name(segs[k - 1].edge)
            val name = graph.name(s.edge)
            val turn = turnAngle(points, j)
            // Salidas posibles en ese cruce (sin contar por donde llegas): con una sola, no hay nada que decir.
            val came = segs[k - 1].edge
            val options = if (s.via < 0) 0 else (graph.firstHalf(s.via) until graph.firstHalf(s.via + 1)).count { graph.canLeave(it) && graph.halfEdge(it) != came }
            // La misma calle (aunque cambie "Calle" por "Avenida"): ligeramente o derecho no es vuelta, solo se dobla.
            val same = sameRoad(before, name)
            val type = when {
                options <= 1 -> null
                abs(turn) >= 165f -> ManeuverType.UTURN
                abs(turn) >= 120f -> if (turn > 0) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
                abs(turn) >= 50f -> if (turn > 0) ManeuverType.RIGHT else ManeuverType.LEFT
                same -> null
                abs(turn) >= 20f -> if (turn > 0) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
                name.isNotEmpty() -> ManeuverType.STRAIGHT
                else -> null
            } ?: continue
            out += Maneuver(type, turn, name, j, atM[j])
        }
        val last = points.size / 2 - 1
        out += Maneuver(ManeuverType.ARRIVE, 0f, "", last, atM[last])
        return out
    }

    /** Cuánto se da vuelta en el punto [j] (grados, + a la derecha): rumbo de unos 15 m antes contra unos 15 m después. */
    private fun turnAngle(points: IntArray, j: Int): Float {
        val n = points.size / 2
        var a = j - 1
        while (a > 0 && flatMeters(points, a, j) < TURN_LOOK_M) a--
        var b = j + 1
        while (b < n - 1 && flatMeters(points, j, b) < TURN_LOOK_M) b++
        if (a < 0 || b >= n) return 0f
        val inB = bearing(points, a, j)
        val outB = bearing(points, j, b)
        var d = outB - inB
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d.toFloat()
    }

    /**
     * Los puntos de forma del tramo de [s] entre [fromM] y [toM] metros desde su cruce de salida (en ese orden: si
     * [toM] < [fromM], al revés), sin los extremos. Las distancias se miden sobre la forma, escaladas a su largo.
     */
    private fun partial(s: Snap, fromM: Double, toM: Double, out: PointList) {
        val shape = PointList()
        shape.add(graph.latE6(s.from), graph.lonE6(s.from))
        graph.addShape(s.edge, s.from, s.to, graph.halfReversed(s.half), shape)
        if (shape.size <= 2) return
        val along = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            val dy = (shape.lat(i) - shape.lat(i - 1)) / 1e6 * RoadGraph.M_PER_DEG
            val dx = (shape.lon(i) - shape.lon(i - 1)) / 1e6 * cos(Math.toRadians(shape.lat(i) / 1e6)) * RoadGraph.M_PER_DEG
            along[i] = along[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val k = if (along.last() > 0) graph.lengthM(s.edge) / along.last() else 1.0
        val lo = minOf(fromM, toM)
        val hi = maxOf(fromM, toM)
        val inner = (1 until shape.size - 1).filter { along[it] * k > lo && along[it] * k < hi }
        for (i in if (toM >= fromM) inner else inner.reversed()) out.add(shape.lat(i), shape.lon(i))
    }

    /** Velocidad de manejo del tramo en m/s: la de su clase o su máxima (lo que sea menor, con un poco de margen). */
    private fun speed(e: Int): Double {
        val byClass = CLASS_KMH.getOrElse(graph.roadClass(e)) { 30 }
        val max = graph.maxKmh(e)
        val kmh = if (max > 0) minOf(max * 0.9, byClass * 1.25) else byClass.toDouble()
        return kmh.coerceAtLeast(5.0) / 3.6
    }

    private fun e6(d: Double) = Math.round(d * 1e6).toInt()

    companion object {
        /** Qué tan lejos se mira antes y después de un cruce para medir la vuelta (las calles se curvan al llegar). */
        private const val TURN_LOOK_M = 15.0

        /** km/h por clase (el orden de CLASSES en armar_mapa.py), para calles de México sin velocidad marcada. */
        private val CLASS_KMH = intArrayOf(95, 75, 55, 45, 38, 32, 25, 12, 55, 45, 35, 30, 28, 25, 12)

        /** Lo más rápido que va un auto (m/s): la heurística nunca debe pasarse (si no, la ruta deja de ser la mejor). */
        private const val FASTEST = 130 / 3.6
    }
}

class RouteException(val error: RouteError) : Exception(error.name)

private fun PointList.toArray(): IntArray = IntArray(size * 2) { if (it % 2 == 0) lat(it / 2) else lon(it / 2) }

/** Montón mínimo de (llave, cruce) en arreglos que crecen (sin objetos por entrada). */
private class Heap {
    private var keys = FloatArray(1024)
    private var values = IntArray(1024)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun topKey() = keys[0]

    fun push(key: Float, value: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (keys[p] <= key) break
            keys[i] = keys[p]
            values[i] = values[p]
            i = p
        }
        keys[i] = key
        values[i] = value
    }

    fun pop(): Int {
        val top = values[0]
        val lastKey = keys[--size]
        val lastValue = values[size]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && keys[c + 1] < keys[c]) c++
            if (keys[c] >= lastKey) break
            keys[i] = keys[c]
            values[i] = values[c]
            i = c
        }
        keys[i] = lastKey
        values[i] = lastValue
        return top
    }
}

/** Lo que se puede hacer para ir a un lugar con lo bajado. */
sealed interface RoutePlan {
    /** La ruta. */
    class Found(val route: Route) : RoutePlan

    /** Tu estado está bajado pero sin sus calles para la ruta: hay que bajarlas ([MapPacks.downloadRoutes]). */
    class NeedsRoutes(val region: MapRegion) : RoutePlan

    /** Tu estado y el del destino están bajados, pero las rutas entre estados aún no. */
    data object TwoStates : RoutePlan

    /** El destino está en otro estado ([region], si se sabe cuál) que no está bajado. */
    class OtherState(val region: MapRegion?) : RoutePlan

    /** No estás en un estado bajado. */
    data object NoState : RoutePlan

    /** No se pudo (lejos de las calles o sin camino). */
    class Failed(val error: RouteError) : RoutePlan
}

/** Qué tipo de vuelta es (la flecha y el texto salen de aquí). */
enum class ManeuverType { DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ARRIVE }

/**
 * Una vuelta de la ruta: su tipo, cuánto se gira ([angle], grados, + a la derecha, para dibujar la flecha), la calle a la
 * que se entra ([road], "" sin nombre), en qué punto de la ruta está y a cuántos metros de la salida.
 */
class Maneuver(val type: ManeuverType, val angle: Float, val road: String, val point: Int, val atM: Double)

/** Metros entre los puntos [i] y [j] de [points] (pares lat, lon en millonésimas), en plano (tramos cortos). */
internal fun flatMeters(points: IntArray, i: Int, j: Int): Double {
    val dy = (points[2 * j] - points[2 * i]) / 1e6 * RoadGraph.M_PER_DEG
    val dx = (points[2 * j + 1] - points[2 * i + 1]) / 1e6 * RoadGraph.M_PER_DEG * cos(Math.toRadians(points[2 * i] / 1e6))
    return sqrt(dx * dx + dy * dy)
}

/** Rumbo (grados desde el norte, a la derecha) del punto [i] al [j]. */
internal fun bearing(points: IntArray, i: Int, j: Int): Double {
    val dy = (points[2 * j] - points[2 * i]) / 1e6
    val dx = (points[2 * j + 1] - points[2 * i + 1]) / 1e6 * cos(Math.toRadians(points[2 * i] / 1e6))
    return Math.toDegrees(kotlin.math.atan2(dx, dy))
}

/** Lo que se le quita a un nombre de calle para compararlo ("Avenida X" es la misma que "X"). */
private val ROAD_PREFIXES = Regex(
    "^(calle|avenida|av\\.?|calzada|calz\\.?|boulevard|bulevar|blvd\\.?|carretera|carr\\.?|prolongación|prol\\.?|privada|priv\\.?|" +
        "andador|callejón|circuito|periférico|eje|street|st\\.?|avenue|ave\\.?|road|rd\\.?)\\s+",
)

/** ¿Es la misma calle? Igual sin mayúsculas ni el tipo de vía al principio; sin nombre las dos, también. */
internal fun sameRoad(a: String, b: String): Boolean {
    fun core(s: String) = s.trim().lowercase().replace(ROAD_PREFIXES, "")
    return core(a) == core(b)
}

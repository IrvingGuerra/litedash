package com.irvingguerra.litedash.feature.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Una ruta: sus puntos (pares lat, lon en millonésimas de grado, del punto de salida al de llegada), lo que mide, lo que
 * se tarda (sin tráfico) y la calle por donde va la mayor parte ([mainRoad], "" si no tiene nombre). [online] = vino del
 * servidor de rutas ([OnlineRoute]), no de las calles bajadas.
 */
class Route(
    val points: IntArray, val distanceM: Double, val seconds: Double, val mainRoad: String,
    /** Las vueltas en orden (la primera es la salida y la última la llegada). */
    val maneuvers: List<Maneuver> = emptyList(),
    /** Por punto: metros y segundos desde la salida (para el avance y la llegada). */
    val atM: DoubleArray = DoubleArray(0),
    val atS: DoubleArray = DoubleArray(0),
    val online: Boolean = false,
) {
    val pointCount get() = points.size / 2

    /** El rumbo con que sale la ruta (grados desde el norte; null si no se mueve): orienta el mapa antes de la primera lectura del GPS. */
    val startBearing: Float? get() {
        for (j in 1 until pointCount) if (flatMeters(points, 0, j) > 5.0) return bearing(points, 0, j).toFloat()
        return null
    }
}

/** Por qué no salió una ruta. [TOO_BIG] = no cabe en la memoria de esta pantalla (un estado muy grande o un viaje muy largo). */
enum class RouteError { FROM_OFF_ROAD, TO_OFF_ROAD, NO_PATH, TOO_BIG }

/**
 * Busca la ruta más rápida por las calles de [graph] (A*: el tiempo de cada tramo por su clase y velocidad máxima; respeta
 * sentidos y vueltas prohibidas u obligatorias; sin vueltas en U en los cruces; al salir, la vuelta en U para ir contra tu
 * rumbo cuesta [UTURN_S]). Una búsqueda a la vez: llamar desde un solo hilo, fuera del principal.
 *
 * Vueltas guardadas (A6 de la auditoría 1.5.15): en un cruce con vueltas prohibidas u obligatorias, a dónde se puede
 * seguir depende del tramo por el que se llegó. Ahí la etiqueta no es del cruce sino de la **media arista de llegada**
 * (en [turnLabels]): llegar más barato por un tramo con «solo derecho» ya no tapa otra llegada más cara que sí deja dar
 * la vuelta que la ruta necesita. En los demás cruces (casi todos) una etiqueta por cruce, como siempre. Un «estado» de
 * la búsqueda es el cruce (≥ 0) o, en esos cruces, `-(media arista + 1)`.
 *
 * Memoria ([Labels]): en estados chicos (≤ [DENSE_MAX] cruces, todo México) una lista por cruce, ~16 B cada uno (Edomex:
 * ~10 MB); en los grandes (California: ~2.9 millones) solo lo que la búsqueda toca, que crece sobre la marcha y se suelta
 * al terminar. Si no cabe en la memoria de la app, la ruta dice [RouteError.TOO_BIG] en vez de tumbarla.
 */
class Router internal constructor(
    private val graph: RoadGraph, dense: Boolean, sparseStart: Int,
    /** Solo pruebas: false = la tabla nunca se pasa a la lista (para probar que crezca muchas veces). */
    switchToDense: Boolean = true,
) {
    constructor(graph: RoadGraph) : this(graph, denseFits(graph.vertexCount), SPARSE_START)

    private val room = Room()
    private val labels = Labels(graph.vertexCount, dense, sparseStart, switchToDense, room)
    /** Las llegadas a cruces con vueltas guardadas, por media arista (pocas: siempre tabla, nunca lista). */
    private val turnLabels = Labels(graph.halfCount, false, TURN_START, false, room)
    private val heap = Heap(room)

    /** Cuántos cruces tocó la última búsqueda y su memoria en bytes (para medir en las pruebas). */
    internal var touched = 0
        private set
    internal var touchedBytes = 0L
        private set

    /**
     * La ruta de ([fromLat], [fromLon]) a ([toLat], [toLon]), o por qué no la hay. Con [headingDeg] (tu rumbo al ir
     * manejando, grados desde el norte), salir en sentido contrario cuesta una vuelta en U ([UTURN_S]): al recalcular,
     * la ruta no arranca «para atrás» si hay cómo rodear.
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float? = null): Result<Route> = try {
        var a = graph.snap(fromLat, fromLon) ?: throw RouteException(RouteError.FROM_OFF_ROAD)
        var b = graph.snap(toLat, toLon) ?: throw RouteException(RouteError.TO_OFF_ROAD)
        // Un punto pegado a una isla (calles sin salida para autos: un fraccionamiento cuyas entradas son privadas) no
        // tiene camino: se pega a la calle más cercana (≤ [ISLAND_SNAP_M]) fuera de ella. Si los dos están en la misma
        // isla se quedan; si cada uno en la suya, solo se mueve el de la más chica.
        val islandA = island(a)
        val islandB = island(b)
        if (islandA == null || b.edge !in islandA) {
            val moveA = islandA != null && (islandB == null || islandA.size <= islandB.size)
            if (moveA) a = graph.snap(fromLat, fromLon, ISLAND_SNAP_M, islandA) ?: a
            else if (islandB != null) b = graph.snap(toLat, toLon, ISLAND_SNAP_M, islandB) ?: b
        }
        search(a, b, headingDeg)?.let { Result.success(it) } ?: Result.failure(RouteException(RouteError.NO_PATH))
    } catch (e: RouteException) {
        Result.failure(e)
    } catch (_: OutOfMemoryError) {
        // La red de seguridad: si la cuenta de [Room.need] se quedó corta, sin ruta pero sin tumbar la app.
        Result.failure(RouteException(RouteError.TOO_BIG))
    } catch (_: RuntimeException) {
        // Un archivo dañado por dentro (pasó la revisión de la cabecera): sin ruta, sin tumbar la app.
        Result.failure(RouteException(RouteError.NO_PATH))
    }

    /**
     * Los tramos de la isla donde cayó [s]: lo que se alcanza desde sus dos cruces (sin ver el sentido) si son a lo más
     * [ISLAND_MAX] cruces; null = se conecta con el resto.
     */
    private fun island(s: Snap): Set<Int>? {
        val seen = hashSetOf(s.from, s.to)
        val queue = ArrayDeque(seen)
        val edges = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (h in graph.firstHalf(v) until graph.firstHalf(v + 1)) {
                edges += graph.halfEdge(h)
                val t = graph.halfTarget(h)
                if (seen.add(t)) {
                    if (seen.size > ISLAND_MAX) return null
                    queue += t
                }
            }
        }
        return edges
    }

    private fun search(a: Snap, b: Snap, headingDeg: Float?): Route? = try {
        room.collectedAt = 0L
        labels.reset()
        turnLabels.reset()
        heap.clear()
        explore(a, b, headingDeg)
    } finally {
        touched = labels.used + turnLabels.used
        touchedBytes = labels.bytes + turnLabels.bytes + heap.bytes
        // Lo que creció para una búsqueda larga se suelta: la memoria vuelve a la app entre rutas.
        labels.release()
        turnLabels.release()
        heap.release()
    }

    private fun explore(a: Snap, b: Snap, headingDeg: Float?): Route? {
        val aLen = graph.lengthM(a.edge)
        val bLen = graph.lengthM(b.edge)
        val aSpeed = speed(a.edge)
        val bSpeed = speed(b.edge)
        // Salidas: por el tramo de donde estás, hacia adelante (a su cruce de llegada) o, si se puede, hacia atrás.
        val back = twin(a)
        // Con tu rumbo: el sentido del tramo que va contra él lleva el costo de una vuelta en U.
        val turn = headingDeg?.let { startPenalties(a, it.toDouble()) }
        // Mismo tramo, en un sentido permitido: directo, sin pasar por cruces (si no es dar la vuelta en U ahí mismo y
        // hay cómo rodear).
        if (a.edge == b.edge && a.from == b.from) {
            val forward = b.alongM >= a.alongM
            val uTurn = turn != null && (if (forward) turn.first else turn.second) > 0f
            if (uTurn) {
                // Solo la salida a favor (la otra sería volver por el mismo tramo): rodea si se puede; si no, directo.
                val ahead = if (forward) Float.POSITIVE_INFINITY to 0f else 0f to Float.POSITIVE_INFINITY
                seedStarts(a, b, back, aLen, aSpeed, ahead)
                exploreFrom(a, b, bLen, bSpeed)?.let { return it }
                labels.reset()
                turnLabels.reset()
                heap.clear()
            }
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
        seedStarts(a, b, back, aLen, aSpeed, turn)
        return exploreFrom(a, b, bLen, bSpeed)
    }

    /**
     * Siembra las dos salidas por el tramo de [a] (cada una con su castigo de vuelta en U de [turn], si hay; infinito =
     * esa salida no).
     */
    private fun seedStarts(a: Snap, b: Snap, back: Int, aLen: Double, aSpeed: Double, turn: Pair<Float, Float>?) {
        val ahead = turn?.first ?: 0f
        val behind = turn?.second ?: 0f
        if (graph.canLeave(a.half) && ahead.isFinite()) seed(a.to, ((aLen - a.alongM) / aSpeed).toFloat() + ahead, b)
        if (back >= 0 && graph.canLeave(back) && behind.isFinite()) seed(a.from, (a.alongM / aSpeed).toFloat() + behind, b)
    }

    /**
     * El castigo (segundos) de salir hacia adelante (de [Snap.from] a [Snap.to]) y hacia atrás por el tramo de [a] yendo
     * con rumbo [headingDeg]: el sentido que va contra tu rumbo (más de 90°) cuesta [UTURN_S] si el otro va a favor.
     */
    private fun startPenalties(a: Snap, headingDeg: Double): Pair<Float, Float>? {
        val dy = (graph.latE6(a.to) - graph.latE6(a.from)) / 1e6 * RoadGraph.M_PER_DEG
        val dx = (graph.lonE6(a.to) - graph.lonE6(a.from)) / 1e6 * cos(Math.toRadians(a.lat)) * RoadGraph.M_PER_DEG
        if (dx * dx + dy * dy < 1.0) return null
        val forward = (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
        val d = ((headingDeg - forward) % 360 + 360) % 360
        val against = minOf(d, 360 - d) > 90
        return if (against) UTURN_S to 0f else 0f to UTURN_S
    }

    private fun exploreFrom(a: Snap, b: Snap, bLen: Double, bSpeed: Double): Route? {
        // Llegadas: al cruce de antes del destino sobre su tramo, más lo que falta (en el sentido permitido).
        val bBack = twin(b)
        val goalFrom = if (graph.canLeave(b.half)) (b.alongM / bSpeed).toFloat() else Float.NaN
        val goalTo = if (bBack >= 0 && graph.canLeave(bBack)) ((bLen - b.alongM) / bSpeed).toFloat() else Float.NaN
        var best = Float.MAX_VALUE
        // El estado del último cruce (los de media arista son negativos: «nada» es MIN_VALUE).
        var bestEnd = Int.MIN_VALUE
        val toLat = b.lat
        val toLon = b.lon
        val kx = cos(Math.toRadians(toLat)) * RoadGraph.M_PER_DEG
        while (heap.size > 0) {
            val f = heap.topKey()
            val state = heap.pop()
            if (f >= best) break
            val v = vertexOf(state)
            val table = tableOf(state)
            val sv = table.find(keyOf(state))
            val g = table.cost[sv]
            // Ya se sacó con algo mejor (las entradas viejas se quedan en el montón).
            if (f > g + heuristic(v, toLat, toLon, kx) + 0.01f) continue
            val inVia = table.via[sv]
            val inEdge = if (inVia >= 0) graph.halfEdge(inVia) else a.edge
            // Entrar al tramo del destino también es una vuelta: sin vuelta en U ni vueltas prohibidas.
            val ontoGoal = inEdge != b.edge && graph.turnAllowed(v, inEdge, b.edge)
            if (ontoGoal && v == b.from && !goalFrom.isNaN() && g + goalFrom < best) {
                best = g + goalFrom
                bestEnd = state
            }
            if (ontoGoal && v == b.to && !goalTo.isNaN() && g + goalTo < best) {
                best = g + goalTo
                bestEnd = state
            }
            for (h in graph.firstHalf(v) until graph.firstHalf(v + 1)) {
                if (!graph.canLeave(h)) continue
                val e = graph.halfEdge(h)
                // Sin vuelta en U por el mismo tramo, ni vueltas prohibidas.
                if (e == inEdge || !graph.turnAllowed(v, inEdge, e)) continue
                val t = graph.halfTarget(h)
                val c = g + (graph.lengthM(e) / speed(e)).toFloat()
                // Al cruce [t] con vueltas guardadas se llega «por h»: cada llegada con su propia etiqueta.
                val next = if (graph.hasTurns(t)) -(h + 1) else t
                val to = tableOf(next)
                val key = keyOf(next)
                val st = to.find(key)
                if (st < 0 || c < to.cost[st]) {
                    val slot = if (st < 0) to.add(key) else st
                    to.cost[slot] = c
                    to.via[slot] = h
                    to.prev[slot] = state
                    heap.push(c + heuristic(t, toLat, toLon, kx), next)
                }
            }
        }
        if (bestEnd == Int.MIN_VALUE) return null
        return build(a, b, bestEnd, best.toDouble())
    }

    /** El cruce del estado [s] de la búsqueda (un cruce, o la llegada por una media arista). */
    private fun vertexOf(s: Int) = if (s >= 0) s else graph.halfTarget(-s - 1)

    /** Dónde guarda su etiqueta el estado [s], y con qué llave. */
    private fun tableOf(s: Int) = if (s >= 0) labels else turnLabels
    private fun keyOf(s: Int) = if (s >= 0) s else -s - 1

    /** La salida: el cruce [v] por el tramo de donde estás (su etiqueta es la del cruce, sin media arista de llegada). */
    private fun seed(v: Int, c: Float, b: Snap) {
        val found = labels.find(v)
        if (found >= 0 && labels.cost[found] <= c) return
        val slot = if (found < 0) labels.add(v) else found
        labels.cost[slot] = c
        labels.via[slot] = -1
        labels.prev[slot] = -1
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

    /**
     * Arma la ruta: del punto de salida al cruce de inicio, los tramos y del último cruce al destino. [endState] = el estado
     * de la búsqueda del último cruce.
     */
    private fun build(a: Snap, b: Snap, endState: Int, seconds: Double): Route {
        val halves = ArrayList<Int>()
        val end = vertexOf(endState)
        var state = endState
        while (true) {
            val table = tableOf(state)
            val slot = table.find(keyOf(state))
            val h = table.via[slot]
            if (h < 0) break
            halves += h
            state = table.prev[slot]
        }
        val v = vertexOf(state)
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

        /** Lo que cuesta (segundos) salir en sentido contrario a tu rumbo: dar una vuelta en U o rodear la manzana. */
        private const val UTURN_S = 60f

        /** km/h por clase (el orden de CLASSES en armar_mapa.py), para calles de México sin velocidad marcada. */
        private val CLASS_KMH = intArrayOf(95, 75, 55, 45, 38, 32, 25, 12, 55, 45, 35, 30, 28, 25, 12)

        /** Lo más rápido que va un auto (m/s): la heurística nunca debe pasarse (si no, la ruta deja de ser la mejor). */
        private const val FASTEST = 130 / 3.6

        /** Hasta cuántos cruces va la lista por cruce (16 MB): el estado más grande de México tiene ~650 mil. */
        internal const val DENSE_MAX = 1_000_000

        /** Lugares iniciales de la tabla de lo recorrido (crece al doble cuando se llena a 3/4). */
        private const val SPARSE_START = 1 shl 14
        /** Una isla de calles (ver [island]) tiene a lo más estos cruces; más, ya es parte de la ciudad. */
        private const val ISLAND_MAX = 1_000
        /** Hasta dónde se busca la calle que sí conecta al salir de una isla (metros). */
        private const val ISLAND_SNAP_M = 600.0

        /** Lugares iniciales de la tabla de llegadas a cruces con vueltas guardadas (son pocas). */
        private const val TURN_START = 1 shl 8

        /** ¿Cabe la lista por cruce? Solo en estados chicos y si deja de sobra memoria libre (a lo más un cuarto). */
        private fun denseFits(n: Int) = n <= DENSE_MAX && n.toLong() * Labels.DENSE_BYTES <= freeMemory() / 4
    }
}

class RouteException(val error: RouteError) : Exception(error.name)

private fun PointList.toArray(): IntArray = IntArray(size * 2) { if (it % 2 == 0) lat(it / 2) else lon(it / 2) }

/** La memoria que le queda a la app (lo que aún puede pedir antes de un OutOfMemoryError). */
private fun freeMemory(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }

/** La memoria de una búsqueda: la basura se recoge pocas veces por búsqueda ([collectedAt] lo reinicia [Router.search]). */
private class Room {
    /** Lo que se pedía la última vez que se recogió la basura (0 = aún no en esta búsqueda). */
    var collectedAt = 0L

    /** ¿Hay memoria para [bytes] más, dejando la mitad de lo libre a la app? (La basura cuenta como usada: se recoge y se mira otra vez.) */
    fun has(bytes: Long): Boolean {
        if (bytes <= freeMemory() / 2) return true
        // La basura se recoge otra vez solo si se pide el doble que la vez pasada (los arreglos crecen al doble y dejan
        // el viejo de basura): pocas recolecciones por búsqueda, sin dar «muy largo» antes de tiempo.
        if (collectedAt > 0L && bytes < collectedAt * 2) return false
        collectedAt = bytes
        System.gc()
        return bytes <= freeMemory() / 2
    }

    /** Como [has], pero si no cabe la ruta no sale ([RouteError.TOO_BIG]). */
    fun need(bytes: Long) {
        if (!has(bytes)) throw RouteException(RouteError.TOO_BIG)
    }
}

/**
 * Lo que la búsqueda sabe de cada cruce que tocó: costo, por qué media arista llegó y de qué cruce venía, en el lugar que
 * da [find] / [add]. Dos formas: lista por cruce (el lugar es el cruce mismo, marcada por ronda: nada que limpiar entre
 * búsquedas) o tabla de lo tocado (direccionamiento abierto, sondeo lineal) que crece al doble al llenarse a 3/4. Si la
 * tabla fuera a pasar de la mitad de la lista (un viaje muy largo), esa búsqueda se pasa a la lista: nunca gasta más que
 * ella. [startDense] = lista desde el principio (estados chicos).
 */
private class Labels(
    private val n: Int, private val startDense: Boolean, private val start: Int, private val switchToDense: Boolean, private val room: Room,
) {
    private var dense = startDense
    /** Lista por cruce: la ronda en que se tocó. Tabla: el cruce + 1 (0 = libre). */
    private var keys: IntArray
    var cost: FloatArray
        private set
    var via: IntArray
        private set
    var prev: IntArray
        private set
    private var round = 0
    /** Lo que ocupan sus arreglos ahora. */
    val bytes get() = keys.size * DENSE_BYTES

    /** Cruces tocados en esta búsqueda. */
    var used = 0
        private set
    private var mask = 0
    private var shift = 0

    init {
        val size = if (dense) n else start
        keys = IntArray(size)
        cost = FloatArray(size)
        via = IntArray(size)
        prev = IntArray(size)
        mask = size - 1
        shift = 32 - Integer.numberOfTrailingZeros(size)
        require(dense || size and mask == 0) { "potencia de 2" }
    }

    /** Antes de cada búsqueda: nadie tocado (la tabla se limpia: lo de la búsqueda anterior no debe verse). */
    fun reset() {
        if (dense) {
            if (++round == Int.MAX_VALUE) {
                keys.fill(0)
                round = 1
            }
        } else if (used > 0) {
            keys.fill(0)
        }
        used = 0
    }

    /** Después de cada búsqueda: lo crecido vuelve a la app (la tabla a su tamaño inicial; si no creció, la limpia [reset]). */
    fun release() {
        if (!startDense && keys.size > start) {
            dense = false
            allocate(start)
            used = 0
        }
    }

    /** El lugar del cruce [v], o -1 si esta búsqueda no lo ha tocado. */
    fun find(v: Int): Int {
        if (dense) return if (keys[v] == round) v else -1
        var i = hash(v)
        while (true) {
            val k = keys[i]
            if (k == 0) return -1
            if (k == v + 1) return i
            i = (i + 1) and mask
        }
    }

    /** Aparta un lugar para [v] (que no estaba) y lo regresa; sus valores los pone quien llama. */
    fun add(v: Int): Int {
        if (dense) {
            keys[v] = round
            used++
            return v
        }
        if ((used + 1) * 4 > keys.size * 3) grow()
        if (dense) return add(v)
        var i = hash(v)
        while (keys[i] != 0) i = (i + 1) and mask
        keys[i] = v + 1
        used++
        return i
    }

    /** Hash de Fibonacci: los bits altos del producto (los bajos dependen solo de los bits bajos del cruce). */
    private fun hash(v: Int) = (v * -0x61c88647) ushr shift

    private fun grow() {
        val oldKeys = keys
        val oldCost = cost
        val oldVia = via
        val oldPrev = prev
        // Pasarse a la lista si la tabla doblada ya pasaría de la mitad de ella y la lista cabe; si no, doblar (si cabe).
        val toDense = switchToDense && oldKeys.size.toLong() * 2 * 2 > n && room.has(n * DENSE_BYTES)
        if (!toDense) room.need(oldKeys.size * 2L * DENSE_BYTES)
        if (toDense) {
            // A la lista por cruce (en esta búsqueda): el lugar pasa a ser el cruce y se marca con la ronda.
            dense = true
            if (++round == Int.MAX_VALUE) round = 1
            allocate(n)
            for (j in oldKeys.indices) {
                val k = oldKeys[j]
                if (k == 0) continue
                keys[k - 1] = round
                cost[k - 1] = oldCost[j]
                via[k - 1] = oldVia[j]
                prev[k - 1] = oldPrev[j]
            }
            return
        }
        allocate(oldKeys.size * 2)
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == 0) continue
            var i = hash(k - 1)
            while (keys[i] != 0) i = (i + 1) and mask
            keys[i] = k
            cost[i] = oldCost[j]
            via[i] = oldVia[j]
            prev[i] = oldPrev[j]
        }
    }

    private fun allocate(size: Int) {
        keys = IntArray(size)
        cost = FloatArray(size)
        via = IntArray(size)
        prev = IntArray(size)
        mask = size - 1
        shift = 32 - Integer.numberOfTrailingZeros(size)
    }

    companion object {
        /** Bytes por lugar: llave, costo, media arista y cruce de antes. */
        const val DENSE_BYTES = 16L
    }
}

/** Montón mínimo de (llave, cruce) en arreglos que crecen (sin objetos por entrada). */
private class Heap(private val room: Room) {
    private var keys = FloatArray(START)
    private var values = IntArray(START)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    val bytes get() = keys.size * 8L

    /** Lo crecido en una búsqueda larga vuelve a la app. */
    fun release() {
        size = 0
        if (keys.size > START) {
            keys = FloatArray(START)
            values = IntArray(START)
        }
    }

    fun topKey() = keys[0]

    fun push(key: Float, value: Int) {
        if (size == keys.size) {
            room.need(size.toLong() * 2 * 8)
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

    private companion object {
        const val START = 1024
    }
}

/** Lo que se puede hacer para ir a un lugar con lo bajado. */
sealed interface RoutePlan {
    /** La ruta; [routesOf] = salió en línea aunque tu estado está bajado: le faltan sus calles (se pueden bajar). */
    class Found(val route: Route, val routesOf: MapRegion? = null) : RoutePlan

    /** Tu estado está bajado pero sin sus calles para la ruta: hay que bajarlas ([MapPacks.downloadRoutes]). */
    class NeedsRoutes(val region: MapRegion) : RoutePlan

    /** Tu estado y el del destino están bajados, pero las rutas entre estados aún no. [online] = por qué no salió en línea. */
    class TwoStates(val online: OnlineMiss? = null) : RoutePlan

    /** El destino está en otro estado ([region], si se sabe cuál) que no está bajado. */
    class OtherState(val region: MapRegion?, val online: OnlineMiss? = null) : RoutePlan

    /** No estás en un estado bajado: el que hay que bajar ([region]; null = aún no hay mapa de ahí, o no se sabe). */
    class NoState(val region: MapRegion?, val online: OnlineMiss? = null) : RoutePlan

    /** Aún se carga la lista de estados del país (índice por país); al llegar se vuelve a planear. */
    data object Loading : RoutePlan

    /** No se pudo (lejos de las calles o sin camino). */
    class Failed(val error: RouteError) : RoutePlan
}

/** Por qué no hubo ruta en línea donde falta el mapa: sin internet, el servidor falló (o contestó algo raro) o está apagada. */
enum class OnlineMiss { NO_NET, FAILED, OFF }

/** ¿Falta un mapa (tu estado, el del destino o la ruta entre estados)? Ahí sirve la ruta en línea si hay internet. */
val RoutePlan.missingMap: Boolean
    get() = this is RoutePlan.NoState || this is RoutePlan.OtherState || this is RoutePlan.TwoStates

/** Por qué no salió en línea (null = no tocaba o no se sabe). */
val RoutePlan.onlineMiss: OnlineMiss?
    get() = when (this) {
        is RoutePlan.NoState -> online
        is RoutePlan.OtherState -> online
        is RoutePlan.TwoStates -> online
        else -> null
    }

/** El mismo plan diciendo por qué no salió en línea ([miss]); los que no son de mapa faltante quedan igual. */
internal fun RoutePlan.missed(miss: OnlineMiss): RoutePlan = when (this) {
    is RoutePlan.NoState -> RoutePlan.NoState(region, miss)
    is RoutePlan.OtherState -> RoutePlan.OtherState(region, miss)
    is RoutePlan.TwoStates -> RoutePlan.TwoStates(miss)
    else -> this
}

/** Qué tipo de vuelta es (la flecha y el texto salen de aquí). */
enum class ManeuverType { DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/**
 * Una vuelta de la ruta: su tipo, cuánto se gira ([angle], grados, + a la derecha, para dibujar la flecha), la calle a la
 * que se entra ([road], "" sin nombre), en qué punto de la ruta está y a cuántos metros de la salida. En una glorieta
 * ([ManeuverType.ROUNDABOUT]), [exit] = qué salida se toma (1 = la primera; 0 = no se sabe).
 */
class Maneuver(val type: ManeuverType, val angle: Float, val road: String, val point: Int, val atM: Double, val exit: Int = 0)

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

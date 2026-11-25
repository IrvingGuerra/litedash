package com.irvingguerra.litedash.feature.map

import com.irvingguerra.litedash.feature.map.LdmWriter.Edge
import com.irvingguerra.litedash.feature.map.LdmWriter.Turn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * El ruteador con un LDM0 chico armado aquí: cuadrícula de 3×3 cruces (~200 m entre ellos) cerca de 19.40, -99.10
 * (cruza el borde de una celda de 0.01° en longitud) y un tramo aislado ~1.2 km al norte.
 *
 * Cruces v(i, j): fila i (sur → norte), columna j (oeste → este). Tramos: h(i, j) = v(i, j)–v(i, j+1),
 * vt(i, j) = v(i, j)–v(i+1, j). Los de la fila 0 llevan un punto de forma a la mitad.
 */
class RouterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = ArrayList<RoadGraph>()

    @After
    fun closeAll() = opened.forEach { it.close() }

    // ---- Red de prueba ----

    private val vertices: List<IntArray> = buildList {
        for (i in 0..2) for (j in 0..2) add(intArrayOf(LAT0 + DLAT * i, LON0 + DLON * j))
        add(intArrayOf(ISO_LAT, LON0))
        add(intArrayOf(ISO_LAT, LON0 + 2_000))
    }

    private fun vid(i: Int, j: Int) = i * 3 + j
    private fun h(i: Int, j: Int) = i * 2 + j
    private fun vt(i: Int, j: Int) = 6 + i * 3 + j

    private fun edges(oneway: Map<Int, Int>): List<Edge> {
        val rowNames = listOf("Avenida Sur", "Avenida Media", "Avenida Norte")
        val colNames = listOf("Calle Poniente", "Calle Centro", "Calle Oriente")
        val list = ArrayList<Edge>()
        for (i in 0..2) for (j in 0..1) {
            val a = vid(i, j)
            val b = vid(i, j + 1)
            val inner = if (i == 0) listOf(mid(a, b)) else emptyList()
            list += Edge(a, b, inner, name = rowNames[i], oneway = oneway[list.size] ?: 0)
        }
        for (i in 0..1) for (j in 0..2) {
            list += Edge(vid(i, j), vid(i + 1, j), name = colNames[j], oneway = oneway[list.size] ?: 0)
        }
        list += Edge(9, 10, name = "Camino Aislado")
        return list
    }

    private fun mid(a: Int, b: Int) =
        intArrayOf((vertices[a][0] + vertices[b][0]) / 2, (vertices[a][1] + vertices[b][1]) / 2)

    private inner class Net(val graph: RoadGraph, val index: IntArray, val edges: List<Edge>) {
        val router = Router(graph)

        /** Número nuevo (en el archivo) del cruce v(i, j). */
        fun v(i: Int, j: Int) = index[vid(i, j)]

        /** Largo guardado del tramo en metros. */
        fun len(e: Int) = graph.lengthM(e)

        fun route(from: DoubleArray, to: DoubleArray) = router.route(from[0], from[1], to[0], to[1])
    }

    private fun build(oneway: Map<Int, Int> = emptyMap(), turns: List<Turn> = emptyList()): Net {
        val file = tmp.newFile()
        val list = edges(oneway)
        val index = LdmWriter.write(file, vertices, list, turns)
        val graph = RoadGraph.open(file) ?: error("no abrió el LDM0 de prueba")
        opened += graph
        return Net(graph, index, list)
    }

    /** Punto a la fracción [f] del tramo [e], medido de su `a` original (en línea recta: la forma es recta). */
    private fun at(e: Int, f: Double, list: List<Edge> = edges(emptyMap())): DoubleArray {
        val a = vertices[list[e].a]
        val b = vertices[list[e].b]
        return doubleArrayOf((a[0] + f * (b[0] - a[0])) / 1e6, (a[1] + f * (b[1] - a[1])) / 1e6)
    }

    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * RoadGraph.M_PER_DEG
        val dx = (lon2 - lon1) * cos(Math.toRadians(lat1)) * RoadGraph.M_PER_DEG
        return sqrt(dx * dx + dy * dy)
    }

    /** Los cruces (en el orden en que la ruta pasa por ellos), sin los puntos de forma ni los extremos. */
    private fun Net.crossings(r: Route): List<Int> {
        val out = ArrayList<Int>()
        for (k in 0 until r.pointCount) {
            val la = r.points[2 * k]
            val lo = r.points[2 * k + 1]
            for (v in 0 until graph.vertexCount) {
                if (graph.latE6(v) == la && graph.lonE6(v) == lo && out.lastOrNull() != v) out += v
            }
        }
        return out
    }

    private fun Net.goes(r: Route, from: Int, to: Int): Boolean {
        val c = crossings(r)
        return (0 until c.size - 1).any { c[it] == from && c[it + 1] == to }
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 0.05) =
        assertTrue("esperado ~$expected m, salió $actual m", abs(actual - expected) <= expected * tolerance)

    private fun assertStartsAndEnds(r: Route, from: DoubleArray, to: DoubleArray) {
        val n = r.pointCount
        assertTrue(n >= 2)
        assertTrue(distM(r.points[0] / 1e6, r.points[1] / 1e6, from[0], from[1]) < 5)
        assertTrue(distM(r.points[2 * n - 2] / 1e6, r.points[2 * n - 1] / 1e6, to[0], to[1]) < 5)
    }

    private fun errorOf(r: Result<Route>) = (r.exceptionOrNull() as RouteException).error

    // ---- Pruebas ----

    @Test
    fun writerSortsVerticesByCell() {
        val net = build()
        val g = net.graph
        // La cuadrícula cruza el borde de una celda en longitud: debe haber al menos dos celdas.
        assertTrue(g.cellCount >= 2)
        var last = Int.MIN_VALUE
        for (v in 0 until g.vertexCount) {
            val c = LdmWriter.cellOf(g.latE6(v), g.lonE6(v))
            assertTrue(c >= last)
            last = c
        }
        assertEquals(13, g.edgeCount)
        assertEquals(26, g.halfCount)
    }

    @Test
    fun simpleRoute() {
        val net = build()
        val from = at(h(0, 0), 0.25)
        val to = at(h(2, 1), 0.75)
        val r = net.route(from, to).getOrThrow()
        val expected = 0.75 * net.len(h(0, 0)) + net.len(vt(0, 1)) + net.len(vt(1, 1)) + 0.75 * net.len(h(2, 1))
        assertNear(expected, r.distanceM)
        assertStartsAndEnds(r, from, to)
        assertTrue(r.seconds > 0)
        // El punto de forma de la fila 0 no debe estar en esta ruta (sale hacia el este por su mitad final).
        assertTrue(net.crossings(r).size >= 3)
    }

    @Test
    fun oneWayAgainstGoesAround() {
        // h(0,0) solo de v(0,1) a v(0,0).
        val net = build(oneway = mapOf(h(0, 0) to -1))
        val from = at(vt(0, 0), 0.1)
        val to = at(vt(0, 1), 0.1)
        val r = net.route(from, to).getOrThrow()
        val around = 0.9 * net.len(vt(0, 0)) + net.len(h(1, 0)) + 0.9 * net.len(vt(0, 1))
        assertNear(around, r.distanceM)
        assertFalse(net.goes(r, net.v(0, 0), net.v(0, 1)))
        assertStartsAndEnds(r, from, to)

        // Al revés sí puede usarla y es corto.
        val back = net.route(to, from).getOrThrow()
        assertNear(0.2 * net.len(vt(0, 0)) + net.len(h(0, 0)), back.distanceM)
        assertTrue(net.goes(back, net.v(0, 1), net.v(0, 0)))
    }

    @Test
    fun oneWayShapeIsDrawnInDrivingOrder() {
        // h(0,1) va de v(0,2) a v(0,1) y tiene un punto de forma a la mitad: al recorrerla al revés debe salir en orden.
        val net = build(oneway = mapOf(h(0, 1) to -1))
        val from = at(vt(0, 2), 0.1)
        val to = at(vt(0, 1), 0.1)
        val r = net.route(from, to).getOrThrow()
        assertTrue(net.goes(r, net.v(0, 2), net.v(0, 1)))
        // Todos los puntos avanzan hacia el oeste o se quedan (nunca brincan de ida y vuelta) en el tramo de la fila 0.
        val lons = (0 until r.pointCount).map { r.points[2 * it + 1] }
        val m = mid(vid(0, 1), vid(0, 2))
        val k = (0 until r.pointCount).first { r.points[2 * it] == m[0] && r.points[2 * it + 1] == m[1] }
        assertTrue(lons[k - 1] > lons[k] && lons[k] > lons[k + 1])
    }

    @Test
    fun forbiddenTurnIsAvoided() {
        val noTurn = listOf(Turn(vid(1, 1), h(1, 0), vt(1, 1), 0))
        val from = at(h(1, 0), 0.5)
        val to = at(h(2, 1), 0.5)

        val free = build()
        val direct = free.route(from, to).getOrThrow()
        assertNear(0.5 * free.len(h(1, 0)) + free.len(vt(1, 1)) + 0.5 * free.len(h(2, 1)), direct.distanceM)
        assertTrue(free.goes(direct, free.v(1, 1), free.v(2, 1)))

        val net = build(turns = noTurn)
        val r = net.route(from, to).getOrThrow()
        assertFalse(net.goes(r, net.v(1, 1), net.v(2, 1)))
        assertNear(0.5 * net.len(h(1, 0)) + net.len(h(1, 1)) + net.len(vt(1, 2)) + 0.5 * net.len(h(2, 1)), r.distanceM)
        assertStartsAndEnds(r, from, to)
    }

    @Test
    fun onlyTurnIsFollowed() {
        val only = listOf(Turn(vid(1, 1), h(1, 0), h(1, 1), 1))
        val from = at(h(1, 0), 0.8)
        val to = at(h(0, 1), 0.5)

        val free = build()
        val direct = free.route(from, to).getOrThrow()
        assertTrue(free.goes(direct, free.v(1, 1), free.v(0, 1)))
        assertNear(0.2 * free.len(h(1, 0)) + free.len(vt(0, 1)) + 0.5 * free.len(h(0, 1)), direct.distanceM)

        val net = build(turns = only)
        val r = net.route(from, to).getOrThrow()
        assertFalse(net.goes(r, net.v(1, 1), net.v(0, 1)))
        assertTrue(net.goes(r, net.v(1, 1), net.v(1, 2)))
        assertNear(0.2 * net.len(h(1, 0)) + net.len(h(1, 1)) + net.len(vt(0, 2)) + 0.5 * net.len(h(0, 1)), r.distanceM)
    }

    /**
     * Dos llegadas al mismo cruce (A6): la barata (del poniente, con «solo derecho») no deja subir al norte; la cara (del
     * sur) sí. El destino está en vt(1, 1), de un solo sentido hacia el norte: solo se llega subiendo desde v(1, 1). Con
     * una etiqueta por cruce la llegada cara se tiraba y no había camino; por media arista se rodea por el sur.
     */
    @Test
    fun onlyTurnKeepsTheCostlierArrivalThatAllowsTheExit() {
        val only = listOf(Turn(vid(1, 1), h(1, 0), h(1, 1), 1))
        val oneway = mapOf(vt(1, 1) to 1)
        val from = at(h(1, 0), 0.5)
        val to = at(vt(1, 1), 0.2)

        val net = build(oneway, only)
        val r = net.route(from, to).getOrThrow()
        assertTrue(net.goes(r, net.v(1, 0), net.v(0, 0)))
        assertTrue(net.goes(r, net.v(0, 1), net.v(1, 1)))
        assertNear(
            0.5 * net.len(h(1, 0)) + net.len(vt(0, 0)) + net.len(h(0, 0)) + net.len(vt(0, 1)) + 0.2 * net.len(vt(1, 1)),
            r.distanceM,
        )
        assertStartsAndEnds(r, from, to)
        // Lo mismo con la tabla de lo recorrido (estados grandes).
        val sparse = Router(net.graph, dense = false, sparseStart = 2).route(from[0], from[1], to[0], to[1]).getOrThrow()
        assertTrue(r.points.contentEquals(sparse.points))
    }

    @Test
    fun isolatedStreetHasNoPath() {
        val net = build()
        val iso = at(12, 0.5)
        val grid = at(h(1, 0), 0.5)
        assertEquals(RouteError.NO_PATH, errorOf(net.route(grid, iso)))
        assertEquals(RouteError.NO_PATH, errorOf(net.route(iso, grid)))
        // Dentro de la calle aislada sí hay ruta.
        assertNear(0.6 * net.len(12), net.route(at(12, 0.2), at(12, 0.8)).getOrThrow().distanceM)
    }

    @Test
    fun pointOnANearbyIslandSnapsToTheConnectedStreets() {
        // Dos tramos que se conectan y, ~330 m al norte, una calle suelta (las entradas del fraccionamiento son privadas).
        val pts = listOf(
            intArrayOf(LAT0, LON0), intArrayOf(LAT0, LON0 + 4_000), intArrayOf(LAT0, LON0 + 8_000),
            intArrayOf(LAT0 + 3_000, LON0 + 2_000), intArrayOf(LAT0 + 3_000, LON0 + 4_000),
        )
        val list = listOf(Edge(0, 1, name = "Avenida"), Edge(1, 2, name = "Avenida"), Edge(3, 4, name = "Privada"))
        val file = tmp.newFile()
        LdmWriter.write(file, pts, list, emptyList())
        val graph = RoadGraph.open(file)!!.also { opened += it }
        val inside = doubleArrayOf((LAT0 + 3_000) / 1e6, (LON0 + 3_000) / 1e6)
        val street = doubleArrayOf(LAT0 / 1e6, (LON0 + 7_000) / 1e6)
        val r = Router(graph).route(street[0], street[1], inside[0], inside[1]).getOrThrow()
        // Llega a la avenida, junto a la calle suelta (no «sin camino»).
        assertEquals(LAT0, r.points[r.points.size - 2])
        val back = Router(graph).route(inside[0], inside[1], street[0], street[1]).getOrThrow()
        assertEquals(LAT0, back.points[0])
        // Dentro de la calle suelta sí se queda en ella.
        assertNear(105.0, Router(graph).route(inside[0], (LON0 + 2_500) / 1e6, inside[0], (LON0 + 3_500) / 1e6).getOrThrow().distanceM)
    }

    @Test
    fun farFromStreetsIsOffRoad() {
        val net = build()
        val grid = at(h(1, 0), 0.5)
        // ~5 km al norte (fuera de las celdas revisadas) y ~2.7 km al este (dentro de las celdas, pero > 2 km).
        for (far in listOf(doubleArrayOf(19.45, -99.10), doubleArrayOf(LAT0 / 1e6 + 0.002, -99.0715))) {
            assertNull(net.graph.snap(far[0], far[1]))
            assertEquals(RouteError.FROM_OFF_ROAD, errorOf(net.route(far, grid)))
            assertEquals(RouteError.TO_OFF_ROAD, errorOf(net.route(grid, far)))
        }
    }

    @Test
    fun sameEdgeForwardIsDirect() {
        for (oneway in listOf(emptyMap(), mapOf(h(1, 0) to 1))) {
            val net = build(oneway = oneway)
            val from = at(h(1, 0), 0.2)
            val to = at(h(1, 0), 0.7)
            val r = net.route(from, to).getOrThrow()
            assertEquals(2, r.pointCount)
            assertNear(0.5 * net.len(h(1, 0)), r.distanceM)
            assertEquals("Avenida Media", r.mainRoad)
            assertStartsAndEnds(r, from, to)
        }
    }

    @Test
    fun recalculatingDoesNotStartBackwards() {
        // Vas al oriente por Avenida Media y el destino quedó atrás (Calle Poniente, abajo).
        val net = build()
        val from = at(h(1, 0), 0.5)
        val to = at(vt(0, 0), 0.5)
        // Sin rumbo (al planear): lo más corto, de regreso.
        val plain = net.router.route(from[0], from[1], to[0], to[1]).getOrThrow()
        assertNear(0.5 * net.len(h(1, 0)) + 0.5 * net.len(vt(0, 0)), plain.distanceM)
        // Con tu rumbo al oriente: sigue adelante y rodea la manzana (sin vuelta en U).
        val ahead = net.router.route(from[0], from[1], to[0], to[1], headingDeg = 90f).getOrThrow()
        assertTrue(net.goes(ahead, net.v(1, 1), net.v(0, 1)))
        assertNear(0.5 * net.len(h(1, 0)) + net.len(vt(0, 1)) + net.len(h(0, 0)) + 0.5 * net.len(vt(0, 0)), ahead.distanceM)
        // Hacia el poniente, regresar es a favor: igual que sin rumbo.
        assertNear(plain.distanceM, net.router.route(from[0], from[1], to[0], to[1], headingDeg = 270f).getOrThrow().distanceM, 0.01)
        // En el mismo tramo, el destino atrás: rodea en vez de la vuelta en U ahí mismo.
        val back = net.router.route(from[0], from[1], at(h(1, 0), 0.2)[0], at(h(1, 0), 0.2)[1], headingDeg = 90f).getOrThrow()
        assertTrue(back.distanceM > 0.5 * net.len(h(1, 0)))
    }

    @Test
    fun sameEdgeBackwardOnOneWayGoesAroundTheBlock() {
        val net = build(oneway = mapOf(h(1, 0) to 1))
        val from = at(h(1, 0), 0.7)
        val to = at(h(1, 0), 0.2)
        val r = net.route(from, to).getOrThrow()
        // Sigue a v(1,1) y vuelve por arriba o por abajo a v(1,0): nunca recorre h(1,0) al revés.
        assertFalse(net.goes(r, net.v(1, 1), net.v(1, 0)))
        assertNear(
            0.3 * net.len(h(1, 0)) + net.len(vt(0, 1)) + net.len(h(0, 0)) + net.len(vt(0, 0)) + 0.2 * net.len(h(1, 0)),
            r.distanceM,
        )
    }

    @Test
    fun damagedFilesDoNotOpen() {
        val good = tmp.newFile()
        LdmWriter.write(good, vertices, edges(emptyMap()), listOf(Turn(vid(1, 1), h(1, 0), vt(1, 1), 0)))
        val bytes = good.readBytes()

        fun variant(edit: (ByteArray) -> ByteArray): File = tmp.newFile().apply { writeBytes(edit(bytes.copyOf())) }
        fun putInt(b: ByteArray, at: Int, v: Int) = b.apply {
            this[at] = v.toByte(); this[at + 1] = (v shr 8).toByte(); this[at + 2] = (v shr 16).toByte(); this[at + 3] = (v shr 24).toByte()
        }

        val bad = listOf(
            "magia" to variant { it.apply { this[3] = '2'.code.toByte() } },
            "vueltas fuera" to variant { putInt(it, 56, bytes.size + 100) },
            "forma fuera" to variant { putInt(it, 48, bytes.size * 4) },
            "desplazamiento < 64" to variant { putInt(it, 32, 10) },
            "secciones desordenadas" to variant { putInt(it, 36, bytes.size - 1) },
            "conteo negativo" to variant { putInt(it, 12, -5) },
            "vértices de más" to variant { putInt(it, 12, 1_000_000) },
            "truncado" to variant { it.copyOf(it.size - 5) },
            "solo cabecera corta" to variant { it.copyOf(40) },
            "vacío" to variant { ByteArray(0) },
        )
        for ((why, file) in bad) assertNull(why, RoadGraph.open(file))
        assertNull(RoadGraph.open(File(tmp.root, "no-existe.ldm")))
        // El bueno sí abre.
        RoadGraph.open(good)!!.close()
    }

    @Test
    fun turnAllowedDirect() {
        val net = build(
            turns = listOf(
                Turn(vid(1, 1), h(1, 0), vt(1, 1), 0),
                Turn(vid(1, 1), h(1, 0), vt(0, 1), 0),
                Turn(vid(2, 1), vt(1, 1), h(2, 1), 1),
            ),
        )
        val g = net.graph
        val c = net.v(1, 1)
        assertFalse(g.turnAllowed(c, h(1, 0), vt(1, 1)))
        assertFalse(g.turnAllowed(c, h(1, 0), vt(0, 1)))
        assertTrue(g.turnAllowed(c, h(1, 0), h(1, 1)))
        assertTrue(g.turnAllowed(c, h(1, 1), vt(1, 1)))
        assertTrue(g.turnAllowed(c, vt(0, 1), vt(1, 1)))
        // Obligatoria: de vt(1,1) en v(2,1) solo a h(2,1).
        val top = net.v(2, 1)
        assertTrue(g.turnAllowed(top, vt(1, 1), h(2, 1)))
        assertFalse(g.turnAllowed(top, vt(1, 1), h(2, 0)))
        assertTrue(g.turnAllowed(top, h(2, 0), h(2, 1)))
        // Cruces sin vueltas.
        assertTrue(g.turnAllowed(net.v(0, 0), h(0, 0), vt(0, 0)))
        assertTrue(g.turnAllowed(net.v(2, 2), h(2, 1), vt(1, 2)))
        // Sin ninguna vuelta en el archivo.
        val plain = build().graph
        assertTrue(plain.turnAllowed(net.v(1, 1), h(1, 0), vt(1, 1)))
    }

    @Test
    fun mainRoadIsTheLongestNamedStreet() {
        val net = build()
        val r = net.route(at(vt(0, 0), 0.9), at(vt(0, 2), 0.9)).getOrThrow()
        assertNear(0.1 * net.len(vt(0, 0)) + net.len(h(1, 0)) + net.len(h(1, 1)) + 0.1 * net.len(vt(0, 2)), r.distanceM)
        assertEquals("Avenida Media", r.mainRoad)
        assertEquals("Avenida Media", net.graph.name(h(1, 0)))
        assertEquals("Camino Aislado", net.graph.name(12))
    }

    @Test
    fun snapFindsShapeAndAlong() {
        val net = build()
        val p = at(h(0, 1), 0.5) // justo el punto de forma
        val s = net.graph.snap(p[0], p[1]) ?: error("sin snap")
        assertEquals(h(0, 1), s.edge)
        assertTrue(s.offM < 1)
        assertNear(0.5 * net.len(h(0, 1)), s.alongM, 0.02)
    }

    // ---- Bugs encontrados (se dejan apagados: el código de main no se toca en esta tanda) ----

    /** BUG: la vuelta al tramo del destino no revisa las vueltas prohibidas (Router.search, revisión de goalFrom/goalTo). */
    @Test
    fun forbiddenTurnOntoGoalEdge() {
        val net = build(turns = listOf(Turn(vid(1, 1), h(1, 0), vt(1, 1), 0)))
        val r = net.route(at(h(1, 0), 0.5), at(vt(1, 1), 0.5)).getOrThrow()
        // Correcto: no puede dar esa vuelta, debe rodear (≥ 1.5 cuadras). Hoy sale directo por v(1,1) (~200 m).
        assertTrue(r.distanceM > net.len(h(1, 0)) + net.len(vt(1, 1)))
    }

    /** BUG: en el mismo tramo de doble sentido, hacia atrás, la ruta va al cruce y da vuelta en U sobre el mismo tramo. */
    @Test
    fun sameEdgeBackwardTwoWay() {
        val net = build()
        val r = net.route(at(h(1, 0), 0.7), at(h(1, 0), 0.2)).getOrThrow()
        // Correcto: directo (0.5 L), o al menos sin vuelta en U. Hoy: 0.7 L hasta v(1,0) y regresa 0.2 L (0.9 L).
        assertNear(0.5 * net.len(h(1, 0)), r.distanceM)
    }

    /** BUG menor: mainRoad no cuenta los pedazos del tramo de salida y del de llegada. */
    @Test
    fun mainRoadCountsStartAndEndEdges() {
        val net = build()
        val r = net.route(at(h(1, 0), 0.5), at(h(1, 1), 0.5)).getOrThrow()
        assertEquals("Avenida Media", r.mainRoad)
    }

    /**
     * La tabla de lo recorrido (estados grandes) da exactamente lo mismo que la lista por cruce, con muchas rutas seguidas en
     * el mismo ruteador: reusada sin crecer (64 lugares: lo de una búsqueda no debe verse en la siguiente), creciendo muchas
     * veces sin pasarse a la lista (desde 2) y pasándose a la lista a media búsqueda.
     */
    @Test
    fun sparseLabelsMatchDense() {
        val net = build(oneway = mapOf(h(1, 0) to 1, vt(0, 2) to -1), turns = listOf(Turn(vid(1, 1), h(1, 0), vt(1, 1), 0)))
        val points = (0 until 13).flatMap { e -> listOf(0.1, 0.5, 0.9).map { at(e, it) } }
        val routers = listOf(
            Router(net.graph, dense = false, sparseStart = 64),
            Router(net.graph, dense = false, sparseStart = 2, switchToDense = false),
            Router(net.graph, dense = false, sparseStart = 2),
        )
        var compared = 0
        repeat(2) {
            for (from in points) for (to in points) {
                val d = net.router.route(from[0], from[1], to[0], to[1])
                for (sparse in routers) {
                    val s = sparse.route(from[0], from[1], to[0], to[1])
                    assertEquals(d.isSuccess, s.isSuccess)
                    if (d.isSuccess) {
                        val a = d.getOrThrow()
                        val b = s.getOrThrow()
                        assertTrue(a.points.contentEquals(b.points))
                        assertEquals(a.seconds, b.seconds, 1e-6)
                        assertEquals(a.distanceM, b.distanceM, 1e-6)
                        compared++
                    } else {
                        assertEquals((d.exceptionOrNull() as RouteException).error, (s.exceptionOrNull() as RouteException).error)
                    }
                }
            }
        }
        assertTrue(compared > 3000)
    }

    private companion object {
        const val LAT0 = 19_401_500
        const val LON0 = -99_101_500
        const val DLAT = 1_800 // ~200 m
        const val DLON = 1_900 // ~199 m a esta latitud
        const val ISO_LAT = 19_412_000
    }
}

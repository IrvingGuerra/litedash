package com.irvingguerra.litedash.feature.map

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * El LDM1 con dibujo (calles desde el archivo de ruta, MP2-D2): `colima-centro.ldm.gz` lo armó `armar_mapa.py --dibujo`
 * con un recorte de 3 × 3 km del centro de Colima (© OpenStreetMap, ODbL), con vías completas: varias salen del recorte
 * (tramos largos).
 */
class GraphTilesTest {
    companion object {
        private lateinit var file: File
        private lateinit var graph: RoadGraph

        @BeforeClass @JvmStatic
        fun open() {
            file = File.createTempFile("colima", ".ldm")
            GraphTilesTest::class.java.getResourceAsStream("/ldm/colima-centro.ldm.gz")!!.use { raw ->
                GZIPInputStream(raw).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            graph = RoadGraph.open(file)!!
        }

        @AfterClass @JvmStatic
        fun close() {
            graph.close()
            file.delete()
        }
    }

    /** Todos los tramos con sus puntos (lat, lon en millonésimas), desde su origen. */
    private fun allEdges(): Map<Int, PointList> {
        val out = HashMap<Int, PointList>()
        for (v in 0 until graph.vertexCount) for (h in graph.firstHalf(v) until graph.firstHalf(v + 1)) {
            if (graph.halfReversed(h)) continue
            val pts = PointList()
            pts.add(graph.latE6(v), graph.lonE6(v))
            graph.addShape(graph.halfEdge(h), v, graph.halfTarget(h), false, pts)
            out[graph.halfEdge(h)] = pts
        }
        return out
    }

    @Test
    fun readsLdm1WithDrawingSections() {
        assertTrue(graph.drawable)
        assertTrue(graph.edgeCount > 1000)
        // Lo mismo solo con la cabecera (lo que revisa un paquete del formato 2 antes de usarlo).
        assertTrue(RoadGraph.hasDrawing(file))
    }

    @Test
    fun ldm0IsNotDrawable() {
        val f = File.createTempFile("ldm0", ".ldm")
        try {
            LdmWriter.write(f, listOf(intArrayOf(19_240_000, -103_730_000), intArrayOf(19_241_000, -103_730_000)), listOf(LdmWriter.Edge(0, 1)), emptyList())
            RoadGraph.open(f)!!.use { assertFalse(it.drawable) }
            assertFalse(RoadGraph.hasDrawing(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun everyEdgeInABoxIsFoundOnce() {
        val edges = allEdges()
        // Cajas chicas (una celda y un pedazo) en varios lugares: lo que tenga un punto o medio pedazo adentro debe salir.
        val boxes = listOf(
            doubleArrayOf(19.236, 19.244, -103.735, -103.725),
            doubleArrayOf(19.2405, 19.2415, -103.7305, -103.7295),
            doubleArrayOf(19.226, 19.229, -103.744, -103.741),
        )
        for (b in boxes) {
            val found = HashMap<Int, Int>()
            GraphTiles.forEachEdge(graph, b[0], b[1], b[2], b[3]) { h, from ->
                val e = graph.halfEdge(h)
                found[e] = (found[e] ?: 0) + 1
                // El cruce de salida es de verdad el de esa mitad.
                assertEquals(from, graph.halfOrigin(h))
            }
            assertTrue(found.values.all { it == 1 })
            var expected = 0
            for ((e, pts) in edges) {
                var hit = false
                for (i in 0 until pts.size) {
                    val lat = pts.lat(i) / 1e6
                    val lon = pts.lon(i) / 1e6
                    val midLat = if (i + 1 < pts.size) (pts.lat(i) + pts.lat(i + 1)) / 2e6 else lat
                    val midLon = if (i + 1 < pts.size) (pts.lon(i) + pts.lon(i + 1)) / 2e6 else lon
                    if ((lat in b[0]..b[1] && lon in b[2]..b[3]) || (midLat in b[0]..b[1] && midLon in b[2]..b[3])) hit = true
                }
                if (hit) {
                    expected++
                    assertTrue("falta el tramo $e", e in found)
                }
            }
            assertTrue(expected > 0)
        }
    }

    @Test
    fun longEdgesComeFromTheIndex() {
        // Una vía que sale del recorte no tiene cruce fuera: sus tramos largos viven en el índice.
        var listed = 0
        for (r in 10_920..10_930) for (c in 7_624..7_630) graph.forEachListed(RoadGraph.LONG, r * 65536 + c) { h ->
            assertFalse(graph.halfReversed(h))
            listed++
        }
        assertTrue(listed > 0)
    }

    @Test
    fun tileHasRoadsNamesAndPrivateStreets() {
        val z = 14
        val key = tileKey(z, tileX(-103.73, z).toInt(), tileY(19.24, z).toInt())
        val roads = GraphTiles.roads(graph, key, 4096)
        val parts = roads.roads.sumOf { tier -> tier.sumOf { it.parts.size } }
        assertTrue(parts > 200)
        assertEquals(roads.roads.map { it.size }, roads.oneway.map { it.size })
        assertTrue(roads.roads[RoadTier.SERVICE.ordinal].isNotEmpty())
        // Los nombres van unidos por calle: menos calles con nombre que tramos con nombre.
        assertTrue(roads.names.isNotEmpty())
        val namedEdges = roads.names.size
        assertTrue(roads.names.any { n -> n.shape.points.size > 6 })
        assertTrue(namedEdges < parts)
        // En el 13 no van las de servicio.
        val z13 = GraphTiles.roads(graph, tileKey(13, keyX(key) / 2, keyY(key) / 2), 4096)
        assertTrue(z13.roads[RoadTier.SERVICE.ordinal].isEmpty())
        assertTrue(z13.names.isEmpty())
    }

    @Test
    fun farViewHasMinorStreetsOnlyAtZoom12() {
        val z = 12
        val far = GraphTiles.roads(graph, tileKey(z, tileX(-103.73, z).toInt(), tileY(19.24, z).toInt()), 4096)
        // En el 12 la trama de calles chicas (sección minor) junto a las principales.
        assertTrue(far.roads[RoadTier.MINOR.ordinal].isNotEmpty())
        assertTrue(far.roads.take(3).sumOf { it.size } > 0)
        val z11 = GraphTiles.roads(graph, tileKey(11, tileX(-103.73, 11).toInt(), tileY(19.24, 11).toInt()), 4096)
        assertTrue(z11.roads[RoadTier.MINOR.ordinal].isEmpty())
        assertTrue(z11.roads.take(3).sumOf { it.size } > 0)
        val z9 = GraphTiles.roads(graph, tileKey(9, tileX(-103.73, 9).toInt(), tileY(19.24, 9).toInt()), 4096)
        assertTrue(z9.roads[RoadTier.SECONDARY.ordinal].isEmpty())
    }

    @Test
    fun extraLinesDecode() {
        val pts = PointList()
        val line = DrawLine()
        var seen = 0
        for (r in 10_920..10_930) for (c in 7_624..7_630) graph.forEachListed(RoadGraph.EXTRA, r * 65536 + c) { off ->
            pts.clear()
            graph.readLine(RoadGraph.EXTRA, off, pts, line)
            assertTrue(pts.size >= 2)
            assertTrue(pts.lat(0) in 19_200_000..19_300_000 && pts.lon(0) in -103_800_000..-103_700_000)
            seen++
        }
        assertTrue(seen > 0)
    }

    @Test
    fun routerStillWorksOnLdm1() {
        val r = Router(graph).route(19.2435, -103.7310, 19.2330, -103.7220, null).getOrThrow()
        assertTrue(r.distanceM > 500)
    }
}

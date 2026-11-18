package com.irvingguerra.litedash.feature.map

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Medición del ruteador con los estados reales (solo en la Mac de desarrollo: sin los archivos de
 * `litedash-claude/prototipos/mapas/datos/ldm/`, se salta). Imprime tiempo, distancia y minutos de cada par.
 */
class RouterBench {
    // LITEDASH_LDM_DIR para otro país (p. ej. …/datos/us/ldm).
    private val dir = System.getenv("LITEDASH_LDM_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.dir"), "../../../litedash-claude/prototipos/mapas/datos/ldm")

    @Test
    fun benchmark() {
        assumeTrue(System.getenv("LITEDASH_ROUTER_BENCH") != null && dir.isDirectory)
        val pairs = System.getenv("LITEDASH_ROUTER_PAIRS")?.let { File(it).readLines() } ?: return
        var graphName = ""
        var graph: RoadGraph? = null
        var router: Router? = null
        for (line in pairs) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 5 || line.startsWith("#")) continue
            if (p[0] != graphName) {
                graph?.close()
                graphName = p[0]
                val t = System.nanoTime()
                graph = RoadGraph.open(File(dir, "${p[0]}.ldm"))!!
                router = Router(graph)
                println("ABRIR ${p[0]} ${(System.nanoTime() - t) / 1e6} ms cruces=${graph.vertexCount} lista por cruce=${graph.vertexCount <= Router.DENSE_MAX}")
            }
            val (a, b, c, d) = p.subList(1, 5).map { it.toDouble() }
            // LITEDASH_ROUTER_COMPARE: la tabla de lo recorrido contra la lista por cruce, punto por punto.
            if (System.getenv("LITEDASH_ROUTER_COMPARE") != null) {
                val x = Router(graph!!, dense = true, sparseStart = 1 shl 14).route(a, b, c, d).getOrNull()
                val y = Router(graph, dense = false, sparseStart = 1 shl 14).route(a, b, c, d).getOrNull()
                println("IGUAL ${p[0]} $a,$b -> $c,$d : ${x != null && y != null && x.points.contentEquals(y.points) && x.seconds == y.seconds}")
            }
            // Una vez para calentar (JIT) y la segunda se mide.
            router!!.route(a, b, c, d)
            val t = System.nanoTime()
            val r = router.route(a, b, c, d)
            val ms = (System.nanoTime() - t) / 1e6
            r.fold(
                { println("RUTA ${p[0]} $a,$b -> $c,$d : ${"%.0f".format(ms)} ms ${"%.2f".format(it.distanceM / 1000)} km ${"%.1f".format(it.seconds / 60)} min por ${it.mainRoad} puntos=${it.pointCount} tocados=${router.touched} memoria=${router.touchedBytes / 1_000_000} MB") },
                { println("RUTA ${p[0]} $a,$b -> $c,$d : ${"%.0f".format(ms)} ms ERROR ${it.message}") },
            )
        }
        graph?.close()
    }
}

/** Las vueltas y la guía con un estado real (solo en la Mac). */
class GuidanceBench {
    private val dir = File(System.getProperty("user.dir"), "../../../litedash-claude/prototipos/mapas/datos/ldm")

    @Test
    fun maneuversOfARealRoute() {
        assumeTrue(System.getenv("LITEDASH_ROUTER_BENCH") != null && dir.isDirectory)
        val which = System.getenv("LITEDASH_ROUTE_STATE") ?: "colima"
        val c = (System.getenv("LITEDASH_ROUTE_COORDS") ?: "19.2433,-103.7250,19.2900,-103.7400").split(",").map { it.toDouble() }
        val graph = RoadGraph.open(File(dir, "$which.ldm"))!!
        val route = Router(graph).route(c[0], c[1], c[2], c[3]).getOrThrow()
        System.getenv("LITEDASH_ROUTE_DUMP")?.let { out ->
            File(out).writeText((0 until route.pointCount).joinToString("\n") { "${route.points[2 * it] / 1e6} ${route.points[2 * it + 1] / 1e6}" })
        }
        for (m in route.maneuvers) println("VUELTA ${m.type} ${"%.0f".format(m.angle)}° a ${"%.0f".format(m.atM)} m → ${m.road}")
        // Recorre la ruta punto por punto y algunos puntos desviados.
        val g = Guidance(route)
        var i = 0
        while (i < route.pointCount) {
            val s = g.update(route.points[2 * i] / 1e6, route.points[2 * i + 1] / 1e6)
            if (i % 20 == 0) println("GUIA punto $i: hecho ${"%.0f".format(s.doneM)} m, próxima #${s.next} a ${"%.0f".format(s.toNextM)} m, faltan ${"%.0f".format(s.leftM)} m / ${"%.0f".format(s.leftS)} s, fuera=${s.offRoute}, llegó=${s.arrived}")
            i += 5
        }
        val end = route.pointCount - 1
        println("GUIA final: ${g.update(route.points[2 * end] / 1e6, route.points[2 * end + 1] / 1e6)}")
        val off = Guidance(route)
        off.update(19.2433, -103.7250)
        println("FUERA 1: ${off.update(19.2500, -103.7100).offRoute} 2: ${off.update(19.2501, -103.7101).offRoute}")
        graph.close()
    }
}

package com.irvingguerra.litedash.feature.map

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Medición del ruteador con los estados reales (solo en la Mac de desarrollo: sin los archivos de
 * `litedash-claude/prototipos/mapas/datos/ldm/`, se salta). Imprime tiempo, distancia y minutos de cada par.
 */
class RouterBench {
    private val dir = File(System.getProperty("user.dir"), "../../../litedash-claude/prototipos/mapas/datos/ldm")

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
                println("ABRIR ${p[0]} ${(System.nanoTime() - t) / 1e6} ms cruces=${graph.vertexCount}")
            }
            val (a, b, c, d) = p.subList(1, 5).map { it.toDouble() }
            // Una vez para calentar (JIT) y la segunda se mide.
            router!!.route(a, b, c, d)
            val t = System.nanoTime()
            val r = router.route(a, b, c, d)
            val ms = (System.nanoTime() - t) / 1e6
            r.fold(
                { println("RUTA ${p[0]} $a,$b -> $c,$d : ${"%.0f".format(ms)} ms ${"%.2f".format(it.distanceM / 1000)} km ${"%.1f".format(it.seconds / 60)} min por ${it.mainRoad} puntos=${it.pointCount}") },
                { println("RUTA ${p[0]} $a,$b -> $c,$d : ${"%.0f".format(ms)} ms ERROR ${it.message}") },
            )
        }
        graph?.close()
    }
}

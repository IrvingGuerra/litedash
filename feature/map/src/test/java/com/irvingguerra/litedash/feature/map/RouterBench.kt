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

/**
 * El mismo estado en LDM0 y en LDM1 (con dibujo) debe dar las mismas rutas (solo en la Mac): LITEDASH_LDM_COMPARE =
 * «ruta al LDM0:ruta al LDM1», LITEDASH_LDM_PAIRS = cuántos pares al azar dentro de la caja (y Toluca a mano).
 */
class LdmCompareBench {
    @Test
    fun sameRoutes() {
        val files = System.getenv("LITEDASH_LDM_COMPARE")?.split(":")?.map(::File) ?: return
        assumeTrue(files.size == 2 && files.all { it.isFile })
        val old = RoadGraph.open(files[0])!!
        val new = RoadGraph.open(files[1])!!
        // Denso (la Mac) y disperso (pantallas de poca memoria, como LD_A6): los dos contra los dos archivos.
        val dense = System.getenv("LITEDASH_LDM_SPARSE") == null
        val a = Router(old, dense, 1 shl 14)
        val b = Router(new, dense, 1 shl 14)
        val n = System.getenv("LITEDASH_LDM_PAIRS")?.toInt() ?: 40
        val rnd = java.util.Random(7)
        // Unos puntos de Toluca (donde salió «sin camino») y pares al azar junto a cruces del estado (a ≤ 300 m).
        val pairs = mutableListOf(
            doubleArrayOf(19.2925, -99.6569, 19.2650, -99.6100), doubleArrayOf(19.3300, -99.5600, 19.2860, -99.6750),
            doubleArrayOf(19.2570, -99.6370, 19.3060, -99.6500), doubleArrayOf(19.4040, -99.5950, 19.2890, -99.6530),
        )
        fun near(): Pair<Double, Double> {
            val v = rnd.nextInt(old.vertexCount)
            return old.latE6(v) / 1e6 + (rnd.nextDouble() - 0.5) * 0.005 to old.lonE6(v) / 1e6 + (rnd.nextDouble() - 0.5) * 0.005
        }
        repeat(n) {
            val (la, lo) = near()
            // La mitad cerca (≤ ~30 km, como en la ciudad) y la otra mitad a cualquier parte del estado.
            val (lb, lob) = if (it % 2 == 0) near() else {
                var p: Pair<Double, Double>
                do p = near() while (kotlin.math.abs(p.first - la) + kotlin.math.abs(p.second - lo) > 0.3)
                p
            }
            pairs += doubleArrayOf(la, lo, lb, lob)
        }
        var same = 0
        var diff = 0
        for (p in pairs) {
            val heading = rnd.nextInt(360).toFloat()
            for (h in listOf(null, heading)) {
                val x = a.route(p[0], p[1], p[2], p[3], h)
                val y = b.route(p[0], p[1], p[2], p[3], h)
                val ok = x.isSuccess == y.isSuccess && (x.isFailure || (x.getOrThrow().points.contentEquals(y.getOrThrow().points) && x.getOrThrow().seconds == y.getOrThrow().seconds))
                if (ok) same++ else diff++
                fun d(r: Result<Route>) = r.fold({ "%.2f km %.1f min".format(it.distanceM / 1000, it.seconds / 60) }, { "ERROR ${(it as? RouteException)?.error ?: it.message}" })
                println("${if (ok) "IGUAL" else "DISTINTA"} ${p.joinToString(",") { "%.5f".format(it) }} rumbo=$h : LDM0 ${d(x)} · LDM1 ${d(y)}")
            }
        }
        println("RESUMEN iguales=$same distintas=$diff")
        old.close()
        new.close()
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

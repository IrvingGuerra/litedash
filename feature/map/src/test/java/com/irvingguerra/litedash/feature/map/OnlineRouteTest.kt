package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OnlineRouteTest {
    // Respuestas reales de routing.openstreetmap.de (2026-11-24), guardadas tal cual.
    private fun sample(name: String) = javaClass.getResource("/osrm/$name")!!.readText()

    @Test
    fun decodesPolyline6() {
        // El ejemplo de la documentación de Google con 6 decimales: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
        val points = decodePolyline6("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI")
        assertArrayEquals(intArrayOf(38_500_000, -120_200_000, 40_700_000, -120_950_000, 43_252_000, -126_453_000), points)
        assertEquals(0, decodePolyline6("").size)
        // Cortada a la mitad: lo que se pudo leer completo.
        assertEquals(2, decodePolyline6("_izlhA~rlgdF_{ge").size)
    }

    @Test
    fun mapsOsrmManeuverTypes() {
        assertEquals(ManeuverType.RIGHT, osrmType("turn", "right"))
        assertEquals(ManeuverType.SHARP_LEFT, osrmType("turn", "sharp left"))
        assertEquals(ManeuverType.SLIGHT_LEFT, osrmType("merge", "slight left"))
        assertEquals(ManeuverType.SLIGHT_RIGHT, osrmType("off ramp", "slight right"))
        assertEquals(ManeuverType.RIGHT, osrmType("end of road", "right"))
        assertEquals(ManeuverType.LEFT, osrmType("fork", "left"))
        assertEquals(ManeuverType.UTURN, osrmType("continue", "uturn"))
        assertEquals(ManeuverType.STRAIGHT, osrmType("turn", "straight"))
        assertEquals(ManeuverType.STRAIGHT, osrmType("on ramp", null))
        assertEquals(ManeuverType.ROUNDABOUT, osrmType("roundabout", "right"))
        assertEquals(ManeuverType.ROUNDABOUT, osrmType("rotary", "left"))
        // La misma calle que solo cambia de nombre o sigue, salir de la glorieta y los avisos: nada que decir.
        assertNull(osrmType("new name", "straight"))
        assertNull(osrmType("continue", "slight right"))
        assertNull(osrmType("exit roundabout", "right"))
        assertNull(osrmType("notification", "straight"))
        assertEquals(ManeuverType.LEFT, osrmType("new name", "left"))
    }

    @Test
    fun parsesRouteWithRampsAndMerges() {
        val route = parseOsrm(sample("cdmx-reforma.json"))!!
        assertTrue(route.online)
        assertEquals(3861.9, route.distanceM, 1e-6)
        assertEquals(409.1, route.seconds, 1e-6)
        assertEquals(route.pointCount, route.atM.size)
        assertEquals(route.pointCount, route.atS.size)
        assertEquals(route.distanceM, route.atM.last(), 1e-6)
        assertEquals(route.seconds, route.atS.last(), 1e-6)
        // Metros y segundos que nunca bajan.
        for (i in 1 until route.pointCount) {
            assertTrue(route.atM[i] >= route.atM[i - 1])
            assertTrue(route.atS[i] >= route.atS[i - 1])
        }
        assertEquals("Calzada Manuel Villalongín", route.mainRoad)
        val types = route.maneuvers.map { it.type }
        assertEquals(ManeuverType.DEPART, types.first())
        assertEquals(ManeuverType.ARRIVE, types.last())
        // «new name» de Donato Guerra a Artículo 123 no es vuelta.
        assertTrue(route.maneuvers.none { it.road == "Calle Artículo 123" })
        val reforma = route.maneuvers.single { it.road == "Avenida Paseo de la Reforma" }
        assertEquals(ManeuverType.LEFT, reforma.type)
        // OSRM da 109° → 108° en ese cruce: la flecha sale del tipo.
        assertEquals(-90f, reforma.angle)
        val villalongin = route.maneuvers.single { it.road == "Calzada Manuel Villalongín" }
        assertEquals(ManeuverType.RIGHT, villalongin.type)
        assertEquals(90f, villalongin.angle)
        // La incorporación al Circuito Interior y la salida.
        assertTrue(route.maneuvers.any { it.type == ManeuverType.SLIGHT_LEFT && it.road == "Circuito Interior Melchor Ocampo" })
        // En orden sobre la línea y cada una en su punto.
        for (k in 1 until route.maneuvers.size) {
            assertTrue(route.maneuvers[k].point > route.maneuvers[k - 1].point)
            assertEquals(route.atM[route.maneuvers[k].point], route.maneuvers[k].atM, 1e-9)
        }
        assertEquals(route.pointCount - 1, route.maneuvers.last().point)
    }

    @Test
    fun keepsRoundaboutExits() {
        val route = parseOsrm(sample("gdl-minerva.json"))!!
        val turns = route.maneuvers.map { it.type }
        assertEquals(
            listOf(
                ManeuverType.DEPART, ManeuverType.RIGHT, ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.ROUNDABOUT,
                ManeuverType.ROUNDABOUT, ManeuverType.STRAIGHT, ManeuverType.SHARP_LEFT, ManeuverType.ARRIVE,
            ),
            turns,
        )
        val (small, minerva) = route.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }
        assertEquals(2, small.exit)
        // La calle es la de la salida.
        assertEquals("Calle Diagonal Golfo de Cortés", small.road)
        // La Minerva: salida 3, hacia la izquierda de por donde entras (del paso «exit rotary»).
        assertEquals(3, minerva.exit)
        assertEquals(-24f, minerva.angle, 0.5f)
        assertEquals("Avenida de los Arcos", route.maneuvers[7].road)
    }

    @Test
    fun roundedKeepsOnlineAndExits() {
        val route = parseOsrm(sample("gdl-minerva.json"))!!.rounded()
        assertTrue(route.online)
        assertEquals(listOf(2, 3), route.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }.map { it.exit })
        // La guía la sigue igual: al principio, la primera vuelta es la de Avenida Hidalgo.
        val s = Guidance(route).update(route.points[0] / 1e6, route.points[1] / 1e6)
        assertEquals("Avenida Hidalgo", route.maneuvers[s.next].road)
    }

    @Test
    fun rejectsErrorsAndJunk() {
        assertNull(parseOsrm("""{"code":"NoRoute","message":"Impossible route between points"}"""))
        assertNull(parseOsrm("no es json"))
        assertNull(parseOsrm("""{"code":"Ok","routes":[]}"""))
    }

    @Test
    fun thinsLongRoutesKeepingTurnsCoherent() {
        // Una carretera sintética de ~120 km con un punto cada ~1 m (lo que trae OSRM en tramos curvos) y 40 vueltas.
        val big = syntheticOsrm(points = 120_000, turns = 40)
        assertTrue(big.length < OnlineRoute.MAX_BYTES)
        val route = parseOsrm(big)!!
        // Cada ~3 m: un tercio de los puntos o menos.
        assertTrue("${route.pointCount}", route.pointCount < 120_000 / 2.5)
        assertEquals(route.pointCount, route.atM.size)
        assertEquals(route.pointCount, route.atS.size)
        assertEquals(route.distanceM, route.atM.last(), 1e-6)
        for (i in 1 until route.pointCount) assertTrue(route.atM[i] >= route.atM[i - 1] && route.atS[i] >= route.atS[i - 1])
        // Todas las vueltas siguen, cada una en su punto exacto de la línea y en orden.
        assertEquals(40 + 2, route.maneuvers.size)
        for (k in 1 until route.maneuvers.size) {
            val m = route.maneuvers[k]
            assertTrue(m.point > route.maneuvers[k - 1].point)
            assertEquals(route.atM[m.point], m.atM, 1e-9)
        }
        for (k in 1..40) {
            val m = route.maneuvers[k]
            assertEquals(turnLat(k), route.points[2 * m.point])
        }
        // Al suavizar no se dispara: los puntos siguen del mismo orden.
        val r = route.rounded()
        assertTrue(r.pointCount < route.pointCount * 2)
        assertEquals(42, r.maneuvers.size)
    }

    @Test
    fun thinnedKeepsMarkedPointsAndEnds() {
        // Cinco puntos a ~1 m: se quedan la punta, el marcado (3) y el último.
        val line = intArrayOf(0, 0, 9, 0, 18, 0, 27, 0, 36, 0)
        val keep = intArrayOf(0, 3, 4)
        val out = thinned(line, keep, 3.0)
        assertArrayEquals(intArrayOf(0, 0, 27, 0, 36, 0), out)
        assertArrayEquals(intArrayOf(0, 1, 2), keep)
    }

    @Test
    fun readsOnlyUpToTheCap() {
        val text = "x".repeat(10_000)
        assertEquals(text, readCapped(ByteArrayInputStream(text.toByteArray()), 10_000))
        assertNull(readCapped(ByteArrayInputStream(text.toByteArray()), 9_999))
    }

    @Test
    fun keyNormalizesHeading() {
        val key = { h: Float? -> routeKey(19.0, -99.0, 20.0, -100.0, h) }
        assertEquals(key(350f), key(-10f))
        assertEquals(key(320f), key(-400f))
        assertEquals(key(0f), key(360f))
        assertEquals(key(10f), key(370f))
        assertNotEquals(key(90f), key(-90f))
        assertNotEquals(key(null), key(0f))
    }

    @Test
    fun sharedCallsRunOnceForTheSameKey() {
        val calls = SharedCalls<String>()
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = arrayOfNulls<String>(2)
        val first = thread {
            results[0] = calls.run("a", 5_000) {
                runs.incrementAndGet()
                started.countDown()
                release.await()
                "ruta"
            }
        }
        started.await()
        val second = thread { results[1] = calls.run("a", 5_000) { runs.incrementAndGet(); "otra" } }
        // El segundo ya espera al primero.
        Thread.sleep(100)
        release.countDown()
        first.join()
        second.join()
        assertEquals(1, runs.get())
        assertEquals(listOf("ruta", "ruta"), results.toList())
        // Ya terminado, uno nuevo sí corre.
        assertEquals("otra", calls.run("a", 5_000) { "otra" })
    }

    /** Latitud (millonésimas) de la vuelta [k] de [syntheticOsrm]. */
    private fun turnLat(k: Int) = 19_000_000 + k * 2_900 * 9

    /**
     * Una respuesta de OSRM inventada: [points] puntos hacia el norte cada 9 millonésimas (~1 m), una vuelta cada
     * `points / (turns + 1)` puntos (en el punto exacto [turnLat]).
     */
    private fun syntheticOsrm(points: Int, turns: Int): String {
        val every = 2_900
        require(every * (turns + 1) <= points)
        val lats = IntArray(points) { 19_000_000 + it * 9 }
        val lons = IntArray(points) { -99_000_000 + (it / every) }
        val geometry = encodePolyline6(lats, lons)
        val meters = points * 1.0
        val steps = buildString {
            append("""{"maneuver":{"type":"depart","location":[${lons[0] / 1e6},${lats[0] / 1e6}]},"name":"Salida","duration":1,"distance":1}""")
            for (k in 1..turns) {
                val i = k * every
                append(""",{"maneuver":{"type":"turn","modifier":"${if (k % 2 == 0) "left" else "right"}","location":[${lons[i] / 1e6},${lats[i] / 1e6}]},"name":"Calle $k","duration":100,"distance":${every}}""")
            }
            append(""",{"maneuver":{"type":"arrive","location":[${lons.last() / 1e6},${lats.last() / 1e6}]},"name":"","duration":0,"distance":0}""")
        }
        return """{"code":"Ok","routes":[{"geometry":"$geometry","distance":$meters,"duration":${turns * 100 + 1},"legs":[{"steps":[$steps]}]}]}"""
    }

    private fun encodePolyline6(lats: IntArray, lons: IntArray): String {
        val out = StringBuilder()
        fun put(v: Int) {
            var n = if (v < 0) (v shl 1).inv() else v shl 1
            while (n >= 0x20) {
                out.append(((0x20 or (n and 0x1f)) + 63).toChar())
                n = n ushr 5
            }
            out.append((n + 63).toChar())
        }
        var lat = 0
        var lon = 0
        for (i in lats.indices) {
            put(lats[i] - lat)
            put(lons[i] - lon)
            lat = lats[i]
            lon = lons[i]
        }
        return out.toString()
    }
}

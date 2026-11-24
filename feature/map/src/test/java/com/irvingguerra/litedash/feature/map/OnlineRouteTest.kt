package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

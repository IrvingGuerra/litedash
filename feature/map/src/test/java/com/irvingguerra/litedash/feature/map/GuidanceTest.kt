package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {
    // Una ruta en L: 3 puntos al norte (~111 m cada tramo) y luego 2 al este, con la vuelta a la derecha en el punto 2.
    private val lat0 = 19.0
    private val lon0 = -99.0
    private val step = 0.001
    private val points = intArrayOf(
        e6(lat0), e6(lon0),
        e6(lat0 + step), e6(lon0),
        e6(lat0 + 2 * step), e6(lon0),
        e6(lat0 + 2 * step), e6(lon0 + step),
        e6(lat0 + 2 * step), e6(lon0 + 2 * step),
    )
    private val atM = DoubleArray(5) { i -> (0 until i).sumOf { flatMeters(points, it, it + 1) } }
    private val atS = DoubleArray(5) { atM[it] / 10 }
    private val route = Route(
        points, atM.last(), atS.last(), "",
        listOf(
            Maneuver(ManeuverType.DEPART, 0f, "Norte", 0, 0.0),
            Maneuver(ManeuverType.RIGHT, 90f, "Oriente", 2, atM[2]),
            Maneuver(ManeuverType.ARRIVE, 0f, "", 4, atM[4]),
        ),
        atM, atS,
    )

    @Test
    fun followsTheRouteAndPointsToTheNextTurn() {
        val g = Guidance(route)
        val s = g.update(lat0 + 0.5 * step, lon0)
        assertEquals(1, s.next)
        assertEquals(atM[2] - s.doneM, s.toNextM, 0.5)
        assertEquals(route.distanceM - s.doneM, s.leftM, 0.5)
        assertFalse(s.offRoute)
        // Al norte: el rumbo de la ruta ahí.
        assertEquals(0f, s.heading!!, 1f)
        // Pasando la vuelta, la próxima es la llegada y va al oriente.
        val after = g.update(lat0 + 2 * step, lon0 + 0.5 * step)
        assertEquals(2, after.next)
        assertEquals(90f, after.heading!!, 2f)
    }

    @Test
    fun offRouteNeedsTwoReadings() {
        val g = Guidance(route)
        g.update(lat0 + 0.5 * step, lon0)
        // ~200 m al oeste de la ruta.
        assertFalse(g.update(lat0 + 0.5 * step, lon0 - 0.002).offRoute)
        assertTrue(g.update(lat0 + 0.6 * step, lon0 - 0.002).offRoute)
        // Al volver, ya no.
        assertFalse(g.update(lat0 + 0.7 * step, lon0).offRoute)
    }

    @Test
    fun aBadGpsFixDoesNotCountAsLeaving() {
        val g = Guidance(route)
        g.update(lat0 + 0.5 * step, lon0)
        // 60 m fuera pero con 50 m de error: no es salirse.
        g.update(lat0 + 0.5 * step, lon0 - 0.00057, 50f)
        assertFalse(g.update(lat0 + 0.5 * step, lon0 - 0.00057, 50f).offRoute)
    }

    @Test
    fun arrivesAtTheEnd() {
        val g = Guidance(route)
        g.update(lat0 + 0.5 * step, lon0)
        g.update(lat0 + 2 * step, lon0 + 0.5 * step)
        val s = g.update(lat0 + 2 * step, lon0 + 1.9 * step)
        assertTrue(s.arrived)
        assertEquals(0.0, s.leftS, 15.0)
    }

    @Test
    fun outAndBackDoesNotJumpToTheWayBack() {
        // 300 m al norte y de regreso por la misma calle (rodear una manzana o un retorno).
        val pts = intArrayOf(e6(lat0), e6(lon0), e6(lat0 + 0.0027), e6(lon0), e6(lat0), e6(lon0 + 0.00001))
        val m = DoubleArray(3) { i -> (0 until i).sumOf { flatMeters(pts, it, it + 1) } }
        val r = Route(
            pts, m.last(), m.last() / 10, "",
            listOf(Maneuver(ManeuverType.DEPART, 0f, "", 0, 0.0), Maneuver(ManeuverType.UTURN, 180f, "", 1, m[1]), Maneuver(ManeuverType.ARRIVE, 0f, "", 2, m[2])),
            m, DoubleArray(3) { m[it] / 10 },
        )
        val g = Guidance(r)
        val s = g.update(lat0 + 0.0003, lon0)
        // Va de ida (unos 33 m hechos), no en el regreso, y la próxima es la vuelta.
        assertEquals(33.0, s.doneM, 10.0)
        assertEquals(1, s.next)
        assertFalse(s.arrived)
    }

    @Test
    fun aTwoPointRouteWorks() {
        val pts = intArrayOf(e6(lat0), e6(lon0), e6(lat0 + 0.001), e6(lon0))
        val d = flatMeters(pts, 0, 1)
        val r = Route(
            pts, d, d / 10, "", listOf(Maneuver(ManeuverType.DEPART, 0f, "", 0, 0.0), Maneuver(ManeuverType.ARRIVE, 0f, "", 1, d)),
            doubleArrayOf(0.0, d), doubleArrayOf(0.0, d / 10),
        )
        val g = Guidance(r)
        assertEquals(1, g.update(lat0 + 0.0002, lon0).next)
        assertTrue(g.update(lat0 + 0.00099, lon0).arrived)
    }

    private fun e6(d: Double) = Math.round(d * 1e6).toInt()
}

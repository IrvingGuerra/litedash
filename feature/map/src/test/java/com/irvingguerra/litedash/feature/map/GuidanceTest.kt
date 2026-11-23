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
    fun theMarginGrowsWithTheGpsErrorUpToALimit() {
        assertEquals(30.0, Guidance.offRouteLimit(null), 0.01)
        assertEquals(30.0, Guidance.offRouteLimit(5f), 0.01)
        assertEquals(72.0, Guidance.offRouteLimit(40f), 0.01)
        assertEquals(90.0, Guidance.offRouteLimit(100f), 0.01)
        // Con buen GPS, 40 m fuera ya cuenta (antes el margen era 45 m).
        val g = Guidance(route)
        g.update(lat0 + 0.5 * step, lon0, 5f)
        g.update(lat0 + 0.5 * step, lon0 - 0.00038, 5f)
        assertTrue(g.update(lat0 + 0.6 * step, lon0 - 0.00038, 5f).offRoute)
    }

    @Test
    fun aVeryImpreciseFixFarBeyondItsErrorStillCountsAsLeaving() {
        // Pantalla barata: 80 m de error todo el tiempo. A ~200 m de la ruta durante 10 s: fuera y recálculo, como en la 1.5.14.
        val g = Guidance(route)
        g.update(lat0 + 0.2 * step, lon0, 80f, 10f, 0f, 0L)
        var off = false
        for (k in 1..10) {
            val s = g.update(lat0 + (0.2 + 0.05 * k) * step, lon0 - 0.0019, 80f, 10f, 0f, k * 1_000L)
            if (k >= 2) assertTrue("lectura $k", s.offRoute)
            off = off || s.offRoute
        }
        assertTrue(off)
    }

    @Test
    fun anImpreciseFixWithinItsMarginDoesNotRecalculate() {
        // 80 m de error y a ~60 m de la ruta (dentro del margen) durante 10 s: sigues en la ruta.
        val g = Guidance(route)
        g.update(lat0 + 0.2 * step, lon0, 80f, 10f, 0f, 0L)
        for (k in 1..10) {
            assertFalse(g.update(lat0 + (0.2 + 0.05 * k) * step, lon0 - 0.00057, 80f, 10f, 0f, k * 1_000L).offRoute)
        }
    }

    @Test
    fun anImpreciseFixWithinItsOwnErrorWaitsAFewReadingsButNeverSticks() {
        // 80 m de error y a ~100 m (fuera del margen de 90 m, dentro de su error de 120 m): una suelta no decide y te
        // quedas donde ibas; si se repite, a las pocas lecturas cuenta como fuera (nunca pegado a la ruta vieja).
        val g = Guidance(route)
        val before = g.update(lat0 + 0.5 * step, lon0, 80f)
        val first = g.update(lat0 + 0.5 * step, lon0 - 0.00095, 80f)
        assertFalse(first.offRoute)
        assertEquals(before.doneM, first.doneM, 0.5)
        var readings = 1
        while (!g.update(lat0 + 0.5 * step, lon0 - 0.00095, 80f).offRoute) readings++
        assertTrue("lecturas: $readings", readings <= 5)
        assertTrue(Guidance.isVague(100.0, 80f))
        assertFalse(Guidance.isVague(130.0, 80f))
        assertFalse(Guidance.isVague(100.0, 40f))
    }

    @Test
    fun anImpossibleJumpAheadIsIgnoredUnlessItRepeats() {
        // 2 km derecho al norte, un punto cada ~111 m, con una vuelta a los ~555 m (punto 5).
        val pts = IntArray(38) { k -> if (k % 2 == 0) e6(lat0 + (k / 2) * step) else e6(lon0) }
        val m = DoubleArray(19) { i -> (0 until i).sumOf { flatMeters(pts, it, it + 1) } }
        val r = Route(
            pts, m.last(), m.last() / 10, "",
            listOf(Maneuver(ManeuverType.DEPART, 0f, "", 0, 0.0), Maneuver(ManeuverType.STRAIGHT, 0f, "", 5, m[5]), Maneuver(ManeuverType.ARRIVE, 0f, "", 18, m[18])),
            m, DoubleArray(19) { m[it] / 10 },
        )
        val g = Guidance(r)
        g.update(lat0 + 1 * step, lon0, 5f, 10f, 0f, 0L)
        // Una lectura mala ~1 km adelante, un segundo después: no se salta la vuelta de en medio ni cuenta como salirse.
        val bad = g.update(lat0 + 10 * step, lon0, 5f, 10f, 0f, 1_000L)
        assertFalse(bad.offRoute)
        assertEquals(1, bad.next)
        assertEquals(m[1], bad.doneM, 5.0)
        // La siguiente, de vuelta donde vas: sigue sobre la ruta, sin recálculo.
        val good = g.update(lat0 + 1.1 * step, lon0, 5f, 10f, 0f, 2_000L)
        assertFalse(good.offRoute)
        assertEquals(1, good.next)
        // Si el brinco se repite (salías de un túnel), se cree.
        g.update(lat0 + 10 * step, lon0, 5f, 10f, 0f, 3_000L)
        val tunnel = g.update(lat0 + 10.1 * step, lon0, 5f, 10f, 0f, 4_000L)
        assertEquals(m[10], tunnel.doneM, 20.0)
        assertEquals(2, tunnel.next)
    }

    @Test
    fun goingAgainstTheRouteCountsAsLeaving() {
        val g = Guidance(route)
        g.update(lat0 + 0.5 * step, lon0, 5f, 8f, 0f, 0L)
        // ~20 m al oriente (dentro del margen) yendo al sur, contra la ruta: a las dos lecturas, recalcula.
        g.update(lat0 + 0.45 * step, lon0 + 0.00019, 5f, 8f, 180f, 1_000L)
        assertTrue(g.update(lat0 + 0.4 * step, lon0 + 0.00019, 5f, 8f, 180f, 2_000L).offRoute)
        // En el mismo lugar pero a favor de la ruta, no.
        val h = Guidance(route)
        h.update(lat0 + 0.4 * step, lon0, 5f, 8f, 0f, 0L)
        h.update(lat0 + 0.45 * step, lon0 + 0.00019, 5f, 8f, 0f, 1_000L)
        assertFalse(h.update(lat0 + 0.5 * step, lon0 + 0.00019, 5f, 8f, 0f, 2_000L).offRoute)
        assertEquals(180.0, Guidance.angleBetween(10.0, 190.0), 0.01)
        assertEquals(20.0, Guidance.angleBetween(350.0, 10.0), 0.01)
    }

    @Test
    fun aWideTurnDoesNotCountAsGoingAgainstTheRoute() {
        // Dando la vuelta a la derecha en una avenida ancha: a ~20 m de la línea, ya más cerca del tramo al oriente, con
        // el rumbo todavía al norte (90° contra el tramo nuevo). Cerca de la vuelta no cuenta.
        val g = Guidance(route)
        g.update(lat0 + 1.8 * step, lon0, 5f, 8f, 0f, 0L)
        g.update(lat0 + 2 * step - 0.00018, lon0 + 0.0003, 5f, 8f, 0f, 1_000L)
        assertFalse(g.update(lat0 + 2 * step - 0.00018, lon0 + 0.00035, 5f, 8f, 0f, 2_000L).offRoute)
    }

    @Test
    fun theRoutesOwnUTurnDoesNotCountAsGoingAgainstIt() {
        // 300 m al norte y de regreso por el carril de junto (~15 m): al dar la vuelta en U y regresar, no recalcula.
        val pts = intArrayOf(e6(lat0), e6(lon0), e6(lat0 + 0.0027), e6(lon0), e6(lat0 + 0.0027), e6(lon0 + 0.00014), e6(lat0), e6(lon0 + 0.00014))
        val m = DoubleArray(4) { i -> (0 until i).sumOf { flatMeters(pts, it, it + 1) } }
        val r = Route(
            pts, m.last(), m.last() / 10, "",
            listOf(Maneuver(ManeuverType.DEPART, 0f, "", 0, 0.0), Maneuver(ManeuverType.UTURN, 180f, "", 1, m[1]), Maneuver(ManeuverType.ARRIVE, 0f, "", 3, m[3])),
            m, DoubleArray(4) { m[it] / 10 },
        )
        val g = Guidance(r)
        var t = 0L
        for (k in 0..26) assertFalse(g.update(lat0 + k * 0.0001, lon0, 5f, 8f, 0f, t++ * 1_000).offRoute)
        for (k in 26 downTo 20) assertFalse("regreso $k", g.update(lat0 + k * 0.0001, lon0 + 0.00014, 5f, 8f, 180f, t++ * 1_000).offRoute)
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

    @Test
    fun roundedRouteCurvesTheCornerAndKeepsItsTurn() {
        val r = route.rounded()
        assertTrue(r.pointCount > route.pointCount)
        // Empieza y acaba igual, y ya no pasa por el pico de la esquina.
        assertEquals(points[0], r.points[0]); assertEquals(points[9], r.points[r.points.size - 1])
        assertFalse((0 until r.pointCount).any { r.points[2 * it] == points[4] && r.points[2 * it + 1] == points[5] })
        // Un poco más corta (corta la esquina), con los metros y segundos crecientes y su total al final.
        assertTrue(r.distanceM < route.distanceM && r.distanceM > route.distanceM - 15)
        for (i in 1 until r.pointCount) assertTrue(r.atM[i] >= r.atM[i - 1] && r.atS[i] >= r.atS[i - 1])
        assertEquals(r.distanceM, r.atM.last(), 0.001)
        // La vuelta sigue ahí, a media curva: cerca de la esquina de antes.
        val turn = r.maneuvers[1]
        assertEquals(ManeuverType.RIGHT, turn.type)
        assertEquals(atM[2], turn.atM, 10.0)
        assertEquals(r.pointCount - 1, r.maneuvers.last().point)
        // Sobre la curva la guía no se sale de la ruta y gira poco a poco.
        val s = Guidance(r).update(r.points[2 * turn.point] / 1e6, r.points[2 * turn.point + 1] / 1e6)
        assertFalse(s.offRoute)
        assertTrue(s.heading!! in 20f..70f)
    }
}

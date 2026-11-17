package com.irvingguerra.litedash.feature.map

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Cómo vas en la ruta: cuánto llevas ([doneM], y en qué punto vas: [point] más [fraction] hacia el siguiente), la
 * próxima vuelta y la de después ([next], [then]; índices de [Route.maneuvers]), a cuántos metros está la próxima,
 * lo que falta ([leftM], [leftS]), si te saliste de la ruta ([offRoute]) y si ya llegaste.
 */
data class GuidanceState(
    val doneM: Double,
    val point: Int,
    val fraction: Float,
    val next: Int,
    val then: Int?,
    val toNextM: Double,
    val leftM: Double,
    val leftS: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** Tu posición puesta sobre la ruta (fuera de ella, la del GPS) y el rumbo de la ruta ahí (grados desde el norte). */
    val lat: Double,
    val lon: Double,
    val heading: Float?,
)

/**
 * Sigue tu avance sobre [route] con cada lectura del GPS: te pone sobre la ruta (buscando cerca de donde ibas, nunca muy
 * atrás), dice la próxima vuelta y si te saliste (más de [OFF_ROUTE_M] lejos en dos lecturas seguidas). Sin estado
 * compartido: un objeto por ruta, desde un solo hilo.
 */
class Guidance(val route: Route) {
    private var segment = 0
    private var offCount = 0
    private var arrived = false
    private var lastDoneM = 0.0

    /** Tu posición nueva ([lat], [lon]) con su precisión en metros ([accuracyM], null si no se sabe). */
    fun update(lat: Double, lon: Double, accuracyM: Float? = null): GuidanceState {
        val p = route.points
        val n = route.pointCount
        val kx = cos(Math.toRadians(lat)) * RoadGraph.M_PER_DEG
        // Busca desde un poco antes de donde ibas hasta unos km adelante (en un alto o tras perder la señal).
        val from = (segment - BACK_SEGMENTS).coerceAtLeast(0)
        var to = segment
        while (to < n - 2 && route.atM[to] < lastDoneM + AHEAD_M) to++
        var best = segment
        var bestF = 0.0
        var bestD = Double.MAX_VALUE
        var bestScore = Double.MAX_VALUE
        for (i in from..to) {
            val ay = p[2 * i] / 1e6
            val ax = p[2 * i + 1] / 1e6
            val dy = (p[2 * i + 2] / 1e6 - ay) * RoadGraph.M_PER_DEG
            val dx = (p[2 * i + 3] / 1e6 - ax) * kx
            val py = (lat - ay) * RoadGraph.M_PER_DEG
            val px = (lon - ax) * kx
            val len2 = dx * dx + dy * dy
            val f = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
            val ex = px - f * dx
            val ey = py - f * dy
            val d = sqrt(ex * ex + ey * ey)
            // Lo que está más adelante de lo esperado cuenta como más lejos: en una calle de ida y vuelta (o al rodear una
            // manzana) el tramo de regreso queda encima del de ida y no debe ganar.
            val along = route.atM[i] + (route.atM[i + 1] - route.atM[i]) * f
            val score = d + AHEAD_PENALTY * (along - lastDoneM - EXPECTED_AHEAD_M).coerceAtLeast(0.0)
            if (score < bestScore) {
                bestScore = score
                bestD = d
                best = i
                bestF = f
            }
        }
        // Un GPS impreciso no cuenta como salirse: el margen crece con su error.
        val margin = OFF_ROUTE_M + (accuracyM ?: 0f).coerceAtMost(MAX_ACCURACY_SLACK)
        if (bestD > margin) offCount++ else {
            offCount = 0
            segment = best
        }
        val i = segment
        val f = if (bestD > margin) 0.0 else bestF
        val doneM = route.atM[i] + (route.atM[i + 1] - route.atM[i]) * f
        if (bestD <= margin) lastDoneM = doneM
        val doneS = route.atS[i] + (route.atS[i + 1] - route.atS[i]) * f
        val leftM = (route.distanceM - doneM).coerceAtLeast(0.0)
        if (leftM < ARRIVE_M) arrived = true
        val maneuvers = route.maneuvers
        // La próxima: la primera que queda adelante (la salida no cuenta una vez en camino).
        var next = maneuvers.indexOfFirst { it.atM > doneM - PASSED_M && it.type != ManeuverType.DEPART }
        if (next < 0) next = maneuvers.lastIndex
        val then = (next + 1).takeIf { it < maneuvers.size }
        // Sobre la ruta: el punto de la ruta donde vas y su rumbo (más estables que el GPS, que brinca y a veces no da rumbo).
        val onRoute = bestD <= margin
        val sLat = if (onRoute) (p[2 * i] + (p[2 * i + 2] - p[2 * i]) * f) / 1e6 else lat
        val sLon = if (onRoute) (p[2 * i + 1] + (p[2 * i + 3] - p[2 * i + 1]) * f) / 1e6 else lon
        val heading = if (onRoute && flatMeters(p, i, i + 1) > 0.5) bearing(p, i, i + 1).toFloat() else null
        return GuidanceState(
            doneM, i, f.toFloat(), next, then, (maneuvers[next].atM - doneM).coerceAtLeast(0.0),
            leftM, (route.seconds - doneS).coerceAtLeast(0.0), offCount >= OFF_ROUTE_READINGS, arrived, sLat, sLon, heading,
        )
    }

    companion object {
        /** Más lejos que esto de la ruta (metros) se cuenta como salirse. */
        const val OFF_ROUTE_M = 40.0

        /** Lecturas seguidas fuera de la ruta para recalcular (una sola puede ser un brinco del GPS). */
        private const val OFF_ROUTE_READINGS = 2

        /** Lo más que el error del GPS agranda el margen. */
        private const val MAX_ACCURACY_SLACK = 40f

        /** A menos de esto del destino, llegaste. */
        private const val ARRIVE_M = 30.0

        /** Una vuelta a menos de esto atrás ya pasó. */
        private const val PASSED_M = 5.0

        private const val BACK_SEGMENTS = 3

        /** Hasta dónde adelante se busca (metros sobre la ruta). */
        private const val AHEAD_M = 3_000.0

        /** Lo que se puede avanzar entre una lectura y otra sin castigo (metros), y el castigo por metro de más. */
        private const val EXPECTED_AHEAD_M = 150.0
        private const val AHEAD_PENALTY = 0.2
    }
}

package com.irvingguerra.litedash.feature.map

import kotlin.math.abs
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
 * atrás), dice la próxima vuelta y si te saliste (dos lecturas seguidas fuera). Sin estado compartido: un objeto por
 * ruta, desde un solo hilo.
 *
 * Cuándo cuenta como salirse (idea tomada de NavigationPolicy.kt de Horizon Maps, GPL-3.0, adaptada): el margen es
 * max(30 m, error × 1.5 + 12 m) con tope; una lectura con error de más de [UNRELIABLE_ACCURACY_M] solo se ignora si la
 * ruta cae dentro de su propio error (error × 1.5), y aun así, si se repite [VAGUE_READINGS] veces seguidas, cuenta (en
 * pantallas que siempre dicen 50–100 m nunca debe quedarse pegada a la ruta vieja); un brinco hacia adelante imposible
 * para tu velocidad en ese tiempo se descarta (salvo que se repita); y si ya vas algo fuera de la ruta en sentido
 * contrario a ella, lejos de cualquier vuelta, cuenta. Lo nuestro se queda: el castigo por adelantarse (calles de ida y
 * vuelta) y las dos lecturas seguidas.
 */
class Guidance(val route: Route) {
    private var segment = 0
    private var lastF = 0.0
    private var offCount = 0
    /** Lecturas imprecisas seguidas fuera del margen pero dentro de su propio error. */
    private var vagueCount = 0
    private var arrived = false
    private var lastDoneM = 0.0
    /** Cuándo fue la última lectura buena sobre la ruta (ms; null = aún ninguna o sin hora). */
    private var lastOnRouteAt: Long? = null
    /** Un brinco hacia adelante visto una vez (dónde caía sobre la ruta), para creerlo si se repite. */
    private var pendingJumpM = Double.NaN

    /**
     * Tu posición nueva ([lat], [lon]) con su precisión en metros ([accuracyM]), tu velocidad ([speedMps]), tu rumbo
     * ([headingDeg], grados desde el norte) y la hora de la lectura ([timeMs], de un reloj que no brinca); lo que no se
     * sepa va en null y esa regla no aplica.
     */
    fun update(
        lat: Double, lon: Double, accuracyM: Float? = null,
        speedMps: Float? = null, headingDeg: Float? = null, timeMs: Long? = null,
    ): GuidanceState {
        val p = route.points
        val n = route.pointCount
        val kx = cos(Math.toRadians(lat)) * RoadGraph.M_PER_DEG
        // Busca desde un poco antes de donde ibas (unos puntos y al menos [BACK_M] metros) hasta unos km adelante (en un
        // alto o tras perder la señal).
        var from = (segment - BACK_SEGMENTS).coerceAtLeast(0)
        while (from > 0 && route.atM[from] > lastDoneM - BACK_M) from--
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
        val margin = offRouteLimit(accuracyM)
        val bestAlong = route.atM[best] + (route.atM[best + 1] - route.atM[best]) * bestF
        // Un brinco hacia adelante más largo de lo que da tu velocidad en ese tiempo: una lectura mala. Se descarta una
        // vez; si la siguiente cae igual de adelante, era de verdad (p. ej. tras un túnel).
        val last = lastOnRouteAt
        val jump = bestD <= margin && timeMs != null && last != null &&
            isProgressJump(bestAlong, lastDoneM, (speedMps ?: 0f).toDouble(), (timeMs - last) / 1000.0)
        val confirmedJump = jump && !pendingJumpM.isNaN() && abs(bestAlong - pendingJumpM) < JUMP_AGREE_M
        pendingJumpM = if (jump && !confirmedJump) bestAlong else Double.NaN
        val rejectedJump = jump && !confirmedJump
        // Vas en sentido contrario a la ruta y ya algo fuera de ella (diste vuelta en U o tomaste la calle de junto). No
        // cerca de una vuelta: en avenidas anchas, al dar la vuelta, el tramo más cercano ya es el siguiente mientras tu
        // rumbo sigue en el anterior (y en la vuelta en U de la propia ruta, ida y regreso quedan encimados).
        val against = headingDeg != null && speedMps != null && flatMeters(p, best, best + 1) > 0.5 &&
            !nearManeuver(bestAlong) &&
            headingAway(bestD, speedMps.toDouble(), angleBetween(headingDeg.toDouble(), bearing(p, best, best + 1)))
        val onRoute = bestD <= margin && !rejectedJump && !against
        // Una lectura muy imprecisa con la ruta dentro de su propio error no decide... salvo que se repita.
        val vague = !onRoute && !rejectedJump && !against && isVague(bestD, accuracyM) && ++vagueCount < VAGUE_READINGS
        when {
            onRoute -> {
                offCount = 0
                vagueCount = 0
                segment = best
                lastF = bestF
                lastDoneM = bestAlong
                if (timeMs != null) lastOnRouteAt = timeMs
            }
            // Un brinco sin confirmar o una lectura imprecisa suelta no deciden nada: te quedas donde ibas.
            vague || rejectedJump -> Unit
            else -> {
                offCount++
                // Ya cuenta como fuera: la siguiente imprecisa también (como en la 1.5.14, dos lecturas = recálculo).
                if (!rejectedJump) vagueCount = VAGUE_READINGS
            }
        }
        // Dónde se te muestra: sobre la ruta donde ibas (o vas); si te saliste, donde dice el GPS.
        val shownOnRoute = onRoute || vague || rejectedJump
        val i = segment
        val f = if (shownOnRoute) lastF else 0.0
        val doneM = route.atM[i] + (route.atM[i + 1] - route.atM[i]) * f
        val doneS = route.atS[i] + (route.atS[i + 1] - route.atS[i]) * f
        val leftM = (route.distanceM - doneM).coerceAtLeast(0.0)
        if (leftM < ARRIVE_M) arrived = true
        val maneuvers = route.maneuvers
        // La próxima: la primera que queda adelante (la salida no cuenta una vez en camino).
        var next = maneuvers.indexOfFirst { it.atM > doneM - PASSED_M && it.type != ManeuverType.DEPART }
        if (next < 0) next = maneuvers.lastIndex
        val then = (next + 1).takeIf { it < maneuvers.size }
        // Sobre la ruta: el punto de la ruta donde vas y su rumbo (más estables que el GPS, que brinca y a veces no da rumbo).
        val sLat = if (shownOnRoute) (p[2 * i] + (p[2 * i + 2] - p[2 * i]) * f) / 1e6 else lat
        val sLon = if (shownOnRoute) (p[2 * i + 1] + (p[2 * i + 3] - p[2 * i + 1]) * f) / 1e6 else lon
        val heading = if (shownOnRoute && flatMeters(p, i, i + 1) > 0.5) bearing(p, i, i + 1).toFloat() else null
        return GuidanceState(
            doneM, i, f.toFloat(), next, then, (maneuvers[next].atM - doneM).coerceAtLeast(0.0),
            leftM, (route.seconds - doneS).coerceAtLeast(0.0), offCount >= OFF_ROUTE_READINGS, arrived, sLat, sLon, heading,
        )
    }

    /** ¿A [alongM] metros de la ruta hay una vuelta (o una vuelta en U) cerca? */
    private fun nearManeuver(alongM: Double): Boolean = route.maneuvers.any {
        it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE && abs(it.atM - alongM) < MANEUVER_GUARD_M
    }

    companion object {
        /** Más lejos que esto de la ruta (metros) cuenta como salirse con buen GPS; crece con su error ([offRouteLimit]). */
        private const val OFF_ROUTE_M = 30.0

        /** Lo más que crece el margen por el error del GPS (con más, la guía no notaría que te saliste). */
        private const val MAX_OFF_ROUTE_M = 90.0

        /** Sin dato de precisión, como un buen GPS. */
        private const val GOOD_ACCURACY_M = 8.0

        /** Una lectura con más error que esto (metros) no decide si te saliste mientras la ruta caiga dentro de su error. */
        private const val UNRELIABLE_ACCURACY_M = 45f

        /** Lecturas imprecisas seguidas (fuera del margen, dentro de su error) que ya cuentan como fuera. */
        private const val VAGUE_READINGS = 4

        /** A menos de esto (metros sobre la ruta) de una vuelta, el rumbo contrario no cuenta como salirse. */
        private const val MANEUVER_GUARD_M = 80.0

        /** Lecturas seguidas fuera de la ruta para recalcular (una sola puede ser un brinco del GPS). */
        private const val OFF_ROUTE_READINGS = 2

        /** A menos de esto del destino, llegaste. */
        private const val ARRIVE_M = 30.0

        /** Una vuelta a menos de esto atrás ya pasó. */
        private const val PASSED_M = 5.0

        /** Hacia atrás se busca al menos esto: puntos y metros (en las curvas redondeadas hay un punto cada 2.5 m). */
        private const val BACK_SEGMENTS = 3
        private const val BACK_M = 60.0

        /** Hasta dónde adelante se busca (metros sobre la ruta). */
        private const val AHEAD_M = 3_000.0

        /** Lo que se puede avanzar entre una lectura y otra sin castigo (metros), y el castigo por metro de más. */
        private const val EXPECTED_AHEAD_M = 150.0
        private const val AHEAD_PENALTY = 0.2

        /** Dos brincos que caen a menos de esto uno del otro (metros sobre la ruta) se creen. */
        private const val JUMP_AGREE_M = 150.0

        /** El margen (metros) para contar como fuera de la ruta con un error del GPS de [accuracyM]. */
        internal fun offRouteLimit(accuracyM: Float?): Double {
            val acc = accuracyM?.takeIf { it > 0f }?.toDouble() ?: GOOD_ACCURACY_M
            return maxOf(OFF_ROUTE_M, acc * 1.5 + 12.0).coerceAtMost(MAX_OFF_ROUTE_M)
        }

        /**
         * ¿Una lectura a [distanceM] de la ruta con error [accuracyM] es tan imprecisa que la ruta cae dentro de su propio
         * error? (Más lejos que eso, cuenta como fuera aunque sea imprecisa.)
         */
        internal fun isVague(distanceM: Double, accuracyM: Float?): Boolean =
            accuracyM != null && accuracyM > UNRELIABLE_ACCURACY_M &&
                distanceM <= maxOf(offRouteLimit(accuracyM), accuracyM * 1.5)

        /** ¿Avanzar de [trustedM] a [candidateM] metros en [elapsedS] a [speedMps] es imposible? (Holgura: al menos 400 m.) */
        internal fun isProgressJump(candidateM: Double, trustedM: Double, speedMps: Double, elapsedS: Double): Boolean {
            if (elapsedS < 0) return false
            return candidateM > trustedM + maxOf(400.0, speedMps * 1.6 * elapsedS + 250.0)
        }

        /** ¿A [distanceM] de la ruta, a [speedMps] y con [angleDeg] de diferencia contra ella, vas en otro sentido? */
        internal fun headingAway(distanceM: Double, speedMps: Double, angleDeg: Double): Boolean =
            distanceM >= 15.0 && speedMps > 4.0 && angleDeg > 70.0

        /** La diferencia entre dos rumbos (0–180°). */
        internal fun angleBetween(a: Double, b: Double): Double {
            val d = ((a - b) % 360 + 360) % 360
            return if (d > 180) 360 - d else d
        }
    }
}

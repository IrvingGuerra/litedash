package com.irvingguerra.litedash.feature.map

import kotlin.math.abs

/**
 * Cuánto camino antes y después de cada punto se promedia para suavizar una vuelta (m): da una curva de ~12 m de radio,
 * el giro amplio de un auto en una esquina (en la guía tu auto se ve más grande que las calles: una curva chica no se nota).
 */
private const val CURVE_REACH_M = 15.0

/** Menos que esto (grados) no es vuelta: una calle que apenas se dobla se queda como viene. */
private const val TURN_MIN_DEGREES = 12.0

/** En las vueltas va un punto cada tantos metros. */
private const val CURVE_STEP_M = 2.5

/**
 * La misma ruta con las vueltas como las toma un auto: cerca de cada esquina (a [CURVE_REACH_M] de camino) cada punto
 * se cambia por el promedio del camino a su alrededor, así el pico se vuelve curva sin importar cuántos puntos traiga
 * el mapa ahí; lo recto queda igual. Los metros, los segundos y el punto de cada vuelta se llevan a los puntos nuevos:
 * la guía, tu auto y la línea siguen la curva.
 */
internal fun Route.rounded(): Route {
    val n = pointCount
    if (n < 3 || atS.size != n) return this
    // Los metros de camino hasta cada punto.
    val along = DoubleArray(n)
    for (i in 1 until n) along[i] = along[i - 1] + flatMeters(points, i - 1, i)
    val total = along[n - 1]
    // Los pedazos de camino cerca de una vuelta (juntos si se enciman).
    val from = DoubleList()
    val to = DoubleList()
    for (i in 1 until n - 1) {
        if (along[i] - along[i - 1] < 0.01 || along[i + 1] - along[i] < 0.01) continue
        var turn = abs(bearing(points, i, i + 1) - bearing(points, i - 1, i)) % 360.0
        if (turn > 180.0) turn = 360.0 - turn
        if (turn < TURN_MIN_DEGREES) continue
        val a = (along[i] - CURVE_REACH_M).coerceAtLeast(0.0)
        val b = (along[i] + CURVE_REACH_M).coerceAtMost(total)
        if (to.size > 0 && a <= to.last()) to[to.size - 1] = b else {
            from.add(a)
            to.add(b)
        }
    }
    if (from.size == 0) return this
    val out = IntList(points.size + from.size * 24)
    // Por cada punto nuevo, a cuántos metros del camino de antes queda (sin cajas: una ruta larga trae decenas de miles).
    val source = DoubleList(n + from.size * 12)
    val at = DoubleArray(2)
    var cursor = 0
    /** El punto a [s] metros de camino, en [at] (lat, lon en millonésimas). */
    fun locate(s: Double) {
        val m = s.coerceIn(0.0, total)
        while (cursor < n - 2 && along[cursor + 1] < m) cursor++
        while (cursor > 0 && along[cursor] > m) cursor--
        val span = along[cursor + 1] - along[cursor]
        val f = if (span > 0.0) (m - along[cursor]) / span else 0.0
        at[0] = points[2 * cursor] + (points[2 * cursor + 2] - points[2 * cursor]) * f
        at[1] = points[2 * cursor + 1] + (points[2 * cursor + 3] - points[2 * cursor + 1]) * f
    }
    fun add(lat: Double, lon: Double, s: Double) {
        out.add(Math.round(lat).toInt())
        out.add(Math.round(lon).toInt())
        source.add(s)
    }
    /** El promedio del camino alrededor de [s] (más peso al centro); en las puntas de la ruta, lo que haya de cada lado. */
    fun addSmooth(s: Double) {
        val reach = minOf(CURVE_REACH_M, s, total - s)
        var lat = 0.0
        var lon = 0.0
        for (k in -2..2) {
            locate(s + reach * k / 2)
            val weight = 3 - abs(k)
            lat += at[0] * weight
            lon += at[1] * weight
        }
        add(lat / 9, lon / 9, s)
    }
    var piece = 0
    var i = 0
    while (i < n) {
        if (piece < from.size && along[i] >= from[piece]) {
            // Una vuelta: puntos parejos del principio al fin del pedazo, en vez de los del mapa.
            var s = from[piece]
            while (s < to[piece]) {
                addSmooth(s)
                s += CURVE_STEP_M
            }
            addSmooth(to[piece])
            while (i < n && along[i] <= to[piece]) i++
            piece++
        } else {
            add(points[2 * i].toDouble(), points[2 * i + 1].toDouble(), along[i])
            i++
        }
    }
    val count = source.size
    val curved = IntArray(count * 2) { out[it] }
    val meters = DoubleArray(count)
    for (k in 1 until count) meters[k] = meters[k - 1] + flatMeters(curved, k - 1, k)
    // Los segundos de cada punto nuevo, por su lugar en el camino de antes.
    val seconds = DoubleArray(count)
    cursor = 0
    for (k in 0 until count) {
        val s = source[k]
        while (cursor < n - 2 && along[cursor + 1] < s) cursor++
        val span = along[cursor + 1] - along[cursor]
        seconds[k] = atS[cursor] + (atS[cursor + 1] - atS[cursor]) * (if (span > 0.0) ((s - along[cursor]) / span).coerceIn(0.0, 1.0) else 0.0)
    }
    seconds[count - 1] = atS[n - 1]
    // Cada vuelta, al punto nuevo más cercano en el camino.
    var k = 0
    val turns = maneuvers.map { m ->
        val s = along[m.point.coerceIn(0, n - 1)]
        while (k < count - 1 && abs(source[k + 1] - s) <= abs(source[k] - s)) k++
        Maneuver(m.type, m.angle, m.road, k, meters[k], m.exit)
    }
    return Route(curved, meters[count - 1], this.seconds, mainRoad, turns, meters, seconds, online)
}

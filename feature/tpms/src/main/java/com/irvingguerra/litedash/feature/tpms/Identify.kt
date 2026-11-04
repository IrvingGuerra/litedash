package com.irvingguerra.litedash.feature.tpms

/**
 * "¿Cuál sensor es este?": se toma la presión de todos al empezar; el que cambie ≥ 7 kPa
 * (el usuario le sacó o le puso aire) es el buscado. Un sensor que aparece después también
 * tiene que cambiar (si no, sería el de otro auto que pasó al lado). Dura 120 s.
 */
class SensorIdentifier(start: Map<String, Float?>, private val startedAt: Long) {
    private val baseline = start.toMutableMap()

    fun expired(now: Long) = now - startedAt > TIMEOUT_MS

    /** El sensor encontrado con [readings] de ahora, o null si todavía no. */
    fun check(readings: Map<String, TireReading>, now: Long): String? {
        if (expired(now)) return null
        for ((id, r) in readings) {
            if (r.atMillis < startedAt) continue
            val p = r.pressureKpa ?: continue
            // El primero que se oye de un sensor nuevo es su punto de partida.
            val before = baseline[id] ?: run { baseline[id] = p; continue }
            if (kotlin.math.abs(p - before) >= DELTA_KPA) return id
        }
        return null
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
        const val DELTA_KPA = 7f
    }
}

package com.irvingguerra.litedash.feature.tpms

/**
 * "¿Cuál sensor es este?": se toma la presión de todos al empezar; el que cambie ≥ 7 kPa
 * (el usuario le sacó o le puso aire) o el que aparezca nuevo es el buscado. Dura 120 s.
 */
class SensorIdentifier(start: Map<String, Float?>, private val startedAt: Long) {
    private val snapshot = start.toMap()

    fun expired(now: Long) = now - startedAt > TIMEOUT_MS

    /** El sensor encontrado con [readings] de ahora, o null si todavía no. */
    fun check(readings: Map<String, TireReading>, now: Long): String? {
        if (expired(now)) return null
        for ((id, r) in readings) {
            if (r.atMillis < startedAt) continue
            if (id !in snapshot) return id
            val before = snapshot[id] ?: continue
            val p = r.pressureKpa ?: continue
            if (kotlin.math.abs(p - before) >= DELTA_KPA) return id
        }
        return null
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
        const val DELTA_KPA = 7f
    }
}

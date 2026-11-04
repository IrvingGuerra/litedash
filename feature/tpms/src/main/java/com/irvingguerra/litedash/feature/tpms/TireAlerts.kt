package com.irvingguerra.litedash.feature.tpms

enum class TireAlertKind {
    /** Abajo del umbral respecto a la recomendada. */
    LOW,
    /** Bajó ≥ 14 kPa en ≤ 5 min (o el receptor marcó fuga). */
    FAST_LEAK,
    /** Pierde poco a poco: bajó ≥ 20 kPa en los últimos 3 días. */
    SLOW_LEAK,
    HIGH_TEMP,
    LOW_BATTERY,
    /** El sensor dejó de reportar. */
    LOST,
}

enum class TireSeverity { WARNING, DANGER }

data class TireAlert(val wheel: Wheel, val kind: TireAlertKind, val severity: TireSeverity) {
    /** Texto para el conductor, en corto. */
    val message: String get() = when (kind) {
        TireAlertKind.LOW -> if (severity == TireSeverity.DANGER) "Llanta muy baja: revísala ya" else "Presión baja: ponle aire"
        TireAlertKind.FAST_LEAK -> "Pierde aire rápido: oríllate con cuidado"
        TireAlertKind.SLOW_LEAK -> "Pierde aire poco a poco: revisa si tiene un clavo"
        TireAlertKind.HIGH_TEMP -> "Llanta muy caliente: baja la velocidad"
        TireAlertKind.LOW_BATTERY -> "Al sensor se le acaba la pila"
        TireAlertKind.LOST -> "El sensor no responde"
    }
}

/** Límites del usuario (todo en kPa y °C). */
data class TireLimits(
    val recommendedFrontKpa: Float = 230f,
    val recommendedRearKpa: Float = 230f,
    /** Avisa abajo de (100 − esto) % de la recomendada. */
    val lowPercent: Int = 20,
    val highTempC: Float = 75f,
) {
    fun recommended(w: Wheel) = if (w.isFront || w == Wheel.SPARE) recommendedFrontKpa else recommendedRearKpa
}

/** Una muestra del historial: cuándo (ms) y cuánta presión (kPa). */
data class TireSample(val atMillis: Long, val kpa: Float)

const val LOST_AFTER_MS = 10 * 60_000L
const val FAST_LEAK_KPA = 14f
const val FAST_LEAK_WINDOW_MS = 5 * 60_000L
const val SLOW_LEAK_KPA = 20f
const val SLOW_LEAK_WINDOW_MS = 3 * 24 * 3_600_000L
/** El historial debe cubrir al menos esto para hablar de fuga lenta. */
private const val SLOW_LEAK_MIN_SPAN_MS = 24 * 3_600_000L
/** Se comparan los promedios de las primeras 6 h de la ventana y de la última hora (menos ruido). */
private const val SLOW_LEAK_EDGE_MS = 6 * 3_600_000L
private const val SLOW_LEAK_RECENT_MS = 3_600_000L
/** Abajo de este % de la recomendada ya es peligro (rodar así la daña). */
private const val DANGER_PERCENT = 60

/**
 * Las alertas de cada llanta con lo que se sabe ahora, de la más grave a la menos.
 * [readings] = lo último de cada llanta asignada; [history] = muestras de cada llanta (la
 * más vieja primero). Una llanta perdida solo da LOST (sus valores ya son viejos).
 *
 * Fuga lenta, simple a propósito: con al menos 24 h de historial dentro de los últimos 3
 * días, promedio de las primeras 6 h de esa ventana menos promedio de la última hora ≥ 20 kPa
 * (la temperatura mueve ~1 kPa/°C; 20 kPa no los da un día frío solo).
 */
fun tireAlerts(
    readings: Map<Wheel, TireReading>,
    history: Map<Wheel, List<TireSample>>,
    limits: TireLimits,
    now: Long,
): List<TireAlert> {
    val out = mutableListOf<TireAlert>()
    val anyFresh = readings.values.any { now - it.atMillis < LOST_AFTER_MS }
    for ((w, r) in readings) {
        if (r.lost || (anyFresh && now - r.atMillis >= LOST_AFTER_MS)) {
            out += TireAlert(w, TireAlertKind.LOST, TireSeverity.WARNING)
            continue
        }
        val p = r.pressureKpa
        val rec = limits.recommended(w)
        val samples = history[w].orEmpty()
        if (p != null && rec > 0f) {
            val pct = p / rec * 100f
            if (pct < DANGER_PERCENT) out += TireAlert(w, TireAlertKind.LOW, TireSeverity.DANGER)
            else if (pct < 100 - limits.lowPercent) out += TireAlert(w, TireAlertKind.LOW, TireSeverity.WARNING)
        }
        val recentMax = samples.filter { now - it.atMillis <= FAST_LEAK_WINDOW_MS }.maxOfOrNull { it.kpa }
        if (r.leak || (p != null && recentMax != null && recentMax - p >= FAST_LEAK_KPA)) {
            out += TireAlert(w, TireAlertKind.FAST_LEAK, TireSeverity.DANGER)
        } else if (slowLeak(samples, now)) {
            out += TireAlert(w, TireAlertKind.SLOW_LEAK, TireSeverity.WARNING)
        }
        if (r.tempC != null && r.tempC >= limits.highTempC) out += TireAlert(w, TireAlertKind.HIGH_TEMP, TireSeverity.DANGER)
        if (r.lowBattery || (r.batteryPct != null && r.batteryPct <= 10)) out += TireAlert(w, TireAlertKind.LOW_BATTERY, TireSeverity.WARNING)
    }
    return out.sortedWith(compareByDescending<TireAlert> { it.severity }.thenBy { it.kind }.thenBy { it.wheel })
}

private fun slowLeak(samples: List<TireSample>, now: Long): Boolean {
    val window = samples.filter { now - it.atMillis <= SLOW_LEAK_WINDOW_MS }
    if (window.size < 2) return false
    val first = window.first().atMillis
    if (window.last().atMillis - first < SLOW_LEAK_MIN_SPAN_MS) return false
    val old = window.filter { it.atMillis - first <= SLOW_LEAK_EDGE_MS }.map { it.kpa }.average()
    val recent = window.filter { now - it.atMillis <= SLOW_LEAK_RECENT_MS }.map { it.kpa }
    if (recent.isEmpty()) return false
    return old - recent.average() >= SLOW_LEAK_KPA
}

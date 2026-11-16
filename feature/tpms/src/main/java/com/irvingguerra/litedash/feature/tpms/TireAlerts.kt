package com.irvingguerra.litedash.feature.tpms

enum class TireAlertKind {
    /** Abajo del umbral respecto a la recomendada. */
    LOW,
    /** Bajó ≥ 14 kPa en ≤ 5 min, ya descontando la temperatura (o el receptor marcó fuga). */
    FAST_LEAK,
    /** Pierde poco a poco: bajó ≥ 20 kPa en los últimos 3 días, ya descontando la temperatura. */
    SLOW_LEAK,
    HIGH_TEMP,
    LOW_BATTERY,
    /** El sensor dejó de reportar. */
    LOST,
}

enum class TireSeverity { WARNING, DANGER }

data class TireAlert(val wheel: Wheel, val kind: TireAlertKind, val severity: TireSeverity)

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

/** Una muestra del historial: cuándo (ms), cuánta presión (kPa) y a qué temperatura (°C; null si el sensor no la dice). */
data class TireSample(val atMillis: Long, val kpa: Float, val tempC: Float? = null)

/**
 * Lo que dice el historial de una llanta (con la misma calibración que la lectura): las
 * muestras de los últimos 5 min ([TireHistory.recent]) y si pierde aire poco a poco ([TireHistory.slowLeak]).
 */
data class TireTrend(val recent: List<TireSample>, val slowLeak: Boolean)

/** Temperatura de referencia para comparar presiones (20 °C, en kelvin) y la presión del aire (kPa). */
private const val REFERENCE_K = 293.15f
private const val ATMOSPHERE_KPA = 101.325f
private val PLAUSIBLE_TEMP_C = -40f..120f

/**
 * La presión que tendría la llanta a 20 °C (ley de los gases, con presión absoluta): rodar la
 * calienta (+30 °C ≈ +30 kPa) y al enfriarse baja sin perder aire. Así se compara peras con peras.
 * Sin temperatura (o una absurda) se queda igual.
 */
fun coldKpa(kpa: Float, tempC: Float?): Float {
    val t = plausible(tempC) ?: return kpa
    return (kpa + ATMOSPHERE_KPA) * REFERENCE_K / (t + 273.15f) - ATMOSPHERE_KPA
}

/** La temperatura si es creíble (un receptor sin dato puede mandar −50 o 205 °C); si no, como si no la dijera. */
internal fun plausible(tempC: Float?) = tempC?.takeIf { it in PLAUSIBLE_TEMP_C }

/** Una muestra a 20 °C (sin temperatura, como llegó). */
private val TireSample.cold get() = coldKpa(kpa, tempC)
private val TireSample.hasTemp get() = plausible(tempC) != null

/**
 * Cuánto bajó la llanta respecto a lo más alto de [recent] (null sin muestras). Si la lectura
 * de ahora trae temperatura se compara a 20 °C contra las muestras que también la traen; si no,
 * tal como llegaron (nunca se mezclan: una presión caliente sin corregir contra una corregida
 * parecería fuga).
 */
fun pressureDrop(recent: List<TireSample>, kpa: Float, tempC: Float?): Float? {
    val withTemp = plausible(tempC) != null && recent.any { it.hasTemp }
    val max = if (withTemp) recent.filter { it.hasTemp }.maxOfOrNull { it.cold } else recent.maxOfOrNull { it.kpa }
    return max?.let { it - if (withTemp) coldKpa(kpa, tempC) else kpa }
}

const val LOST_AFTER_MS = 10 * 60_000L
const val FAST_LEAK_KPA = 14f
const val FAST_LEAK_WINDOW_MS = 5 * 60_000L
const val SLOW_LEAK_KPA = 20f
const val SLOW_LEAK_WINDOW_MS = 3 * 24 * 3_600_000L
/** El historial debe cubrir al menos esto para hablar de fuga lenta. */
internal const val SLOW_LEAK_MIN_SPAN_MS = 24 * 3_600_000L
/** Se comparan los promedios de las primeras 6 h de la ventana y de la última hora (menos ruido). */
internal const val SLOW_LEAK_EDGE_MS = 6 * 3_600_000L
internal const val SLOW_LEAK_RECENT_MS = 3_600_000L
/** Abajo de este % de la recomendada ya es peligro (rodar así la daña). */
private const val DANGER_PERCENT = 60
/** Pila del sensor en este % o menos: avisar. */
private const val LOW_BATTERY_PCT = 10

/** Hace [LOST_AFTER_MS] o más que no se oye: su valor ya no es de ahora. */
fun TireReading.stale(now: Long) = now - atMillis >= LOST_AFTER_MS

/**
 * Las alertas de cada llanta con lo que se sabe ahora, de la más grave a la menos.
 * [readings] = lo último de cada llanta asignada; [trends] = lo que dice su historial. Una
 * llanta perdida solo da LOST (sus valores ya son viejos). Si todas son viejas, solo LOW y
 * SLOW_LEAK: la fuga rápida, el calor y la pila son de un momento que ya pasó.
 */
fun tireAlerts(
    readings: Map<Wheel, TireReading>,
    trends: Map<Wheel, TireTrend>,
    limits: TireLimits,
    now: Long,
): List<TireAlert> {
    val out = mutableListOf<TireAlert>()
    // Todas viejas (auto apagado, OBD que ya no contesta): lo último sabido solo da baja y fuga lenta.
    val old = readings.values.none { !it.stale(now) }
    for ((w, r) in readings) {
        if (r.lost || (!old && r.stale(now))) {
            out += TireAlert(w, TireAlertKind.LOST, TireSeverity.WARNING)
            continue
        }
        val p = r.pressureKpa
        val rec = limits.recommended(w)
        val trend = trends[w]
        if (p != null && rec > 0f) {
            val pct = p / rec * 100f
            if (pct < DANGER_PERCENT) out += TireAlert(w, TireAlertKind.LOW, TireSeverity.DANGER)
            else if (pct < 100 - limits.lowPercent) out += TireAlert(w, TireAlertKind.LOW, TireSeverity.WARNING)
        }
        val drop = if (!old && p != null && trend != null) pressureDrop(trend.recent, p, r.tempC) else null
        if (!old && (r.leak || (drop != null && drop >= FAST_LEAK_KPA))) {
            out += TireAlert(w, TireAlertKind.FAST_LEAK, TireSeverity.DANGER)
        } else if (trend?.slowLeak == true) {
            out += TireAlert(w, TireAlertKind.SLOW_LEAK, TireSeverity.WARNING)
        }
        if (old) continue
        if (r.tempC != null && r.tempC >= limits.highTempC) out += TireAlert(w, TireAlertKind.HIGH_TEMP, TireSeverity.DANGER)
        if (r.lowBattery || (r.batteryPct != null && r.batteryPct <= LOW_BATTERY_PCT)) out += TireAlert(w, TireAlertKind.LOW_BATTERY, TireSeverity.WARNING)
    }
    return out.sortedWith(compareByDescending<TireAlert> { it.severity }.thenBy { it.kind }.thenBy { it.wheel })
}

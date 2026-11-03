package com.irvingguerra.litedash.feature.obd

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.max

/** Un viaje ya terminado: lo que se guarda y se exporta (en km, km/h, °C y litros). */
data class TripSummary(
    /** Cuándo empezó (milisegundos desde 1970). */
    val start: Long,
    val seconds: Int,
    val km: Float,
    val maxKmh: Float,
    /** Velocidad promedio en movimiento (sin contar lo parado). */
    val avgKmh: Float,
    val maxRpm: Float,
    val maxCoolant: Float?,
    /** Gasolina que se fue (del gasto 5E o del MAF); null si el auto no da ninguno. */
    val liters: Float?,
) {
    val kmPerLiter: Float? get() = liters?.takeIf { it > 0.05f }?.let { km / it }

    fun toCsv(): String = listOf(
        start, seconds, "%.2f".format(Locale.US, km), "%.0f".format(Locale.US, maxKmh), "%.0f".format(Locale.US, avgKmh),
        "%.0f".format(Locale.US, maxRpm), maxCoolant?.let { "%.0f".format(Locale.US, it) } ?: "",
        liters?.let { "%.2f".format(Locale.US, it) } ?: "",
    ).joinToString(",")

    companion object {
        fun fromCsv(line: String): TripSummary? = runCatching {
            val f = line.split(',')
            TripSummary(
                start = f[0].toLong(), seconds = f[1].toInt(), km = f[2].toFloat(), maxKmh = f[3].toFloat(),
                avgKmh = f[4].toFloat(), maxRpm = f[5].toFloat(), maxCoolant = f[6].toFloatOrNull(), liters = f[7].toFloatOrNull(),
            )
        }.getOrNull()
    }
}

/** Gasolina: 14.7 g de aire por gramo, y un litro pesa 737 g (igual que el consumo en vivo). */
private const val LITERS_PER_GRAM_AIR = 1f / (14.7f * 737f)

/** Un hueco más largo que esto entre lecturas no se cuenta (el adaptador se calló). */
private const val MAX_GAP_MS = 5_000L

/** Lo mínimo para guardarlo como viaje (prender el auto un momento no cuenta). */
private const val MIN_SECONDS = 60
private const val MIN_KM = 0.2f

/**
 * Junta las lecturas de un viaje (una por segundo, más o menos) y saca el resumen. La
 * distancia y la gasolina se integran con el tiempo entre lecturas.
 */
class TripRecorder(private val start: Long) {
    private var last = start
    private var seconds = 0f
    private var movingSeconds = 0f
    private var km = 0f
    private var maxKmh = 0f
    private var maxRpm = 0f
    private var maxCoolant: Float? = null
    private var liters = 0f
    private var hasFuel = false

    fun add(now: Long, speedKmh: Float?, rpm: Float?, coolant: Float?, maf: Float?, fuelRate: Float?) {
        val dt = (now - last).coerceAtLeast(0)
        last = now
        if (dt > MAX_GAP_MS) return
        val h = dt / 3_600_000f
        seconds += dt / 1000f
        if (speedKmh != null) {
            km += speedKmh * h
            if (speedKmh >= 3f) movingSeconds += dt / 1000f
            maxKmh = max(maxKmh, speedKmh)
        }
        rpm?.let { maxRpm = max(maxRpm, it) }
        coolant?.let { c -> maxCoolant = maxCoolant?.let { max(it, c) } ?: c }
        // Mejor el gasto de gasolina (5E) si el auto lo da; si no, con el aire que entra.
        val litersPerHour = fuelRate?.takeIf { it >= 0f } ?: maf?.takeIf { it >= 0f }?.let { it * 3600f * LITERS_PER_GRAM_AIR }
        if (litersPerHour != null) {
            liters += litersPerHour * h
            hasFuel = true
        }
    }

    /** El resumen, o null si fue tan corto que no cuenta como viaje. */
    fun summary(): TripSummary? {
        if (seconds < MIN_SECONDS || km < MIN_KM) return null
        return TripSummary(
            start = start,
            seconds = seconds.toInt(),
            km = km,
            maxKmh = maxKmh,
            avgKmh = if (movingSeconds > 0f) km / (movingSeconds / 3600f) else 0f,
            maxRpm = maxRpm,
            maxCoolant = maxCoolant,
            liters = liters.takeIf { hasFuel },
        )
    }
}

/** Los viajes guardados, en un CSV chico dentro de la app (los últimos [KEEP]). */
class TripStore(context: Context) {
    private val file = File(context.filesDir, "trips.csv")

    /** Del más nuevo al más viejo. */
    fun all(): List<TripSummary> =
        runCatching { file.readLines() }.getOrDefault(emptyList()).drop(1).mapNotNull(TripSummary::fromCsv).reversed()

    fun add(trip: TripSummary) {
        val kept = (all().reversed() + trip).takeLast(KEEP)
        file.writeText((listOf(HEADER) + kept.map { it.toCsv() }).joinToString("\n", postfix = "\n"))
    }

    /** El archivo para exportar, tal cual (con encabezados en inglés). */
    fun csv(): String = runCatching { file.readText() }.getOrDefault(HEADER + "\n")

    companion object {
        const val KEEP = 200
        const val HEADER = "start_ms,seconds,km,max_kmh,avg_kmh,max_rpm,max_coolant_c,liters"
    }
}

/** En qué va el cronómetro de 0 a 100. */
sealed interface AccelState {
    /** Hay que pararse para empezar. */
    data object WaitStop : AccelState
    /** Parado: arranca solo al moverse. */
    data object Ready : AccelState
    data class Running(val startMs: Long) : AccelState
    data class Done(val seconds: Float) : AccelState
}

/**
 * Cronómetro de aceleración: listo al estar parado, corre desde que se mueve y para al
 * llegar a [targetKmh]. Las lecturas llegan a saltos (OBD o GPS), así que el arranque y
 * la llegada se calculan entre la lectura de antes y la de después.
 */
class AccelTimer(private val targetKmh: Float = 100f) {
    var state: AccelState = AccelState.WaitStop
        private set
    private var prevMs = -1L
    private var prevKmh = 0f

    fun reset() {
        state = AccelState.WaitStop
    }

    fun update(nowMs: Long, kmh: Float) {
        state = when (val s = state) {
            AccelState.WaitStop, is AccelState.Done -> if (kmh < STOPPED_KMH) AccelState.Ready else s
            AccelState.Ready -> if (kmh >= STOPPED_KMH) AccelState.Running(cross(nowMs, kmh, STOPPED_KMH)) else s
            is AccelState.Running -> when {
                kmh >= targetKmh -> AccelState.Done((cross(nowMs, kmh, targetKmh) - s.startMs) / 1000f)
                kmh < STOPPED_KMH -> AccelState.Ready
                else -> s
            }
        }
        prevMs = nowMs
        prevKmh = kmh
    }

    /** El momento (entre la lectura anterior y esta) en que se pasó por [kmh]. */
    private fun cross(nowMs: Long, nowKmh: Float, kmh: Float): Long {
        if (prevMs < 0 || nowKmh <= prevKmh) return nowMs
        val f = ((kmh - prevKmh) / (nowKmh - prevKmh)).coerceIn(0f, 1f)
        return prevMs + ((nowMs - prevMs) * f).toLong()
    }

    companion object {
        const val STOPPED_KMH = 1f
    }
}

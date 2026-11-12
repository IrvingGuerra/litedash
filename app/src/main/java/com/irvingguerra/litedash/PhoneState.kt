package com.irvingguerra.litedash

import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.tpms.Wheel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * El estado de tu auto en LiteDash Connect (PROTOCOLO_LINK.md §5g): si el celular lo pidió, al conectarse y cada
 * vez que cambia algo (a lo más una vez cada [STATE_GAP_MS]). Solo lee lo que ya corre: el viaje mientras algo usa
 * el GPS, el OBD y las llantas si su módulo está prendido y ya existen (nunca los crea ni los prende); cada uno con
 * el latido que ya tiene, sin sondeo propio.
 */
internal fun wirePhoneState(link: PhoneLink, deps: () -> Dependencies) {
    phoneScope.launch {
        var sentAt = 0L
        snapshotFlow { if (link.connected && link.wantsState) readState(deps()) else null }
            .collectLatest { state ->
                if (state == null) {
                    // Al volver a conectar, la primera va de una vez.
                    sentAt = 0L
                    return@collectLatest
                }
                // Si cambia otra vez mientras espera, se cancela esta y manda la más nueva.
                val wait = sentAt + STATE_GAP_MS - SystemClock.elapsedRealtime()
                if (sentAt != 0L && wait > 0) delay(wait)
                val now = SystemClock.elapsedRealtime()
                link.sendState(state.json(now))
                sentAt = now
            }
    }
}

/** Lo que se sabe ahora del auto (en estado de Compose: se vuelve a leer cuando cambia algo). */
private fun readState(d: Dependencies): PhoneCarState {
    val units = d.prefs.units
    val tpms = d.tpmsIfCreated()?.takeIf { it.source != null }
    val obd = d.obdIfCreated()?.takeIf { d.obdModule }
    val report = obd?.lastReport
    val trip = d.trip
    val wheels = if (tpms?.showSpare == true) Wheel.ROAD + Wheel.SPARE else Wheel.ROAD
    return PhoneCarState(
        miles = units.miles,
        fahrenheit = units.fahrenheit,
        pressure = tpms?.unit?.key,
        tripStartedAt = trip.startedAt,
        tripKm = if (trip.startedAt == 0L) null else tenths(trip.meters.toFloat() / 1000),
        codes = report?.let { (it.stored + it.pending + it.permanent).distinct().size },
        volts = obd?.let { d.bus.value(Signal.VOLTAGE) ?: report?.voltage }?.let(::tenths),
        coolant = obd?.let { d.bus.value(Signal.COOLANT) }?.roundToInt(),
        tires = tpms?.let { t ->
            wheels.mapNotNull { w ->
                val r = t.wheelReading(w)
                val kpa = r?.pressureKpa ?: return@mapNotNull null
                PhoneTire(w.key, tenths(kpa), r.tempC?.roundToInt(), t.alerts.any { it.wheel == w })
            }
        }.orEmpty(),
    )
}

/** Con un decimal (un Float tal cual saldría como 12.300000190734863). */
private fun tenths(value: Float) = (value * 10).roundToInt() / 10.0

/** Una llanta con dato: [pos] `fl`/`fr`/`rl`/`rr`/`spare`, presión en kPa, °C si se conoce y si tiene aviso. */
internal data class PhoneTire(val pos: String, val kpa: Double, val c: Int?, val warn: Boolean)

/**
 * Una foto del auto para el celular (§5g): todo en km, °C y kPa, con las unidades en que se ven en la pantalla;
 * lo que no se sabe es null (y no viaja). [pressure] = la unidad de las llantas (null sin el módulo Llantas).
 */
internal data class PhoneCarState(
    val miles: Boolean,
    val fahrenheit: Boolean,
    val pressure: String?,
    /** Reloj de arranque en que empezó el viaje (0 = no hay viaje). */
    val tripStartedAt: Long,
    val tripKm: Double?,
    val codes: Int?,
    val volts: Double?,
    val coolant: Int?,
    val tires: List<PhoneTire>,
) {
    /** El mensaje `state` sin su `t`; [now] = reloj de arranque (para los minutos del viaje). */
    fun json(now: Long): JSONObject {
        val units = JSONObject().put("speed", if (miles) "mi" else "km").put("temp", if (fahrenheit) "f" else "c")
        if (pressure != null) units.put("pressure", pressure)
        val state = JSONObject().put("units", units)
        if (tripKm != null) state.put("trip", JSONObject().put("km", tripKm).put("min", (now - tripStartedAt).coerceAtLeast(0) / 60_000))
        val obd = JSONObject()
        if (codes != null) obd.put("codes", codes)
        if (volts != null) obd.put("volts", volts)
        if (coolant != null) obd.put("coolant", coolant)
        if (obd.length() > 0) state.put("obd", obd)
        if (tires.isNotEmpty()) {
            state.put("tires", JSONArray(tires.map { t ->
                JSONObject().put("pos", t.pos).put("kpa", t.kpa).put("warn", t.warn).also { if (t.c != null) it.put("c", t.c) }
            }))
        }
        return state
    }
}

/** A lo más un `state` cada tanto (el protocolo dice 5 s). */
private const val STATE_GAP_MS = 5_000L

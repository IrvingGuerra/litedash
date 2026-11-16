package com.irvingguerra.litedash.feature.tpms

import com.irvingguerra.litedash.core.vehicle.KPA_PER_PSI
import com.irvingguerra.litedash.core.vehicle.Source

/** Una llanta del auto; [key] se guarda en preferencias y en el bus (no se traduce). */
enum class Wheel(val key: String) {
    FRONT_LEFT("fl"), FRONT_RIGHT("fr"), REAR_LEFT("rl"), REAR_RIGHT("rr"), SPARE("spare");

    val isFront get() = this == FRONT_LEFT || this == FRONT_RIGHT

    companion object {
        /** Las cuatro que ruedan (sin la refacción). */
        val ROAD = listOf(FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT)
        fun fromKey(key: String?): Wheel? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Lo último que dijo un sensor. [pressureKpa] es manométrica (0 = ponchada): tal como llega
 * dentro del repositorio, y con el ajuste de calibración de su sensor en lo que el repositorio
 * entrega (`wheelReading`, `nearby`). [reportedWheel] = la llanta que dice el propio sensor o
 * receptor (null si no lo sabe). [batteryVolts] solo va al reporte (la pila baja ya viene en [lowBattery]).
 */
data class TireReading(
    val sensorId: String,
    val pressureKpa: Float?,
    val tempC: Float?,
    val batteryPct: Int?,
    val batteryVolts: Float?,
    val lowBattery: Boolean,
    val leak: Boolean,
    val lost: Boolean,
    val family: String,
    val reportedWheel: Wheel?,
    val rssi: Int?,
    val atMillis: Long,
)

/** El "sensor" de [w] cuando las llantas salen de la computadora del auto. */
internal fun obdSensorId(w: Wheel) = "obd-" + w.key

/** El sensor de [w] del receptor USB mientras no diga su id real. */
internal fun usbSensorId(w: Wheel) = "usb-" + w.key

/** Señales de llantas en el bus del auto ("tire_pressure_fl" en kPa, "tire_temp_fl" en °C). */
object TireSignals {
    fun pressure(w: Wheel) = "tire_pressure_${w.key}"
    fun temp(w: Wheel) = "tire_temp_${w.key}"

    val ALL: Set<String> = Wheel.entries.flatMap { listOf(pressure(it), temp(it)) }.toSet()

    fun isTire(signal: String) = signal in ALL
}

/**
 * Fuente con la que se publican las llantas (ninguna otra publica señales de llantas). Nunca se usa
 * `bus.clear(...)` con ella: se borran solo las señales de llantas, una por una.
 */
internal val TPMS_SOURCE = Source.TPMS

/** Unidad de presión que se muestra (internamente todo va en kPa). */
enum class PressureUnit(val key: String, val label: String) {
    PSI("psi", "psi"), BAR("bar", "bar"), KPA("kpa", "kPa");

    fun fromKpa(kpa: Float): Float = when (this) {
        PSI -> kpa / KPA_PER_PSI
        BAR -> kpa / 100f
        KPA -> kpa
    }

    fun toKpa(value: Float): Float = when (this) {
        PSI -> value * KPA_PER_PSI
        BAR -> value * 100f
        KPA -> value
    }

    /** Texto corto con los decimales que se acostumbran (psi y kPa enteros, bar con uno). */
    fun format(kpa: Float): String = when (this) {
        BAR -> "%.1f".format(java.util.Locale.US, fromKpa(kpa))
        else -> Math.round(fromKpa(kpa)).toString()
    }

    companion object {
        fun fromKey(key: String?): PressureUnit? = entries.firstOrNull { it.key == key }
    }
}

internal fun ByteArray.hex(from: Int = 0, to: Int = size): String =
    (from until to).joinToString("") { "%02X".format(this[it].toInt() and 0xFF) }

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

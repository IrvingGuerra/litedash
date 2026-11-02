package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal

/** Un dato en vivo del modo 01: qué señal llena, cada cuánto y cómo se calcula. */
class Pid(val code: Int, val signal: String, val fast: Boolean, val decode: (IntArray) -> Float?)

private fun IntArray.a() = getOrNull(0)
private fun IntArray.ab() = if (size >= 2) this[0] * 256 + this[1] else null

/** Los PIDs estándar que usa LiteDash (SAE J1979). */
val Pids = listOf(
    Pid(0x0D, Signal.SPEED, fast = true) { it.a()?.toFloat() },
    Pid(0x0C, Signal.RPM, fast = true) { it.ab()?.div(4f) },
    Pid(0x04, Signal.LOAD, fast = true) { it.a()?.times(100f / 255) },
    Pid(0x0B, Signal.MAP, fast = true) { it.a()?.toFloat() },
    Pid(0x10, Signal.MAF, fast = true) { it.ab()?.div(100f) },
    Pid(0x05, Signal.COOLANT, fast = false) { it.a()?.minus(40f) },
    Pid(0x0F, Signal.INTAKE, fast = false) { it.a()?.minus(40f) },
    Pid(0x2F, Signal.FUEL, fast = false) { it.a()?.times(100f / 255) },
    Pid(0x42, Signal.VOLTAGE, fast = false) { it.ab()?.div(1000f) },
    Pid(0x33, Signal.BARO, fast = false) { it.a()?.toFloat() },
    Pid(0x46, Signal.AMBIENT, fast = false) { it.a()?.minus(40f) },
    Pid(0x11, Signal.THROTTLE, fast = true) { it.a()?.times(100f / 255) },
    Pid(0x5E, Signal.FUEL_RATE, fast = true) { it.ab()?.div(20f) },
    // Ajuste de mezcla: 128 = 0 %; abajo le quita gasolina, arriba le pone.
    Pid(0x06, Signal.FUEL_TRIM_SHORT, fast = false) { it.a()?.let { a -> (a - 128) * 100f / 128 } },
    Pid(0x07, Signal.FUEL_TRIM_LONG, fast = false) { it.a()?.let { a -> (a - 128) * 100f / 128 } },
    Pid(0x0A, Signal.FUEL_PRESSURE, fast = false) { it.a()?.times(3f) },
    Pid(0x0E, Signal.TIMING, fast = false) { it.a()?.let { a -> a / 2f - 64 } },
    Pid(0x1F, Signal.RUN_TIME, fast = false) { it.ab()?.toFloat() },
    Pid(0x21, Signal.DISTANCE_MIL, fast = false) { it.ab()?.toFloat() },
    Pid(0x5C, Signal.OIL, fast = false) { it.a()?.minus(40f) },
)

/** Un monitor de preparación (lo que revisan en la verificación). */
data class Monitor(val name: MonitorName, val complete: Boolean)

enum class MonitorName { MISFIRE, FUEL, COMPONENTS, CATALYST, HEATED_CATALYST, EVAP, SECONDARY_AIR, O2_SENSOR, O2_HEATER, EGR }

/** Estado del motor del PID 01: luz de falla, cuántos códigos y los monitores. */
data class Readiness(val mil: Boolean, val codes: Int, val monitors: List<Monitor>) {
    /** Los monitores que todavía no terminan (en la verificación se acepta uno pendiente). */
    val incomplete get() = monitors.filter { !it.complete }
}

/** PID 01 de un motor de gasolina: bits de soportado y de incompleto por monitor. */
fun parseReadiness(d: IntArray): Readiness? {
    if (d.size < 4) return null
    val (a, b, c, e) = d
    val monitors = mutableListOf<Monitor>()
    // B: continuos (soportado en bits 0-2, incompleto en 4-6).
    listOf(MonitorName.MISFIRE, MonitorName.FUEL, MonitorName.COMPONENTS).forEachIndexed { i, name ->
        if (b and (1 shl i) != 0) monitors += Monitor(name, b and (1 shl (i + 4)) == 0)
    }
    // C soportado y D incompleto, para gasolina (bit 3 de B = 0).
    if (b and 0x08 == 0) {
        val bits = listOf(
            0 to MonitorName.CATALYST, 1 to MonitorName.HEATED_CATALYST, 2 to MonitorName.EVAP,
            3 to MonitorName.SECONDARY_AIR, 5 to MonitorName.O2_SENSOR, 6 to MonitorName.O2_HEATER, 7 to MonitorName.EGR,
        )
        for ((bit, name) in bits) if (c and (1 shl bit) != 0) monitors += Monitor(name, e and (1 shl bit) == 0)
    }
    return Readiness(mil = a and 0x80 != 0, codes = a and 0x7F, monitors = monitors)
}

package com.irvingguerra.litedash.core.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf

/**
 * Señales del auto con nombre fijo (como VSS, en corto). Se guardan en acomodos y
 * preferencias: van en español y no se traducen.
 */
object Signal {
    const val SPEED = "velocidad"       // km/h
    const val RPM = "rpm"               // rpm
    const val COOLANT = "motor_temp"    // °C
    const val FUEL = "gasolina"         // %
    const val VOLTAGE = "voltaje"       // V
    const val INTAKE = "admision_temp"  // °C
    const val LOAD = "carga"            // %
    const val MAP = "presion_admision"  // kPa
    const val BARO = "presion_aire"     // kPa
    const val AMBIENT = "aire_temp"     // °C
    const val MAF = "maf"               // g/s
}

/** De dónde viene un dato; con varias fuentes para la misma señal gana la mayor. */
enum class Source(val priority: Int) { GPS(1), OBD(2), CAN(3) }

/**
 * El tablero nunca le habla directo al OBD ni al GPS: lee aquí. Cada fuente publica
 * lo que sabe y las piezas dicen qué quieren ver ([watch]); el OBD solo pregunta
 * eso, así no satura el adaptador ni gasta nada con el tablero en otra cosa.
 */
class VehicleBus {
    data class Reading(val value: Float, val source: Source)

    private val values = mutableStateMapOf<String, Reading>()
    private val sources = mutableMapOf<String, MutableMap<Source, Float>>()
    private val wantedCounts = mutableStateMapOf<String, Int>()

    /** Lo que alguna pieza o pantalla está mostrando ahora mismo. */
    val wanted: Set<String> get() = wantedCounts.keys.toSet()

    /** El valor de la mejor fuente que lo tenga (null = nadie lo sabe todavía). */
    fun value(signal: String): Float? = values[signal]?.value

    fun source(signal: String): Source? = values[signal]?.source

    /** [value] null = esa fuente ya no lo sabe (se desconectó o perdió señal). */
    fun publish(signal: String, value: Float?, source: Source) {
        val bySource = sources.getOrPut(signal) { mutableMapOf() }
        if (value == null) bySource.remove(source) else bySource[source] = value
        val best = bySource.maxByOrNull { it.key.priority }
        if (best == null) values.remove(signal) else values[signal] = Reading(best.value, best.key)
    }

    /** Quita todo lo que publicó [source] (por ejemplo, al desconectar el OBD). */
    fun clear(source: Source) {
        for (signal in sources.keys.toList()) publish(signal, null, source)
    }

    fun want(signal: String) {
        wantedCounts[signal] = (wantedCounts[signal] ?: 0) + 1
    }

    fun unwant(signal: String) {
        val n = (wantedCounts[signal] ?: 0) - 1
        if (n <= 0) wantedCounts.remove(signal) else wantedCounts[signal] = n
    }
}

/** Lee [signal] y avisa que se está mostrando mientras este composable exista. */
@Composable
fun VehicleBus.watch(signal: String): Float? {
    DisposableEffect(this, signal) {
        want(signal)
        onDispose { unwant(signal) }
    }
    return value(signal)
}

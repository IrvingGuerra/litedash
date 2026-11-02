package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal

/**
 * Un dato que el auto no da tal cual y se calcula de otros: [needs] son los que hacen
 * falta, [extras] los que mejoran la cuenta si el auto los tiene. [smoothing] (0–1]
 * suaviza los que brincan mucho (1 = sin suavizar).
 */
class Derived(
    val signal: String,
    val needs: List<String>,
    val extras: List<String> = emptyList(),
    val smoothing: Float = 1f,
    val compute: (Map<String, Float>) -> Float?,
)

/** Gasolina: 14.7 g de aire por cada gramo que se quema, y un litro pesa 737 g. */
private const val AIR_FUEL = 14.7f
private const val FUEL_GRAMS_PER_LITER = 737f
/** Presión del aire a nivel del mar (kPa), si el auto no la reporta. */
private const val SEA_LEVEL_KPA = 101.3f

/** Kilómetros por litro con el flujo de aire (MAF) y la velocidad; null parado (no hay km). */
fun consumption(mafGramsPerSecond: Float, speedKmh: Float): Float? {
    if (speedKmh < 5f || mafGramsPerSecond <= 0f) return null
    val litersPerHour = mafGramsPerSecond * 3600f / (AIR_FUEL * FUEL_GRAMS_PER_LITER)
    return speedKmh / litersPerHour
}

val DerivedSignals = listOf(
    Derived(Signal.CONSUMPTION, needs = listOf(Signal.MAF, Signal.SPEED), smoothing = 0.2f) { v ->
        consumption(v[Signal.MAF] ?: return@Derived null, v[Signal.SPEED] ?: return@Derived null)
    },
    // Turbo: presión de admisión menos la del aire (con la de la altura si el auto la da).
    Derived(Signal.BOOST, needs = listOf(Signal.MAP), extras = listOf(Signal.BARO)) { v ->
        (v[Signal.MAP] ?: return@Derived null) - (v[Signal.BARO] ?: SEA_LEVEL_KPA)
    },
)

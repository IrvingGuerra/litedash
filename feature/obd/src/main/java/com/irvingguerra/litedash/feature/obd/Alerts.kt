package com.irvingguerra.litedash.feature.obd

/** Algo del motor que el conductor debe saber ya; [holdMs] = cuánto debe durar para avisar. */
enum class EngineAlert(val holdMs: Long = 60_000) {
    /** Motor sobrecalentado: orillarse y apagarlo (avisa pronto). */
    HOT(8_000),
    /** Con el motor andando la batería no carga (alternador o banda). */
    NOT_CHARGING,
    /** Voltaje de más: el regulador del alternador puede estar dañando la batería. */
    OVERCHARGING,
    /** Con el motor apagado la batería ya está baja (puede no arrancar). */
    LOW_BATTERY,
}

/** De estos grados (°C) para arriba el motor está sobrecalentado (lo normal es 85–105). */
const val HOT_COOLANT_C = 110f
/** Motor andando: arriba de esto gira (en ralentí anda en 600–900 rpm). */
private const val RUNNING_RPM = 400f
/**
 * El alternador da 13.5–14.7 V. Los "inteligentes" (Mazda, VW, Ford, Toyota…) bajan a
 * 12.4–12.7 V a propósito en crucero: abajo de 12.3 V ya no carga de verdad.
 */
private const val CHARGING_MIN_V = 12.3f
const val CHARGING_MAX_V = 15.3f
/** Batería en reposo: 12.6 V es llena; abajo de 11.9 V ya cuesta arrancar. */
const val RESTING_MIN_V = 11.9f

/**
 * La alerta más importante con lo que se sabe (null = todo bien o no hay datos).
 * Sin rpm no se sabe si el motor anda: solo se avisa del sobrecalentamiento.
 */
fun engineAlert(coolantC: Float?, voltage: Float?, rpm: Float?): EngineAlert? {
    if (coolantC != null && coolantC >= HOT_COOLANT_C) return EngineAlert.HOT
    if (voltage == null || rpm == null || voltage < 5f) return null
    return when {
        rpm > RUNNING_RPM && voltage < CHARGING_MIN_V -> EngineAlert.NOT_CHARGING
        rpm > RUNNING_RPM && voltage > CHARGING_MAX_V -> EngineAlert.OVERCHARGING
        rpm <= RUNNING_RPM && voltage < RESTING_MIN_V -> EngineAlert.LOW_BATTERY
        else -> null
    }
}

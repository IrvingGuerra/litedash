package com.irvingguerra.litedash

/** Kilómetros que hay en una milla. */
const val KM_PER_MILE = 1.609344f

/** Kilómetros (o km/h) a millas (o mph) si [imperial]; si no, tal cual. */
fun Float.toMiles(imperial: Boolean): Float = if (imperial) this / KM_PER_MILE else this

/** Metros a la unidad de distancia que se muestra: millas o kilómetros. */
fun Double.metersToDistance(imperial: Boolean): Double = if (imperial) this / (KM_PER_MILE * 1000.0) else this / 1000.0

/** Grados Celsius a Fahrenheit si [imperial]; si no, tal cual. */
fun Float.toFahrenheit(imperial: Boolean): Float = if (imperial) this * 9f / 5f + 32f else this

/**
 * Las unidades que se ven: [miles] = mph, millas, mpg y galones (si no, km/h, km, km/l y
 * litros); [fahrenheit] = °F (si no, °C). Sale de [Preferences.units].
 */
data class UnitChoice(val miles: Boolean, val fahrenheit: Boolean)

/** Velocidad y distancia elegidas en Ajustes; AUTO = según idioma y región ([imperialUnits]). */
enum class SpeedUnits(val stored: String) {
    AUTO("auto"), METRIC("metric"), IMPERIAL("imperial");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: AUTO
    }
}

/** Temperatura elegida en Ajustes; AUTO = según idioma y región ([imperialUnits]). */
enum class TempUnits(val stored: String) {
    AUTO("auto"), CELSIUS("celsius"), FAHRENHEIT("fahrenheit");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: AUTO
    }
}

/** Resuelve lo elegido; AUTO toma [autoImperial] (la regla de siempre). */
fun resolveUnits(speed: SpeedUnits, temp: TempUnits, autoImperial: Boolean) = UnitChoice(
    miles = when (speed) {
        SpeedUnits.AUTO -> autoImperial
        SpeedUnits.METRIC -> false
        SpeedUnits.IMPERIAL -> true
    },
    fahrenheit = when (temp) {
        TempUnits.AUTO -> autoImperial
        TempUnits.CELSIUS -> false
        TempUnits.FAHRENHEIT -> true
    },
)

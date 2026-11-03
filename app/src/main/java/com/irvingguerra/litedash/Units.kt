package com.irvingguerra.litedash

/** Kilómetros que hay en una milla. */
const val KM_PER_MILE = 1.609344f

/** Kilómetros (o km/h) a millas (o mph) si [imperial]; si no, tal cual. */
fun Float.toMiles(imperial: Boolean): Float = if (imperial) this / KM_PER_MILE else this

/** Metros a la unidad de distancia que se muestra: millas o kilómetros. */
fun Double.metersToDistance(imperial: Boolean): Double = if (imperial) this / (KM_PER_MILE * 1000.0) else this / 1000.0

/** Grados Celsius a Fahrenheit si [imperial]; si no, tal cual. */
fun Float.toFahrenheit(imperial: Boolean): Float = if (imperial) this * 9f / 5f + 32f else this

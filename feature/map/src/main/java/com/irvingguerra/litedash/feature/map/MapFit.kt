package com.irvingguerra.litedash.feature.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2

/** Lo más lejos y lo más cerca que se ve una zona al elegirla (lo que acepta `MapCanvas` con `fixedZoom`). */
const val FIT_MIN_ZOOM = 5
const val FIT_MAX_ZOOM = 17

/** Qué tanto del lado corto ocupa el círculo: el resto es aire alrededor. */
private const val FIT_SHARE = 0.8

/**
 * El zoom más cercano en el que un círculo de [km] de radio en la latitud [lat] cabe con aire en [viewPx]
 * (el lado corto de la vista), con teselas de [tilePx] en pantalla; entre [FIT_MIN_ZOOM] y [FIT_MAX_ZOOM].
 */
fun zoomToFit(km: Float, lat: Double, viewPx: Float, tilePx: Float): Int {
    if (km <= 0f || viewPx <= 0f || tilePx <= 0f) return FIT_MAX_ZOOM
    // El mundo mide 2^z teselas a lo ancho; en la latitud, una tesela abarca (vuelta de la Tierra · cos) / 2^z km.
    val worldKm = 2 * PI * EARTH_RADIUS_KM * cos(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
    val tiles = worldKm * viewPx * FIT_SHARE / (2.0 * km * tilePx)
    return floor(log2(tiles)).toInt().coerceIn(FIT_MIN_ZOOM, FIT_MAX_ZOOM)
}

private const val EARTH_RADIUS_KM = 6371.0

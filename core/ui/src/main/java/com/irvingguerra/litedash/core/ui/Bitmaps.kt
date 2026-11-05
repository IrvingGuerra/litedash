package com.irvingguerra.litedash.core.ui

import android.graphics.BitmapFactory

/**
 * Opciones para decodificar una imagen cuyo lado medido es [sourcePx] ya reducida a [targetPx]:
 * primero se salta píxeles al leer (potencias de 2, sin leer la imagen completa) y luego escala lo
 * que falta, así nunca queda en memoria más que el tamaño final. Nunca agranda.
 */
fun scaledDecode(sourcePx: Int, targetPx: Int) = BitmapFactory.Options().apply {
    if (targetPx <= 0 || sourcePx <= targetPx) return@apply
    var sample = 1
    while (sourcePx / (sample * 2) >= targetPx) sample *= 2
    inSampleSize = sample
    inScaled = true
    inDensity = sourcePx / sample
    inTargetDensity = targetPx
}

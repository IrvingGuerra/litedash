package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * La marca suelta —·· (brand/logo/litedash-marca-*.svg), dibujada sin imágenes.
 * Mismas proporciones que el SVG: lienzo de 184 × 48 unidades.
 */
@Composable
fun MarcaMorse(modifier: Modifier = Modifier, color: Color = LocalColores.current.texto, luz: Color = Marca.Ambar) {
    Canvas(modifier) {
        val u = size.width / 184f
        val alto = 36f * u
        val y = (size.height - alto) / 2f
        drawRoundRect(color, Offset(6f * u, y), Size(76f * u, alto), CornerRadius(alto / 2f))
        drawCircle(color, 17f * u, Offset(113f * u, size.height / 2f))
        drawCircle(luz, 17f * u, Offset(161f * u, size.height / 2f))
    }
}

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
fun MorseMark(modifier: Modifier = Modifier, color: Color = LocalColors.current.text, light: Color = Brand.Amber) {
    Canvas(modifier) {
        val u = size.width / 184f
        val height = 36f * u
        val y = (size.height - height) / 2f
        drawRoundRect(color, Offset(6f * u, y), Size(76f * u, height), CornerRadius(height / 2f))
        drawCircle(color, 17f * u, Offset(113f * u, size.height / 2f))
        drawCircle(light, 17f * u, Offset(161f * u, size.height / 2f))
    }
}

package com.irvingguerra.litedash.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * El tamaño de letra (sp) para que [text] quepa en [width] × [height]: lo alto que deja [heightShare] del widget
 * y lo ancho que da cada carácter (unos 0.6 del tamaño, [extraChars] de más por la unidad), entre [min] y [max].
 * Descuenta la letra grande del sistema (si no, con letra al 130 % los números se salían). Así los números grandes
 * no se cortan en un widget chico ni se ven perdidos en uno grande.
 */
@Composable
fun fitFont(text: String, width: Dp, height: Dp, heightShare: Float, min: Float = 16f, max: Float = 96f, extraChars: Float = 1f): Float =
    minOf(height.value * heightShare, width.value / ((text.length + extraChars) * 0.6f)).coerceIn(min, max) / LocalDensity.current.fontScale

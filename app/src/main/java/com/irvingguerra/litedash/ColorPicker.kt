package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.feature.car.CarColors

/** Arcoíris del círculo "otro color" y de la barra de tono. */
private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/**
 * Los colores rápidos ([swatches]) y uno libre: el círculo de arcoíris abre un selector de
 * tono (barra) y de intensidad y brillo (cuadro). Mientras se arrastra, lo que se pinta
 * cambia en vivo con [onPreview]; al soltar (o al tocar un color) llega [onChange].
 * [leading] va antes de los colores (p. ej. "Natural"); sin color elegido, [color] = Unspecified.
 */
@Composable
fun ColorPicker(
    color: Color,
    onPreview: (Color) -> Unit,
    swatches: List<Color> = CarColors,
    leading: @Composable () -> Unit = {},
    onChange: (Color) -> Unit,
) {
    val custom = color.isSpecified && swatches.none { it.toArgb() == color.toArgb() }
    var open by remember { mutableStateOf(custom) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            leading()
            swatches.forEach { col ->
                Swatch(col.toArgb() == color.toArgb(), Brush.linearGradient(listOf(col, col))) { onChange(col) }
            }
            val otherLabel = stringResource(R.string.other_color)
            Swatch(custom || open, Brush.sweepGradient(RAINBOW), Modifier.semantics { contentDescription = otherLabel }) {
                open = !open
            }
        }
        if (open) ColorPlane(color.takeIf { it.isSpecified } ?: swatches.first(), onPreview, onChange)
    }
}

@Composable
internal fun Swatch(selected: Boolean, fill: Brush, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(3.dp, if (selected) c.accent else c.line, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick),
    )
}

/** Cuadro de intensidad (→) y brillo (↑) para el tono elegido, y la barra de tonos. */
@Composable
private fun ColorPlane(color: Color, onPreview: (Color) -> Unit, onChange: (Color) -> Unit) {
    val c = LocalColors.current
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    // El tono se guarda aparte: en grises o negro el color ya no lo recuerda.
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    LaunchedEffect(hsv) { if (hsv[1] > 0.02f && hsv[2] > 0.02f) hue = hsv[0] }
    val current by rememberUpdatedState(hsv)
    val preview by rememberUpdatedState(onPreview)
    val change by rememberUpdatedState(onChange)
    // El último color del arrastre: es el que se guarda al soltar.
    var dragged by remember { mutableStateOf<Color?>(null) }
    val drop = { dragged?.let(change); dragged = null }
    val shape = RoundedCornerShape(Radius.inner)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(shape)
                .border(1.dp, c.line, shape)
                .dragInput(drop) { p, width, height ->
                    val s = (p.x / width).coerceIn(0f, 1f)
                    val v = 1f - (p.y / height).coerceIn(0f, 1f)
                    dragged = Color.hsv(hue, s, v).also(preview)
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val dot = Offset(current[1] * size.width, (1f - current[2]) * size.height)
            drawCircle(Color.White, 11.dp.toPx(), dot, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), dot, style = Stroke(1.dp.toPx()))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(CircleShape)
                .dragInput(drop) { p, width, _ ->
                    hue = (p.x / width).coerceIn(0f, 1f) * 360f
                    // Si estaba en gris, al elegir tono se le da color.
                    val s = current[1].coerceAtLeast(0.6f)
                    val v = current[2].coerceAtLeast(0.35f)
                    dragged = Color.hsv(hue.coerceAtMost(359.9f), s, v).also(preview)
                },
        ) {
            drawRect(Brush.horizontalGradient(RAINBOW))
            val x = hue / 360f * size.width
            drawCircle(Color.White, size.height / 2 - 3.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
        }
    }
}

/** Tocar o arrastrar dentro: avisa la posición del dedo y el tamaño del área, y [onEnd] al soltar. */
private fun Modifier.dragInput(onEnd: () -> Unit, onMove: (Offset, Float, Float) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onMove(down.position, size.width.toFloat(), size.height.toFloat())
        while (true) {
            val ev = awaitPointerEvent()
            val finger: PointerInputChange = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!finger.pressed) break
            finger.consume()
            onMove(finger.position, size.width.toFloat(), size.height.toFloat())
        }
        onEnd()
    }
}

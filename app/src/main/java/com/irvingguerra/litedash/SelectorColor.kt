package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.feature.auto.ColoresAuto

/** Arcoíris del círculo "otro color" y de la barra de tono. */
private val ARCOIRIS = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/**
 * Los colores rápidos y uno libre: el círculo de arcoíris abre un selector de
 * tono (barra) y de intensidad y brillo (cuadro). El auto cambia en vivo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectorColor(color: Color, alCambiar: (Color) -> Unit) {
    val libre = ColoresAuto.none { it.toArgb() == color.toArgb() }
    var abierto by remember { mutableStateOf(libre) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ColoresAuto.forEach { col ->
                Circulo(col.toArgb() == color.toArgb(), Brush.linearGradient(listOf(col, col))) { alCambiar(col) }
            }
            val otro = stringResource(R.string.otro_color)
            Circulo(libre || abierto, Brush.sweepGradient(ARCOIRIS), Modifier.semantics { contentDescription = otro }) {
                abierto = !abierto
            }
        }
        if (abierto) Paleta(color, alCambiar)
    }
}

@Composable
private fun Circulo(elegido: Boolean, relleno: Brush, modifier: Modifier = Modifier, alTocar: () -> Unit) {
    val c = LocalColores.current
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(3.dp, if (elegido) c.acento else c.linea, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(relleno)
            .clickable(onClick = alTocar),
    )
}

/** Cuadro de intensidad (→) y brillo (↑) para el tono elegido, y la barra de tonos. */
@Composable
private fun Paleta(color: Color, alCambiar: (Color) -> Unit) {
    val c = LocalColores.current
    // El tono se guarda aparte: en grises o negro el color ya no lo recuerda.
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    var tono by remember { mutableFloatStateOf(hsv[0]) }
    if (hsv[1] > 0.02f && hsv[2] > 0.02f) tono = hsv[0]
    val actual by rememberUpdatedState(hsv)
    val cambiar by rememberUpdatedState(alCambiar)
    val forma = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(forma)
                .border(1.dp, c.linea, forma)
                .arrastre { p, ancho, alto ->
                    val s = (p.x / ancho).coerceIn(0f, 1f)
                    val v = 1f - (p.y / alto).coerceIn(0f, 1f)
                    cambiar(Color.hsv(tono, s, v))
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(tono, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val punto = Offset(actual[1] * size.width, (1f - actual[2]) * size.height)
            drawCircle(Color.White, 11.dp.toPx(), punto, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), punto, style = Stroke(1.dp.toPx()))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(CircleShape)
                .arrastre { p, ancho, _ ->
                    tono = (p.x / ancho).coerceIn(0f, 1f) * 360f
                    // Si estaba en gris, al elegir tono se le da color.
                    val s = actual[1].coerceAtLeast(0.6f)
                    val v = actual[2].coerceAtLeast(0.35f)
                    cambiar(Color.hsv(tono.coerceAtMost(359.9f), s, v))
                },
        ) {
            drawRect(Brush.horizontalGradient(ARCOIRIS))
            val x = tono / 360f * size.width
            drawCircle(Color.White, size.height / 2 - 3.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
        }
    }
}

/** Tocar o arrastrar dentro: avisa la posición del dedo y el tamaño del área. */
private fun Modifier.arrastre(alMover: (Offset, Float, Float) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val abajo = awaitFirstDown()
        abajo.consume()
        alMover(abajo.position, size.width.toFloat(), size.height.toFloat())
        while (true) {
            val ev = awaitPointerEvent()
            val dedo: PointerInputChange = ev.changes.firstOrNull { it.id == abajo.id } ?: break
            if (!dedo.pressed) break
            dedo.consume()
            alMover(dedo.position, size.width.toFloat(), size.height.toFloat())
        }
    }
}
